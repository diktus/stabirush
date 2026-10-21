function renderState(s) {
  if (!s) return;

  const toggle = document.getElementById('vpn-toggle');
  const pill = document.getElementById('status-pill');
  const statusText = document.getElementById('status-text');
  const heroTitle = document.getElementById('hero-title');
  const heroSub = document.getElementById('hero-sub');
  const pingBadge = document.getElementById('ping-badge');
  const errorBox = document.getElementById('error-box');

  toggle.checked = Boolean(s.enabled);
  const isSecure = s.mode === 'full' || s.mode === 'secure';

  if (s.enabled) {
    pill.classList.add('connected');
    statusText.textContent = isSecure ? 'Secure Active' : 'Connected';
    heroTitle.textContent = isSecure ? '🔒 Secure Full Tunnel (0.0.0.0/0)' : 'Stabirush Private Network';
    heroSub.textContent = `${s.proxyServer} · ${isSecure ? '0.0.0.0/0 Encrypted' : 'Split Tunnel'}`;
  } else {
    pill.classList.remove('connected');
    statusText.textContent = 'Disconnected';
    heroTitle.textContent = 'Stabirush WireGuard VPN';
    heroSub.textContent = 'Klik saklar untuk terhubung ke jaringan wg0';
  }

  if (s.lastPingMs !== null && s.enabled) {
    pingBadge.textContent = `${s.lastPingMs} ms`;
    pingBadge.style.color = '#10b981';
  } else {
    pingBadge.textContent = '';
  }

  if (s.lastError) {
    errorBox.style.display = 'block';
    errorBox.textContent = s.lastError;
  } else {
    errorBox.style.display = 'none';
  }

  document.getElementById('mode-split').classList.toggle('active', !isSecure);
  document.getElementById('mode-full').classList.toggle('active', isSecure);

  const routesList = document.getElementById('routes-list');
  const patterns = s.privatePatterns || ['10.10.0.*', '192.168.101.*', '10.0.0.*'];
  if (isSecure) {
    routesList.innerHTML = `<span class="route-badge" style="background:rgba(16,185,129,0.18);color:#10b981;border-color:rgba(16,185,129,0.4);">🔒 0.0.0.0/0 (All Traffic)</span>` +
      patterns.map(p => `<span class="route-badge">${p}</span>`).join('');
  } else {
    routesList.innerHTML = patterns
      .map(p => `<span class="route-badge">${p}</span>`)
      .join('');
  }

  document.getElementById('input-server').value = s.proxyServer || '168.110.193.226:51821';
  document.getElementById('input-patterns').value = patterns.join(', ');
  document.getElementById('input-token').value = s.token || 'stabirush-ext-2026';
}

document.addEventListener('DOMContentLoaded', async () => {
  const res = await chrome.runtime.sendMessage({ action: 'GET_STATE' });
  if (res && res.state) {
    renderState(res.state);
  }

  document.getElementById('vpn-toggle').addEventListener('change', async (e) => {
    const enabled = e.target.checked;
    const r = await chrome.runtime.sendMessage({ action: 'TOGGLE_VPN', enabled });
    if (r && r.state) renderState(r.state);
  });

  document.getElementById('mode-split').addEventListener('click', async () => {
    const r = await chrome.runtime.sendMessage({
      action: 'UPDATE_CONFIG',
      config: { mode: 'split' }
    });
    if (r && r.state) renderState(r.state);
  });

  document.getElementById('mode-full').addEventListener('click', async () => {
    const r = await chrome.runtime.sendMessage({
      action: 'UPDATE_CONFIG',
      config: { mode: 'full' }
    });
    if (r && r.state) renderState(r.state);
  });

  document.querySelectorAll('.quick-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const url = btn.getAttribute('data-url');
      if (url) {
        await chrome.tabs.create({ url });
      }
    });
  });

  document.getElementById('btn-toggle-settings').addEventListener('click', () => {
    document.getElementById('settings-panel').classList.toggle('open');
  });

  document.getElementById('btn-save-settings').addEventListener('click', async () => {
    const proxyServer = document.getElementById('input-server').value.trim() || '168.110.193.226:51821';
    const rawPatterns = document.getElementById('input-patterns').value;
    const privatePatterns = rawPatterns.split(',').map(s => s.trim()).filter(Boolean);
    const token = document.getElementById('input-token').value.trim() || 'stabirush-ext-2026';

    const r = await chrome.runtime.sendMessage({
      action: 'UPDATE_CONFIG',
      config: { proxyServer, privatePatterns, token }
    });
    if (r && r.state) {
      renderState(r.state);
      document.getElementById('settings-panel').classList.remove('open');
    }
  });
});
