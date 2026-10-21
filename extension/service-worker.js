const DEFAULT_STATE = {
  enabled: false,
  mode: 'split', // 'split' (Private IPs only) or 'full' (Secure 0.0.0.0/0 All traffic)
  proxyServer: '168.110.193.226:51821',
  token: 'stabirush-ext-2026',
  privatePatterns: [
    '10.10.0.*',
    '192.168.101.*',
    '10.0.0.*'
  ],
  lastPingMs: null,
  clientPublicIP: '',
  lastError: ''
};

async function getState() {
  const stored = await chrome.storage.local.get('vpnState');
  return { ...DEFAULT_STATE, ...(stored.vpnState || {}) };
}

async function saveState(partial) {
  const current = await getState();
  const updated = { ...current, ...partial };
  await chrome.storage.local.set({ vpnState: updated });
  return updated;
}

function buildPacScript(proxyHostPort, mode, patterns) {
  const serverHost = proxyHostPort.split(':')[0];

  if (mode === 'full' || mode === 'secure') {
    return `
function FindProxyForURL(url, host) {
  if (host === "localhost" || host === "127.0.0.1" || host === "${serverHost}") {
    return "DIRECT";
  }
  return "PROXY ${proxyHostPort}; DIRECT";
}`;
  }

  const conditions = (patterns || DEFAULT_STATE.privatePatterns)
    .map(p => p.trim())
    .filter(Boolean)
    .map(p => `shExpMatch(host, "${p.replace(/"/g, '')}")`)
    .join(' || ');

  return `
function FindProxyForURL(url, host) {
  if (host === "localhost" || host === "127.0.0.1") {
    return "DIRECT";
  }
  if (${conditions || 'false'}) {
    return "PROXY ${proxyHostPort}; DIRECT";
  }
  return "DIRECT";
}`;
}

async function pingGateway(state) {
  const start = Date.now();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const res = await fetch(`http://${state.proxyServer}/api/extension/ping`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-WG-Token': state.token
      },
      body: JSON.stringify({ mode: state.mode }),
      signal: controller.signal
    });
    clearTimeout(timer);

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const data = await res.json();
    const latency = Date.now() - start;

    let patterns = state.privatePatterns || DEFAULT_STATE.privatePatterns;
    if (Array.isArray(data.patterns) && data.patterns.length > 0) {
      const merged = new Set([...patterns, ...data.patterns]);
      patterns = Array.from(merged);
    }

    return await saveState({
      lastPingMs: latency,
      clientPublicIP: data.clientIP || '',
      privatePatterns: patterns,
      lastError: ''
    });
  } catch (err) {
    return await saveState({
      lastError: err.message || 'Gagal menghubungi gateway Stabirush'
    });
  }
}

async function applyProxyState(state) {
  if (state.enabled) {
    const updated = await pingGateway(state);
    const pacData = buildPacScript(updated.proxyServer, updated.mode, updated.privatePatterns);

    await chrome.proxy.settings.set({
      value: {
        mode: 'pac_script',
        pacScript: {
          data: pacData
        }
      },
      scope: 'regular'
    });

    const badgeTxt = (updated.mode === 'full' || updated.mode === 'secure') ? 'SEC' : 'ON';
    await chrome.action.setBadgeText({ text: badgeTxt });
    await chrome.action.setBadgeBackgroundColor({ color: '#10b981' });
    await chrome.alarms.create('wgHeartbeat', { periodInMinutes: 4 });
    return updated;
  } else {
    await chrome.proxy.settings.clear({ scope: 'regular' });
    await chrome.action.setBadgeText({ text: '' });
    await chrome.alarms.clear('wgHeartbeat');
    return await saveState({ lastError: '' });
  }
}

chrome.runtime.onInstalled.addListener(async () => {
  const s = await getState();
  await applyProxyState(s);
});

chrome.runtime.onStartup.addListener(async () => {
  const s = await getState();
  await applyProxyState(s);
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === 'wgHeartbeat') {
    const s = await getState();
    if (s.enabled) {
      await pingGateway(s);
    }
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      if (message.action === 'GET_STATE') {
        const s = await getState();
        sendResponse({ ok: true, state: s });
      } else if (message.action === 'TOGGLE_VPN') {
        const next = await saveState({ enabled: Boolean(message.enabled) });
        const applied = await applyProxyState(next);
        sendResponse({ ok: true, state: applied });
      } else if (message.action === 'UPDATE_CONFIG') {
        const next = await saveState(message.config || {});
        const applied = await applyProxyState(next);
        sendResponse({ ok: true, state: applied });
      } else if (message.action === 'PING_NOW') {
        const current = await getState();
        const updated = await pingGateway(current);
        sendResponse({ ok: true, state: updated });
      } else {
        sendResponse({ ok: false, error: 'Unknown action' });
      }
    } catch (e) {
      sendResponse({ ok: false, error: e.message });
    }
  })();
  return true;
});
