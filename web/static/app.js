// =========================================================
// STABIRUSH WIREGUARD MANAGEMENT - CLIENT LOGIC
// =========================================================

let state = {
  devices: [],
  namedSubnets: [],
  status: null,
  filter: 'all',
  searchQuery: '',
  selectedPlatform: 'macos',
  activeTab: 'machines',
  createdConfig: '',
  createdDeviceName: '',
  viewingConfig: '',
  viewingPubKey: '',
  viewingDeviceName: '',
  viewingMode: 'split',
  viewingTransport: 'udp',
  editingWifiRules: [],
  refreshTimer: null,
};

// Platform Icons (SVG)
const OS_ICONS = {
  macos: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M15.97 6.37c.63-.76 1.06-1.82.94-2.88-.91.04-2.02.61-2.67 1.37-.58.67-1.09 1.74-.95 2.78 1.02.08 2.06-.51 2.68-1.27z"/></svg>`,
  ios: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"></rect><line x1="12" y1="18" x2="12.01" y2="18"></line></svg>`,
  windows: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451L0 20.699M10.949 12.6H24V24l-13.051-1.801"/></svg>`,
  linux: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12.001 2c-3.1 0-5.617 2.516-5.617 5.617 0 .895.21 1.742.586 2.492C5.748 10.92 5 12.188 5 13.61c0 2.22 1.428 4.116 3.447 4.793.18 1.996 1.848 3.597 3.554 3.597s3.374-1.601 3.554-3.597C17.572 17.726 19 15.83 19 13.61c0-1.422-.748-2.69-1.97-3.501.376-.75.586-1.597.586-2.492C17.616 4.516 15.1 2 12.001 2z"/></svg>`,
  android: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.551 0 .9993.4482.9993.9993.0001.5511-.4483.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4482.9993.9993 0 .5511-.4482.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.4116 13.8533 8.1 12 8.1s-3.5902.3116-5.1368.8497L4.8409 5.4467a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.1521.5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3432-4.1021-2.6889-7.5743-6.1185-9.4396"/></svg>`,
  router: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zm0 2v8h16V8H4zm3 2h2v2H7v-2zm4 0h2v2h-2v-2zm4 0h2v2h-2v-2z"/></svg>`,
  browser: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>`,
  device: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>`,
};

// Initialize app
document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  refreshAll();
  state.refreshTimer = setInterval(refreshTelemetry, 3000);

  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement.tagName !== 'INPUT' && document.activeElement.tagName !== 'TEXTAREA') {
      e.preventDefault();
      const input = document.getElementById('machine-search');
      if (input) input.focus();
    }
    if (e.key === 'Escape') {
      closeAllModals();
    }
  });
});

// Theme handling
function initTheme() {
  const saved = localStorage.getItem('wg_theme') || 'light';
  document.documentElement.setAttribute('data-theme', saved);
  updateThemeIcon(saved);
}

function toggleTheme() {
  const current = document.documentElement.getAttribute('data-theme') || 'light';
  const next = current === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem('wg_theme', next);
  updateThemeIcon(next);
}

function updateThemeIcon(theme) {
  const btn = document.getElementById('theme-toggle');
  if (!btn) return;
  if (theme === 'dark') {
    btn.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>`;
  } else {
    btn.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>`;
  }
}

// Navigation Tabs
function switchTab(tabId) {
  state.activeTab = tabId;
  document.querySelectorAll('.nav-tab').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-tab') === tabId);
  });
  document.querySelectorAll('.tab-pane').forEach(pane => {
    pane.classList.toggle('active', pane.id === `pane-${tabId}`);
  });

  if (tabId === 'bypass') {
    loadBypassStatus();
    fetchNamedSubnets().then(() => {
      renderNamedSubnetsPills();
      populateCustomClientDeviceSelect();
    });
  }
  if (tabId === 'subnets') {
    fetchNamedSubnets().then(() => loadSubnets());
  }
  if (tabId === 'dns') loadDNSInfo();
  if (tabId === 'config') loadRawConfig();
  if (tabId === 'users') {
    fetchUsers();
    fetchUserTokens();
  }
  if (tabId === 'settings') loadSettings();
}

// Data fetching
async function refreshAll() {
  await Promise.all([fetchStatus(), fetchDevices(), fetchNamedSubnets(), fetchUsers()]);
  renderMachinesTable();
  updateStats();
  renderNamedSubnetsPills();
  if (state.activeTab === 'bypass') {
    populateCustomClientDeviceSelect();
  }
  if (state.activeTab === 'subnets') {
    loadSubnets();
  }
}

async function refreshTelemetry() {
  try {
    const [statusRes, devicesRes] = await Promise.all([
      fetch('/api/status'),
      fetch('/api/devices')
    ]);
    if (statusRes.ok) state.status = await statusRes.json();
    if (devicesRes.ok) state.devices = await devicesRes.json();

    updateStats();
    if (state.activeTab === 'machines') {
      renderMachinesTable();
    }
  } catch (e) {
    console.warn("Background telemetry update failed", e);
  }
}

async function fetchStatus() {
  try {
    const res = await fetch('/api/status');
    if (res.ok) state.status = await res.json();
  } catch (e) {
    console.error("Failed to fetch server status:", e);
  }
}

async function fetchDevices() {
  try {
    const res = await fetch('/api/devices');
    if (res.ok) state.devices = await res.json();
  } catch (e) {
    console.error("Failed to fetch devices:", e);
  }
}

async function fetchNamedSubnets() {
  try {
    const res = await fetch('/api/named-subnets');
    if (res.ok) state.namedSubnets = await res.json();
  } catch (e) {
    console.error("Failed to fetch named subnets:", e);
  }
}

function updateStats() {
  if (!state.status) return;

  const s = state.status;
  document.getElementById('stat-total-devices').textContent = s.totalDevices;
  document.getElementById('stat-connected-devices').textContent = s.connectedDevices;
  document.getElementById('stat-total-transfer').textContent = formatCombinedBytes(s.totalRx + s.totalTx);
  document.getElementById('stat-transfer-sub').textContent = `↓ ${s.totalRxHuman} received · ↑ ${s.totalTxHuman} sent`;
  document.getElementById('stat-server-endpoint').textContent = s.externalEndpoint || `${s.address}`;

  if (s.bypass) {
    const wsTxt = s.bypass.wstunnelRunning ? `wstunnel:${s.bypass.wstunnelPort}` : 'wstunnel:off';
    const u2rTxt = s.bypass.udp2rawRunning ? `udp2raw:${s.bypass.udp2rawPort}` : 'udp2raw:off';
    document.getElementById('stat-server-iface').textContent = `${s.interface} (${s.address}) · TCP: ${wsTxt}, ${u2rTxt}`;
    const bBadge = document.getElementById('tab-badge-bypass');
    if (bBadge) {
      bBadge.textContent = (s.bypass.wstunnelRunning || s.bypass.udp2rawRunning) ? 'TCP ON' : 'OFF';
    }
  } else {
    document.getElementById('stat-server-iface').textContent = `Interface: ${s.interface} (${s.address})`;
  }

  document.getElementById('tab-badge-machines').textContent = s.totalDevices;
  document.getElementById('tab-badge-subnets').textContent = s.totalSubnets;

  document.getElementById('count-all').textContent = s.totalDevices;
  document.getElementById('count-connected').textContent = s.connectedDevices;
  document.getElementById('count-offline').textContent = s.totalDevices - s.connectedDevices;
  document.getElementById('count-subnets').textContent = s.totalSubnets;

  const memElem = document.getElementById('runtime-memory');
  if (memElem) memElem.textContent = `${(s.memoryUsageKb / 1024).toFixed(1)} MB RAM`;
  const upElem = document.getElementById('runtime-uptime');
  if (upElem) upElem.textContent = s.uptime;
}

function formatCombinedBytes(b) {
  if (b === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KiB", "MiB", "GiB", "TiB"];
  const i = Math.floor(Math.log(b) / Math.log(k));
  return parseFloat((b / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

// Machine Table Rendering
function setFilter(filter) {
  state.filter = filter;
  document.querySelectorAll('.filter-pill[data-filter]').forEach(pill => {
    pill.classList.toggle('active', pill.getAttribute('data-filter') === filter);
  });
  renderMachinesTable();
}

function filterMachines() {
  const input = document.getElementById('machine-search');
  state.searchQuery = input ? input.value.trim().toLowerCase() : '';
  renderMachinesTable();
}

function renderMachinesTable() {
  const tbody = document.getElementById('machines-table-body');
  const emptyState = document.getElementById('machines-empty-state');
  if (!tbody) return;

  const q = state.searchQuery;
  const filtered = (state.devices || []).filter(dev => {
    if (state.filter === 'connected' && !dev.isConnected) return false;
    if (state.filter === 'offline' && dev.isConnected) return false;
    if (state.filter === 'subnets' && !dev.isSubnetRouter) return false;

    if (q) {
      const matchName = (dev.name || '').toLowerCase().includes(q);
      const matchIP = (dev.allocatedIP || '').toLowerCase().includes(q);
      const matchKey = (dev.publicKey || '').toLowerCase().includes(q);
      const matchSub = (dev.subnetRoutes || []).some(s => s.toLowerCase().includes(q));
      if (!matchName && !matchIP && !matchKey && !matchSub) return false;
    }
    return true;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = '';
    if (emptyState) emptyState.style.display = 'block';
    return;
  }

  if (emptyState) emptyState.style.display = 'none';

  tbody.innerHTML = filtered.map(dev => {
    const osIcon = OS_ICONS[dev.os] || OS_ICONS.device;
    const truncatedKey = dev.publicKey ? `${dev.publicKey.substring(0, 10)}...${dev.publicKey.substring(dev.publicKey.length - 6)}` : '';
    const isConn = dev.isConnected;

    let tagsHtml = '';
    if (!dev.enabled) {
      tagsHtml += `<span class="badge-tag badge-disabled">Disabled</span>`;
    }
    if (dev.routingMode === 'secure' || dev.routingMode === 'full') {
      tagsHtml += `<span class="badge-tag" style="background:var(--status-connected-bg);color:var(--status-connected);border:1px solid var(--status-connected-border);" title="Full Tunnel 0.0.0.0/0">🔒 Secure 0.0.0.0/0</span>`;
    } else {
      tagsHtml += `<span class="badge-tag" style="background:var(--bg-surface-secondary);color:var(--text-muted);border:1px solid var(--border-color);" title="Split Tunnel Private Network">Split</span>`;
    }
    if (dev.transportMode === 'wstunnel' || dev.transportMode === 'udp2raw') {
      tagsHtml += `<span class="badge-tag badge-subnet" title="TCP Bypass Active">⚡ TCP:${escapeHtml(dev.transportMode)}</span>`;
    }
    if (dev.isSubnetRouter && dev.subnetRoutes) {
      dev.subnetRoutes.forEach(sub => {
        tagsHtml += `<span class="badge-tag badge-subnet" title="Subnet Router">routes ${escapeHtml(sub)}</span>`;
      });
    }

    return `
      <tr data-pubkey="${escapeHtml(dev.publicKey)}">
        <!-- MACHINE -->
        <td>
          <div class="machine-cell">
            <div class="os-icon-wrapper" title="${escapeHtml(dev.os)}">
              ${osIcon}
            </div>
            <div class="machine-info">
              <div class="machine-name-row">
                <span class="machine-name">${escapeHtml(dev.name)}</span>
                ${tagsHtml}
              </div>
              <div class="machine-key" title="Full Public Key: ${escapeHtml(dev.publicKey)}">
                <span>${escapeHtml(truncatedKey)}</span>
                <button class="btn-copy" onclick="copyText('${escapeHtml(dev.publicKey)}', 'Public key copied!')" title="Copy public key">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
              </div>
            </div>
          </div>
        </td>

        <!-- ADDRESSES -->
        <td>
          <div class="address-cell">
            <div class="ip-row">
              <span class="ip-text">${escapeHtml(dev.allocatedIP || dev.allowedIPs)}</span>
              <button class="btn-copy" onclick="copyText('${escapeHtml(dev.allocatedIP)}', 'IP copied!')" title="Copy IP">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
              </button>
            </div>
            <div class="endpoint-text" title="External Endpoint">
              ${dev.endpoint ? escapeHtml(dev.endpoint) : '<span style="color:var(--text-subtle);">(idle / no endpoint)</span>'}
            </div>
          </div>
        </td>

        <!-- STATUS -->
        <td>
          <div class="status-cell">
            <span class="status-dot ${isConn ? 'connected' : 'offline'}"></span>
            <div class="status-info">
              <span class="status-title" style="color: ${isConn ? 'var(--status-connected)' : 'var(--text-muted)'};">
                ${isConn ? 'Connected' : 'Offline'}
              </span>
              <span class="status-subtitle">
                ${dev.latestHandshakeHuman ? dev.latestHandshakeHuman : 'never'}
              </span>
            </div>
          </div>
        </td>

        <!-- TRAFFIC -->
        <td>
          <div class="traffic-cell">
            <div class="traffic-row">
              <span class="traffic-arrow-rx">↓</span>
              <span>${dev.transferRxHuman || '0 B'}</span>
            </div>
            <div class="traffic-row">
              <span class="traffic-arrow-tx">↑</span>
              <span>${dev.transferTxHuman || '0 B'}</span>
            </div>
          </div>
        </td>

        <!-- ACTIONS -->
        <td>
          <div class="actions-cell">
            <button class="btn-secondary btn-sm" onclick="openSetupModal('${escapeHtml(dev.publicKey)}', '${escapeHtml(dev.name)}')">
              Setup / QR
            </button>
            <button class="btn-secondary btn-sm" onclick="downloadDeviceConfig('${escapeHtml(dev.publicKey)}', '${escapeHtml(dev.name)}')">
              Download
            </button>
            <button class="btn-secondary btn-sm" onclick="openEditModal('${escapeHtml(dev.publicKey)}')">
              Edit
            </button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

// Modal: Add Device
async function openAddDeviceModal() {
  try {
    const res = await fetch('/api/devices/next-ip');
    if (res.ok) {
      const data = await res.json();
      document.getElementById('new-device-ip').value = data.nextIP || '10.10.0.6';
    }
  } catch (e) {
    console.warn("Could not fetch next IP:", e);
  }

  document.getElementById('new-device-name').value = '';
  document.getElementById('new-device-subnets').value = '';
  document.getElementById('new-device-routing').value = 'split';
  const transSelect = document.getElementById('new-device-transport');
  if (transSelect) transSelect.value = 'udp';
  selectPlatform('macos');

  resetAddDeviceModal();
  openModal('modal-add-device');
  setTimeout(() => document.getElementById('new-device-name').focus(), 100);
}

function selectPlatform(os) {
  state.selectedPlatform = os;
  document.querySelectorAll('.platform-btn').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-os') === os);
  });
}

function resetAddDeviceModal() {
  document.getElementById('add-device-step1').style.display = 'block';
  document.getElementById('add-device-step2').style.display = 'none';
  const qrContainer = document.getElementById('qrcode-container');
  if (qrContainer) qrContainer.innerHTML = '';
}

async function submitCreateDevice(e) {
  e.preventDefault();
  const btn = document.getElementById('btn-submit-create');
  btn.disabled = true;
  btn.textContent = 'Generating...';

  const name = document.getElementById('new-device-name').value.trim();
  const allocatedIP = document.getElementById('new-device-ip').value.trim();
  const routingMode = document.getElementById('new-device-routing').value;
  const transportMode = document.getElementById('new-device-transport') ? document.getElementById('new-device-transport').value : 'udp';
  const advertisedRoute = document.getElementById('new-device-subnets').value.trim();

  try {
    const res = await fetch('/api/devices', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        os: state.selectedPlatform,
        allocatedIP,
        routingMode,
        transportMode,
        advertisedRoute,
      })
    });

    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.error || 'Failed to create device');
    }

    const data = await res.json();
    state.createdConfig = data.configFile;
    state.createdDeviceName = name;

    document.getElementById('add-device-step1').style.display = 'none';
    document.getElementById('add-device-step2').style.display = 'block';
    document.getElementById('success-device-title').textContent = `${name} Added to Stabirush!`;
    document.getElementById('new-config-preview').textContent = data.configFile;

    renderQRCode('qrcode-container', data.configFile);

    showToast(`Machine ${name} registered to Stabirush!`, 'success');
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Generate Machine';
  }
}

function renderQRCode(elementId, text) {
  const container = document.getElementById(elementId);
  if (!container) return;
  container.innerHTML = '';
  if (window.QRCode) {
    new QRCode(container, {
      text: text,
      width: 220,
      height: 220,
      colorDark: '#111827',
      colorLight: '#ffffff',
      correctLevel: QRCode.CorrectLevel.M
    });
  } else {
    container.innerHTML = `<div style="padding:1rem; color:var(--text-muted);">QR Code library loading...</div>`;
  }
}

function copyCreatedConfig() {
  copyText(state.createdConfig, 'Configuration profile copied to clipboard!');
}

function downloadCreatedConfig() {
  const safeName = (state.createdDeviceName || 'stabirush').toLowerCase().replace(/[^a-z0-9]/g, '-');
  downloadBlob(state.createdConfig, `stabirush-${safeName}.conf`);
}

// Modal: Setup / QR for existing device with interactive Mode & Transport switcher
async function openSetupModal(pubKey, name) {
  const dev = state.devices.find(d => d.publicKey === pubKey);
  state.viewingPubKey = pubKey;
  state.viewingDeviceName = name;
  state.viewingConfig = '';
  state.viewingMode = (dev && dev.routingMode) ? dev.routingMode : 'split';
  state.viewingTransport = (dev && dev.transportMode) ? dev.transportMode : 'udp';

  document.getElementById('view-setup-title').textContent = `Stabirush Setup · ${name}`;
  updateSetupModalPills();
  openModal('modal-view-setup');

  await fetchAndRenderSetupConfig();
}

function updateSetupModalPills() {
  const mSplit = document.getElementById('setup-mode-split');
  const mSecure = document.getElementById('setup-mode-secure');
  if (mSplit && mSecure) {
    mSplit.classList.toggle('active', state.viewingMode !== 'secure');
    mSecure.classList.toggle('active', state.viewingMode === 'secure');
  }

  ['udp', 'wstunnel', 'udp2raw'].forEach(t => {
    const el = document.getElementById(`setup-trans-${t}`);
    if (el) el.classList.toggle('active', state.viewingTransport === t);
  });
}

async function switchSetupProfile(mode, transport) {
  if (mode) state.viewingMode = mode;
  if (transport) state.viewingTransport = transport;
  updateSetupModalPills();
  await fetchAndRenderSetupConfig();
}

async function fetchAndRenderSetupConfig() {
  const pubKey = state.viewingPubKey;
  if (!pubKey) return;

  document.getElementById('view-config-preview').textContent = 'Loading configuration...';
  const qrContainer = document.getElementById('view-qrcode-container');
  if (qrContainer) qrContainer.innerHTML = '';
  const banner = document.getElementById('view-rekey-banner');
  if (banner) banner.style.display = 'none';

  try {
    const url = `/api/device/config?key=${encodeURIComponent(pubKey)}&mode=${encodeURIComponent(state.viewingMode)}&transport=${encodeURIComponent(state.viewingTransport)}`;
    const res = await fetch(url);
    if (!res.ok) {
      let errMsg = 'Could not retrieve config';
      try {
        const errJson = await res.json();
        if (errJson.error) errMsg = errJson.error;
      } catch (_) {}
      throw new Error(errMsg);
    }
    const configText = await res.text();
    state.viewingConfig = configText;

    document.getElementById('view-config-preview').textContent = configText;
    renderQRCode('view-qrcode-container', configText);

    if (configText.includes('<YOUR_CLIENT_PRIVATE_KEY>')) {
      document.getElementById('view-qr-hint').textContent = 'Catatan: Peer lama (Private Key ada di perangkat client). Klik "Generate Kunci Baru" di atas jika ingin membuat QR & config baru.';
      if (banner) banner.style.display = 'flex';
    } else {
      const modeDesc = state.viewingMode === 'secure' ? '🔒 Secure Full Tunnel (0.0.0.0/0)' : 'Split Tunnel (Private Network)';
      const transDesc = state.viewingTransport === 'udp' ? 'Standard UDP 51820' : `⚡ TCP Bypass (${state.viewingTransport})`;
      document.getElementById('view-qr-hint').textContent = `${modeDesc} · ${transDesc}`;
      if (banner) banner.style.display = 'none';
    }
  } catch (err) {
    document.getElementById('view-config-preview').textContent = 'Error loading configuration: ' + err.message;
  }
}

async function rekeyCurrentDevice() {
  if (!state.viewingPubKey) return;
  if (!confirm(`Buat pasangan kunci (Keypair) baru untuk "${state.viewingDeviceName}"?\n\nPerangkat harus mengimpor ulang QR code atau file .conf yang baru ini agar bisa terhubung.`)) {
    return;
  }

  try {
    const res = await fetch(`/api/device/rekey?key=${encodeURIComponent(state.viewingPubKey)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicKey: state.viewingPubKey })
    });
    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.error || 'Failed to regenerate key');
    }

    const data = await res.json();
    state.viewingPubKey = data.device.publicKey;
    await fetchAndRenderSetupConfig();

    showToast(`Kunci baru untuk ${state.viewingDeviceName} berhasil dibuat!`, 'success');
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

function copyExistingConfig() {
  if (!state.viewingConfig) {
    showToast('Config belum dimuat', 'error');
    return;
  }
  copyText(state.viewingConfig, 'Configuration profile copied!');
}

function downloadExistingConfig() {
  if (!state.viewingConfig) {
    showToast('Config belum tersedia untuk diunduh', 'error');
    return;
  }
  const safeName = (state.viewingDeviceName || 'stabirush').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
  const suffix = (state.viewingMode === 'secure' ? '-secure' : '') + (state.viewingTransport !== 'udp' ? `-${state.viewingTransport}` : '');
  downloadBlob(state.viewingConfig, `stabirush-${safeName}${suffix}.conf`);
}

function downloadCustomClientForViewing(platform) {
  if (!state.viewingPubKey) return;
  window.location.href = `/api/device/custom-client?key=${encodeURIComponent(state.viewingPubKey)}&platform=${encodeURIComponent(platform)}`;
}

function downloadQRImage(containerId, deviceName) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const srcCanvas = container.querySelector('canvas');
  const srcImg = container.querySelector('img');

  const pad = 24;
  const outCanvas = document.createElement('canvas');
  const ctx = outCanvas.getContext('2d');

  const exportFromSource = (source, w, h) => {
    outCanvas.width = w + pad * 2;
    outCanvas.height = h + pad * 2;
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, outCanvas.width, outCanvas.height);
    ctx.drawImage(source, pad, pad, w, h);

    const safeName = (deviceName || 'stabirush').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
    const dataUrl = outCanvas.toDataURL('image/png');
    const a = document.createElement('a');
    a.href = dataUrl;
    a.download = `stabirush-${safeName}-qr.png`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    showToast('QR Code (.png) berhasil diunduh!', 'success');
  };

  if (srcCanvas) {
    exportFromSource(srcCanvas, srcCanvas.width || 220, srcCanvas.height || 220);
  } else if (srcImg && srcImg.src) {
    exportFromSource(srcImg, srcImg.naturalWidth || 220, srcImg.naturalHeight || 220);
  } else {
    showToast('QR Code belum tersedia', 'error');
  }
}

function downloadDeviceConfig(pubKey, name) {
  const dev = state.devices.find(d => d.publicKey === pubKey);
  const mode = (dev && dev.routingMode) ? dev.routingMode : 'split';
  const trans = (dev && dev.transportMode) ? dev.transportMode : 'udp';
  window.location.href = `/api/device/config?key=${encodeURIComponent(pubKey)}&name=${encodeURIComponent(name || '')}&mode=${encodeURIComponent(mode)}&transport=${encodeURIComponent(trans)}&download=true`;
}

// Modal: Edit Device
function openEditModal(pubKey) {
  const dev = state.devices.find(d => d.publicKey === pubKey);
  if (!dev) return;

  const fallbackAllowed = dev.allocatedIP
    ? [dev.allocatedIP + '/32', ...(dev.subnetRoutes || [])].join(', ')
    : '';

  document.getElementById('edit-device-pubkey').value = pubKey;
  document.getElementById('edit-device-name').value = dev.name || '';
  document.getElementById('edit-device-os').value = dev.os || 'device';
  document.getElementById('edit-device-routing').value = (dev.routingMode === 'secure' || dev.routingMode === 'full') ? 'secure' : 'split';
  document.getElementById('edit-device-transport').value = dev.transportMode || 'udp';
  document.getElementById('edit-device-allowedips').value = dev.allowedIPs || dev.runtimeAllowedIPs || fallbackAllowed;
  document.getElementById('edit-device-enabled').checked = dev.enabled !== false;

  openModal('modal-edit-device');
}

async function submitEditDevice(e) {
  e.preventDefault();
  const pubKey = document.getElementById('edit-device-pubkey').value;
  const name = document.getElementById('edit-device-name').value.trim();
  const os = document.getElementById('edit-device-os').value;
  const routingMode = document.getElementById('edit-device-routing').value;
  const transportMode = document.getElementById('edit-device-transport').value;
  const allowedIPs = document.getElementById('edit-device-allowedips').value.trim();
  const enabled = document.getElementById('edit-device-enabled').checked;

  try {
    const res = await fetch(`/api/device?key=${encodeURIComponent(pubKey)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicKey: pubKey, name, os, allowedIPs, enabled, routingMode, transportMode })
    });
    if (!res.ok) {
      let errMsg = 'Failed to update device';
      try {
        const errJson = await res.json();
        if (errJson.error) errMsg = errJson.error;
      } catch (_) {}
      throw new Error(errMsg);
    }

    closeModal('modal-edit-device');
    showToast(`Machine ${name} updated successfully!`, 'success');
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

async function confirmDeleteDevice() {
  const pubKey = document.getElementById('edit-device-pubkey').value;
  const name = document.getElementById('edit-device-name').value;
  if (!confirm(`Are you sure you want to remove "${name}" from Stabirush? This will instantly revoke access.`)) {
    return;
  }

  try {
    const res = await fetch(`/api/device?key=${encodeURIComponent(pubKey)}`, {
      method: 'DELETE'
    });
    if (!res.ok) {
      let errMsg = 'Failed to delete device';
      try {
        const errJson = await res.json();
        if (errJson.error) errMsg = errJson.error;
      } catch (_) {}
      throw new Error(errMsg);
    }

    closeModal('modal-edit-device');
    showToast(`Machine ${name} removed from Stabirush.`, 'success');
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

// =========================================================
// TAB: TCP BYPASS (WSTUNNEL / UDP2RAW) & WI-FI SSID CLIENT
// =========================================================

async function loadBypassStatus() {
  try {
    const res = await fetch('/api/bypass');
    if (!res.ok) return;
    const b = await res.json();

    const wsStatus = document.getElementById('bypass-ws-status');
    const wsPid = document.getElementById('bypass-ws-pid');
    if (wsStatus) {
      wsStatus.innerHTML = b.wstunnelRunning
        ? `<span style="color:var(--status-connected);font-weight:600;">● Running</span> · <code>${escapeHtml(b.wstunnelUrl)}</code>`
        : `<span style="color:var(--danger);">○ Stopped</span>`;
    }
    if (wsPid) {
      wsPid.textContent = b.wstunnelRunning ? `PID ${b.wstunnelPid}` : 'Inactive';
    }

    const u2rStatus = document.getElementById('bypass-u2r-status');
    const u2rPid = document.getElementById('bypass-u2r-pid');
    if (u2rStatus) {
      u2rStatus.innerHTML = b.udp2rawRunning
        ? `<span style="color:var(--status-connected);font-weight:600;">● Running</span> · <code>${escapeHtml(b.udp2rawEndpoint)}</code>`
        : `<span style="color:var(--danger);">○ Stopped</span>`;
    }
    if (u2rPid) {
      u2rPid.textContent = b.udp2rawRunning ? `PID ${b.udp2rawPid}` : 'Inactive';
    }

    document.getElementById('cfg-ws-enabled').checked = Boolean(b.wstunnelEnabled);
    document.getElementById('cfg-ws-port').value = b.wstunnelPort || 8443;
    document.getElementById('cfg-ws-prefix').value = b.wstunnelPrefix || 'stabirush';

    document.getElementById('cfg-u2r-enabled').checked = Boolean(b.udp2rawEnabled);
    document.getElementById('cfg-u2r-port').value = b.udp2rawPort || 4096;
    document.getElementById('cfg-u2r-pass').value = b.udp2rawPassword || '';
  } catch (e) {
    console.error("Failed to load bypass status:", e);
  }
}

async function saveBypassSettings(e) {
  e.preventDefault();
  const wstunnelEnabled = document.getElementById('cfg-ws-enabled').checked;
  const wstunnelPort = parseInt(document.getElementById('cfg-ws-port').value, 10) || 8443;
  const wstunnelPrefix = document.getElementById('cfg-ws-prefix').value.trim() || 'stabirush';
  const udp2rawEnabled = document.getElementById('cfg-u2r-enabled').checked;
  const udp2rawPort = parseInt(document.getElementById('cfg-u2r-port').value, 10) || 4096;
  const udp2rawPassword = document.getElementById('cfg-u2r-pass').value.trim() || 'stabirush-raw-secret';

  try {
    const res = await fetch('/api/bypass', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'save',
        wstunnelEnabled,
        wstunnelPort,
        wstunnelPrefix,
        udp2rawEnabled,
        udp2rawPort,
        udp2rawPassword,
      })
    });
    if (!res.ok) throw new Error('Failed to save TCP bypass settings');
    await loadBypassStatus();
    await fetchStatus();
    updateStats();
    showToast('TCP Bypass (wstunnel & udp2raw) updated and restarted!', 'success');
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

// =========================================================
// NAMED SUBNETS MANAGEMENT & PER-SSID SUBNET SPLIT TUNNEL
// =========================================================

function renderNamedSubnetsPills() {
  const container = document.getElementById('named-subnets-pills');
  if (!container) return;
  const list = state.namedSubnets || [];
  if (list.length === 0) {
    container.innerHTML = `<span style="color:var(--text-muted);font-size:0.82rem;">Belum ada Named Subnet terdaftar.</span>`;
    return;
  }

  container.innerHTML = list.map(ns => `
    <div style="display:inline-flex; align-items:center; gap:0.45rem; background:var(--bg-surface-secondary); border:1px solid var(--border-color); border-radius:var(--radius-md); padding:0.35rem 0.65rem; font-size:0.8rem;">
      <strong style="color:var(--text-primary);">${escapeHtml(ns.name)}</strong>
      <code style="color:var(--accent-primary); font-family:var(--font-mono); font-size:0.78rem;">${escapeHtml(ns.cidr)}</code>
      ${ns.description ? `<span style="color:var(--text-muted); font-size:0.75rem;">(${escapeHtml(ns.description)})</span>` : ''}
      <button type="button" class="btn-copy" onclick="prefillNamedSubnet('${escapeHtml(ns.name)}', '${escapeHtml(ns.cidr)}', '${escapeHtml(ns.description || '')}')" title="Edit Nama Subnet">✎</button>
      <button type="button" class="btn-copy" style="color:var(--danger);" onclick="deleteNamedSubnet('${escapeHtml(ns.cidr)}')" title="Hapus Subnet">✕</button>
    </div>
  `).join('');
}

function prefillNamedSubnet(name, cidr, desc) {
  const n1 = document.getElementById('named-sub-name');
  const c1 = document.getElementById('named-sub-cidr');
  const d1 = document.getElementById('named-sub-desc');
  if (n1 && c1) {
    n1.value = name;
    c1.value = cidr;
    if (d1) d1.value = desc || '';
    n1.focus();
  }
  const n2 = document.getElementById('tab-sub-name');
  const c2 = document.getElementById('tab-sub-cidr');
  const d2 = document.getElementById('tab-sub-desc');
  if (n2 && c2) {
    n2.value = name;
    c2.value = cidr;
    if (d2) d2.value = desc || '';
  }
}

async function addOrUpdateNamedSubnet(e) {
  e.preventDefault();
  const name = document.getElementById('named-sub-name').value.trim();
  const cidr = document.getElementById('named-sub-cidr').value.trim();
  const description = document.getElementById('named-sub-desc').value.trim();
  await submitUpsertNamedSubnet(name, cidr, description);
  document.getElementById('named-sub-name').value = '';
  document.getElementById('named-sub-cidr').value = '';
  document.getElementById('named-sub-desc').value = '';
}

async function addOrUpdateNamedSubnetFromTab(e) {
  e.preventDefault();
  const name = document.getElementById('tab-sub-name').value.trim();
  const cidr = document.getElementById('tab-sub-cidr').value.trim();
  const description = document.getElementById('tab-sub-desc').value.trim();
  await submitUpsertNamedSubnet(name, cidr, description);
  document.getElementById('tab-sub-name').value = '';
  document.getElementById('tab-sub-cidr').value = '';
  document.getElementById('tab-sub-desc').value = '';
}

async function submitUpsertNamedSubnet(name, cidr, description) {
  try {
    const res = await fetch('/api/named-subnets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'upsert',
        subnet: { name, cidr, description }
      })
    });
    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.error || 'Gagal menyimpan Named Subnet');
    }
    state.namedSubnets = await res.json();
    renderNamedSubnetsPills();
    renderWifiRulesTable();
    loadSubnets();
    showToast(`Subnet "${name}" (${cidr}) disimpan & disinkronkan!`, 'success');
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

async function deleteNamedSubnet(cidr) {
  if (!confirm(`Hapus Named Subnet "${cidr}" dari daftar?`)) return;
  try {
    const res = await fetch(`/api/named-subnets?cidr=${encodeURIComponent(cidr)}`, {
      method: 'DELETE'
    });
    if (!res.ok) throw new Error('Gagal menghapus subnet');
    state.namedSubnets = await res.json();
    renderNamedSubnetsPills();
    renderWifiRulesTable();
    loadSubnets();
    showToast(`Subnet ${cidr} dihapus.`, 'info');
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

function populateCustomClientDeviceSelect() {
  const sel = document.getElementById('custom-client-device');
  if (!sel) return;
  const currentVal = sel.value;
  sel.innerHTML = (state.devices || []).map(d =>
    `<option value="${escapeHtml(d.publicKey)}">${escapeHtml(d.name)} (${escapeHtml(d.allocatedIP)})</option>`
  ).join('');
  if (currentVal && (state.devices || []).some(d => d.publicKey === currentVal)) {
    sel.value = currentVal;
  }
  loadDeviceWifiRulesUI();
}

function loadDeviceWifiRulesUI() {
  const sel = document.getElementById('custom-client-device');
  if (!sel) return;
  const pubKey = sel.value;
  const dev = (state.devices || []).find(d => d.publicKey === pubKey);
  if (!dev) return;

  state.editingWifiRules = (dev.wifiRules && dev.wifiRules.length > 0)
    ? JSON.parse(JSON.stringify(dev.wifiRules))
    : [
        { ssid: 'WiFi_Kantor_A', profileMode: 'split', subnets: ['192.168.101.0/24'] },
        { ssid: 'Home_WiFi, Kantor_Pusat', profileMode: 'split', subnets: [] },
        { ssid: 'Cafe*, Airport*, Public*, Hotel*', profileMode: 'secure', subnets: [] },
        { ssid: 'Restricted*, Campus*, Firewall*', profileMode: 'bypass_wstunnel', subnets: [] },
        { ssid: '*', profileMode: 'secure', subnets: [] }
      ];

  renderWifiRulesTable();
}

function renderWifiRulesTable() {
  const tbody = document.getElementById('wifi-rules-tbody');
  if (!tbody) return;

  const namedList = (state.namedSubnets && state.namedSubnets.length > 0)
    ? state.namedSubnets
    : [
        { name: 'Stabirush Mesh (wg0)', cidr: '10.10.0.0/24' },
        { name: 'Subnet A - LAN Kantor', cidr: '192.168.101.0/24' },
        { name: 'Subnet B - Core Server & DNS', cidr: '10.0.0.0/24' }
      ];

  tbody.innerHTML = state.editingWifiRules.map((r, idx) => {
    const selectedSubs = Array.isArray(r.subnets) ? r.subnets : [];
    const isAllSubnets = selectedSubs.length === 0 || selectedSubs.includes('*');

    let subnetColHtml = '';
    if (r.profileMode === 'secure') {
      subnetColHtml = `<span class="badge-tag" style="background:var(--status-connected-bg);color:var(--status-connected);border:1px solid var(--status-connected-border);">🔒 Full Tunnel 0.0.0.0/0 (Semua Trafik)</span>`;
    } else if (r.profileMode === 'disconnect') {
      subnetColHtml = `<span style="color:var(--text-muted);font-size:0.8rem;">○ Direct LAN (VPN Nonaktif)</span>`;
    } else {
      // Split Tunnel or TCP Bypass mode -> allow choosing "Semua Subnet" or specific Named Subnets!
      const checkboxes = namedList.map(ns => {
        const checked = !isAllSubnets && selectedSubs.includes(ns.cidr);
        return `
          <label style="display:inline-flex; align-items:center; gap:0.35rem; background:${checked ? 'rgba(56,189,248,0.15)' : 'var(--bg-surface-secondary)'}; border:1px solid ${checked ? 'var(--accent-primary)' : 'var(--border-color)'}; padding:0.22rem 0.5rem; border-radius:6px; font-size:0.76rem; cursor:pointer; user-select:none;">
            <input type="checkbox" ${checked ? 'checked' : ''} onchange="toggleRuleSubnet(${idx}, '${escapeHtml(ns.cidr)}', this.checked)">
            <span><strong>${escapeHtml(ns.name)}</strong> <code style="font-size:0.72rem;">(${escapeHtml(ns.cidr)})</code></span>
          </label>
        `;
      }).join('');

      subnetColHtml = `
        <div style="display:flex; flex-direction:column; gap:0.35rem;">
          <div style="display:flex; align-items:center; gap:0.5rem; flex-wrap:wrap;">
            <label style="display:inline-flex; align-items:center; gap:0.35rem; font-size:0.78rem; font-weight:600; color:${isAllSubnets ? 'var(--status-connected)' : 'var(--text-secondary)'}; cursor:pointer;">
              <input type="checkbox" ${isAllSubnets ? 'checked' : ''} onchange="clearRuleSubnets(${idx}, this.checked)">
              Semua Subnet Private
            </label>
            <span style="color:var(--text-muted);font-size:0.73rem;">atau pilih spesifik:</span>
          </div>
          <div style="display:flex; flex-wrap:wrap; gap:0.35rem;">
            ${checkboxes}
          </div>
        </div>
      `;
    }

    return `
      <tr>
        <td style="vertical-align:top;">
          <input type="text" class="form-input" value="${escapeHtml(r.ssid)}" oninput="updateWifiRule(${idx}, 'ssid', this.value)" placeholder="e.g. WiFi_SSID_A, Home_WiFi, *">
        </td>
        <td style="vertical-align:top;">
          <select class="form-select" onchange="updateWifiRule(${idx}, 'profileMode', this.value)">
            <option value="split" ${r.profileMode === 'split' ? 'selected' : ''}>Split Tunnel ( Berdasarkan Subnet )</option>
            <option value="secure" ${r.profileMode === 'secure' ? 'selected' : ''}>🔒 Secure Mode (Full Tunnel 0.0.0.0/0)</option>
            <option value="bypass_wstunnel" ${r.profileMode === 'bypass_wstunnel' ? 'selected' : ''}>⚡ TCP Bypass: wstunnel (:8443)</option>
            <option value="bypass_udp2raw" ${r.profileMode === 'bypass_udp2raw' ? 'selected' : ''}>⚡ TCP Bypass: udp2raw (:4096)</option>
            <option value="disconnect" ${r.profileMode === 'disconnect' ? 'selected' : ''}>○ Disconnect (Trusted Local LAN)</option>
          </select>
        </td>
        <td style="vertical-align:top;">
          ${subnetColHtml}
        </td>
        <td style="text-align:right; vertical-align:top;">
          <button type="button" class="btn-secondary btn-sm" style="color:var(--danger);" onclick="removeWifiRuleRow(${idx})">Hapus</button>
        </td>
      </tr>
    `;
  }).join('');
}

function updateWifiRule(idx, field, val) {
  if (state.editingWifiRules[idx]) {
    state.editingWifiRules[idx][field] = val;
    if (field === 'profileMode') {
      renderWifiRulesTable();
    }
  }
}

function toggleRuleSubnet(idx, cidr, isChecked) {
  const rule = state.editingWifiRules[idx];
  if (!rule) return;
  if (!Array.isArray(rule.subnets)) {
    rule.subnets = [];
  }
  rule.subnets = rule.subnets.filter(s => s !== '*' && s !== cidr);
  if (isChecked) {
    rule.subnets.push(cidr);
  }
  renderWifiRulesTable();
}

function clearRuleSubnets(idx, checkAll) {
  const rule = state.editingWifiRules[idx];
  if (!rule) return;
  if (checkAll) {
    rule.subnets = [];
  } else if (state.namedSubnets && state.namedSubnets.length > 0) {
    rule.subnets = [state.namedSubnets[0].cidr];
  }
  renderWifiRulesTable();
}

function addWifiRuleRow() {
  const defaultSub = (state.namedSubnets && state.namedSubnets.length > 1)
    ? [state.namedSubnets[1].cidr]
    : ['192.168.101.0/24'];
  state.editingWifiRules.push({
    ssid: 'WiFi_SSID_Baru',
    profileMode: 'split',
    subnets: defaultSub
  });
  renderWifiRulesTable();
}

function removeWifiRuleRow(idx) {
  state.editingWifiRules.splice(idx, 1);
  renderWifiRulesTable();
}

async function saveDeviceWifiRules() {
  const sel = document.getElementById('custom-client-device');
  if (!sel || !sel.value) return;
  const pubKey = sel.value;
  const dev = (state.devices || []).find(d => d.publicKey === pubKey);
  if (!dev) return;

  try {
    const res = await fetch(`/api/device?key=${encodeURIComponent(pubKey)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        publicKey: pubKey,
        name: dev.name,
        os: dev.os,
        allowedIPs: dev.allowedIPs,
        enabled: dev.enabled !== false,
        routingMode: dev.routingMode || 'split',
        transportMode: dev.transportMode || 'udp',
        wifiRules: state.editingWifiRules,
      })
    });
    if (!res.ok) throw new Error('Failed to save Wi-Fi SSID rules');
    const data = await res.json();
    const badge = document.getElementById('sync-version-badge');
    if (badge && data.configVersion) {
      badge.textContent = `☁️ Synced v${data.configVersion}`;
    }
    await fetchDevices();
    loadDeviceWifiRulesUI();
    showToast(`Aturan Wi-Fi SSID & Subnet untuk ${dev.name} disimpan & disinkronkan ke Android!`, 'success');
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

async function downloadCustomClient(platform) {
  const sel = document.getElementById('custom-client-device');
  if (!sel || !sel.value) {
    showToast('Pilih machine terlebih dahulu', 'error');
    return;
  }
  await saveDeviceWifiRules();
  window.location.href = `/api/device/custom-client?key=${encodeURIComponent(sel.value)}&platform=${encodeURIComponent(platform)}`;
}

// Subnets Tab
async function loadSubnets() {
  try {
    const res = await fetch('/api/subnets');
    if (!res.ok) return;
    const subnets = await res.json();
    const tbody = document.getElementById('subnets-table-body');
    if (!tbody) return;

    if (subnets.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:2rem; color:var(--text-muted);">No subnets configured yet.</td></tr>`;
      return;
    }

    tbody.innerHTML = subnets.map(s => `
      <tr>
        <td>
          <div style="font-weight:600; color:var(--text-primary);">${escapeHtml(s.subnetName || ('Subnet ' + s.subnet))}</div>
          ${s.description ? `<div style="font-size:0.78rem; color:var(--text-muted);">${escapeHtml(s.description)}</div>` : ''}
        </td>
        <td><strong style="font-family:var(--font-mono); color:var(--accent-primary);">${escapeHtml(s.subnet)}</strong></td>
        <td>${escapeHtml(s.deviceName)}</td>
        <td><span style="font-family:var(--font-mono);">${escapeHtml(s.deviceIP)}</span></td>
        <td>
          <span class="badge-tag ${s.active ? 'badge-subnet' : ''}">
            ${s.active ? '● Active Route' : '○ Standby'}
          </span>
        </td>
        <td style="text-align:right;">
          <div style="display:inline-flex; gap:0.35rem;">
            <button class="btn-secondary btn-sm" onclick="prefillNamedSubnet('${escapeHtml(s.subnetName || '')}', '${escapeHtml(s.subnet)}', '${escapeHtml(s.description || '')}')">Edit Nama</button>
            ${s.publicKey ? `<button class="btn-secondary btn-sm" onclick="openEditModal('${escapeHtml(s.publicKey)}')">Peer</button>` : `<button class="btn-secondary btn-sm" style="color:var(--danger);" onclick="deleteNamedSubnet('${escapeHtml(s.subnet)}')">Hapus</button>`}
          </div>
        </td>
      </tr>
    `).join('');
  } catch (e) {
    console.error("Failed to load subnets:", e);
  }
}

// DNS & Firewall Tab
async function loadDNSInfo() {
  try {
    const res = await fetch('/api/config/raw');
    if (!res.ok) return;
    const data = await res.json();
    const text = data.content || '';

    const postUpMatch = text.match(/PostUp\s*=\s*(.*)/i);
    const postDownMatch = text.match(/PostDown\s*=\s*(.*)/i);

    document.getElementById('rules-post-up').textContent = postUpMatch ? postUpMatch[1] : 'None configured';
    document.getElementById('rules-post-down').textContent = postDownMatch ? postDownMatch[1] : 'None configured';
  } catch (e) {
    console.error("Failed to load raw config for rules:", e);
  }
}

// Raw Config Tab
async function loadRawConfig() {
  try {
    const res = await fetch('/api/config/raw');
    if (!res.ok) return;
    const data = await res.json();
    document.getElementById('raw-config-editor').value = data.content || '';
  } catch (e) {
    showToast(`Failed to load config: ${e.message}`, 'error');
  }
}

async function saveRawConfig() {
  const content = document.getElementById('raw-config-editor').value;
  if (!confirm('Saving raw config will update /etc/wireguard/wg0.conf. Proceed?')) return;

  try {
    const res = await fetch('/api/config/raw', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    });
    if (!res.ok) throw new Error('Failed to save config');

    showToast('WireGuard config saved and applied successfully!', 'success');
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

// Settings Tab
async function loadSettings() {
  try {
    const res = await fetch('/api/settings');
    if (!res.ok) return;
    const cfg = await res.json();

    document.getElementById('setting-endpoint').value = cfg.serverEndpoint || '';
    document.getElementById('setting-tailnet').value = cfg.tailnetName || '';
    document.getElementById('setting-dns').value = cfg.defaultDns || '';
    document.getElementById('setting-mtu').value = cfg.defaultMtu || '';
    document.getElementById('setting-keepalive').value = cfg.defaultKeepalive || 25;
    document.getElementById('setting-auth-user').value = cfg.authUsername || '';

    const tailnetNav = document.getElementById('nav-tailnet-name');
    if (tailnetNav && cfg.tailnetName) {
      tailnetNav.innerHTML = `<span style="color:#10b981;">●</span> ${escapeHtml(cfg.tailnetName)}`;
    }
  } catch (e) {
    console.error("Failed to load settings:", e);
  }
}

async function saveSettings(e) {
  e.preventDefault();
  const endpoint = document.getElementById('setting-endpoint').value.trim();
  const tailnet = document.getElementById('setting-tailnet').value.trim();
  const dns = document.getElementById('setting-dns').value.trim();
  const mtu = document.getElementById('setting-mtu').value.trim();
  const keepalive = parseInt(document.getElementById('setting-keepalive').value, 10);
  const authUser = document.getElementById('setting-auth-user').value.trim();
  const authPass = document.getElementById('setting-auth-pass').value;

  try {
    const res = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        serverEndpoint: endpoint,
        tailnetName: tailnet,
        defaultDns: dns,
        defaultMtu: mtu,
        defaultKeepalive: keepalive,
        authUsername: authUser,
        authPassword: authPass,
      })
    });
    if (!res.ok) throw new Error('Failed to update settings');

    showToast('Stabirush settings saved successfully!', 'success');
    loadSettings();
    refreshAll();
  } catch (err) {
    showToast(`Error: ${err.message}`, 'error');
  }
}

// Modal Helpers
function openModal(id) {
  const m = document.getElementById(id);
  if (m) m.classList.add('open');
}

function closeModal(id) {
  const m = document.getElementById(id);
  if (m) m.classList.remove('open');
}

function closeAllModals() {
  document.querySelectorAll('.modal-backdrop').forEach(m => m.classList.remove('open'));
}

// Utility Helpers
function copyText(text, msg) {
  navigator.clipboard.writeText(text).then(() => {
    showToast(msg || 'Copied to clipboard!', 'info');
  }).catch(() => {
    const t = document.createElement('textarea');
    t.value = text;
    document.body.appendChild(t);
    t.select();
    document.execCommand('copy');
    document.body.removeChild(t);
    showToast(msg || 'Copied to clipboard!', 'info');
  });
}

function downloadBlob(content, filename) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.innerHTML = `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
    <span>${escapeHtml(message)}</span>
  `;

  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    toast.style.transition = 'all 0.2s ease';
    setTimeout(() => toast.remove(), 200);
  }, 2600);
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

// =========================================================
// USER MANAGEMENT & ZERO TRUST ACCESS
// =========================================================
state.users = [];
state.tokens = [];

async function fetchUsers() {
  try {
    const res = await fetch('/api/users');
    if (!res.ok) return;
    state.users = await res.json();
    renderUsersTable();
    const badge = document.getElementById('tab-badge-users');
    if (badge) badge.textContent = state.users.length;
  } catch (e) {
    console.error("Failed to fetch users:", e);
  }
}

async function fetchUserTokens() {
  try {
    const res = await fetch('/api/users/tokens');
    if (!res.ok) return;
    state.tokens = await res.json();
    renderTokensTable();
  } catch (e) {
    console.error("Failed to fetch tokens:", e);
  }
}

function renderUsersTable() {
  const tbody = document.getElementById('users-table-body');
  if (!tbody) return;

  if (!state.users || state.users.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:2rem; color:var(--text-muted);">Belum ada user yang terdaftar. Klik "Tambah Pengguna Baru".</td></tr>`;
    return;
  }

  tbody.innerHTML = state.users.map(u => {
    const isMasterAdmin = u.username === 'admin';
    const roleBadge = u.role === 'admin' 
      ? `<span class="badge" style="background:#4338ca; color:#e0e7ff; font-weight:700;">Admin</span>`
      : `<span class="badge" style="background:#065f46; color:#a7f3d0; font-weight:600;">User</span>`;

    // Map IP to device name
    let mappedDevicesHtml = '';
    if (u.deviceIps && u.deviceIps.length > 0) {
      mappedDevicesHtml = u.deviceIps.map(ip => {
        const dev = Object.values(state.devices || {}).find(d => {
          const clean = (d.allowedIPs || '').split('/')[0].trim();
          return clean === ip;
        });
        const name = dev ? dev.name : 'Unknown Device';
        return `<span style="display:inline-flex; align-items:center; gap:0.25rem; background:var(--bg-surface-secondary); border:1px solid var(--border-color); padding:0.15rem 0.5rem; border-radius:4px; font-size:0.75rem; margin:0.15rem 0.2rem 0.15rem 0; font-family:monospace;">
          <span style="color:#38bdf8;">⚡ ${escapeHtml(ip)}</span>
          <span style="color:var(--text-muted); font-size:0.7rem;">(${escapeHtml(name)})</span>
        </span>`;
      }).join(' ');
    } else {
      mappedDevicesHtml = `<span style="color:var(--text-muted); font-size:0.8rem; font-style:italic;">Belum ada perangkat dipetakan</span>`;
    }

    return `
      <tr>
        <td>
          <div style="display:flex; align-items:center; gap:0.5rem;">
            <div style="width:28px; height:28px; border-radius:50%; background:var(--bg-surface-secondary); display:flex; align-items:center; justify-content:center; font-size:0.85rem; border:1px solid var(--border-color);">👤</div>
            <div>
              <div style="font-weight:600; color:var(--text-primary); font-size:0.9rem;">${escapeHtml(u.username)}</div>
              <div style="font-size:0.72rem; color:var(--text-muted); font-family:monospace;">${escapeHtml(u.id)}</div>
            </div>
          </div>
        </td>
        <td>${roleBadge}</td>
        <td>${mappedDevicesHtml}</td>
        <td style="color:var(--text-muted); font-size:0.8rem;">${escapeHtml(u.createdAt || '-')}</td>
        <td style="text-align:right;">
          <div style="display:inline-flex; gap:0.35rem;">
            <button class="btn-secondary btn-sm" onclick="openEditUserModal('${escapeHtml(u.username)}')">Edit &amp; Password</button>
            ${!isMasterAdmin ? `<button class="btn-secondary btn-sm" style="color:var(--danger); border-color:var(--danger-border);" onclick="quickDeleteUser('${escapeHtml(u.username)}')">Hapus</button>` : ''}
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

function renderTokensTable() {
  const tbody = document.getElementById('tokens-table-body');
  if (!tbody) return;

  if (!state.tokens || state.tokens.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:1.5rem; color:var(--text-muted);">Tidak ada sesi perangkat yang aktif.</td></tr>`;
    return;
  }

  tbody.innerHTML = state.tokens.map(t => {
    const tokenShort = t.token.length > 20 ? t.token.substring(0, 12) + '...' + t.token.substring(t.token.length - 6) : t.token;
    const lastUsed = t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString('id-ID') : '-';

    return `
      <tr>
        <td>
          <span style="font-weight:600; font-size:0.85rem;">📱 ${escapeHtml(t.deviceName || 'Android App')}</span>
        </td>
        <td><span style="color:#38bdf8; font-weight:600;">@${escapeHtml(t.username)}</span></td>
        <td><code style="background:var(--bg-surface-secondary); padding:0.15rem 0.4rem; border-radius:4px; color:#10b981; font-weight:600;">${escapeHtml(t.allocatedIp || '10.10.0.3')}</code></td>
        <td><code style="font-size:0.75rem; color:var(--text-muted);">${escapeHtml(tokenShort)}</code></td>
        <td style="font-size:0.8rem; color:var(--text-muted);">${escapeHtml(lastUsed)}</td>
        <td style="text-align:right;">
          <button class="btn-secondary btn-sm" style="color:var(--danger); border-color:var(--danger-border); font-size:0.75rem; padding:0.2rem 0.5rem;" onclick="revokeUserToken('${escapeHtml(t.token)}')">Putuskan Sesi</button>
        </td>
      </tr>
    `;
  }).join('');
}

function renderDeviceCheckboxes(containerId, selectedIps = []) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const devicesList = Object.values(state.devices || {});
  if (devicesList.length === 0) {
    container.innerHTML = `<div style="font-size:0.8rem; color:var(--text-muted);">Tidak ada perangkat WireGuard yang terdaftar.</div>`;
    return;
  }

  container.innerHTML = devicesList.map(d => {
    const ip = (d.allowedIPs || '').split('/')[0].trim();
    const isChecked = selectedIps.includes(ip) ? 'checked' : '';
    return `
      <label style="display:flex; align-items:center; gap:0.5rem; font-size:0.82rem; cursor:pointer; padding:0.25rem 0; border-bottom:1px solid rgba(255,255,255,0.05);">
        <input type="checkbox" name="${containerId}-dev" value="${escapeHtml(ip)}" ${isChecked} style="cursor:pointer;">
        <span style="font-weight:600;">${escapeHtml(d.name)}</span>
        <code style="color:#38bdf8; font-size:0.75rem;">${escapeHtml(ip)}</code>
      </label>
    `;
  }).join('');
}

function openAddUserModal() {
  document.getElementById('form-add-user').reset();
  renderDeviceCheckboxes('add-user-devices-checkboxes', []);
  openModal('modal-add-user');
}

async function submitAddUser(e) {
  e.preventDefault();
  const username = document.getElementById('add-user-username').value.trim();
  const password = document.getElementById('add-user-password').value.trim();
  const role = document.getElementById('add-user-role').value;

  const selectedIps = [];
  document.querySelectorAll('input[name="add-user-devices-checkboxes-dev"]:checked').forEach(cb => {
    selectedIps.push(cb.value);
  });

  try {
    const res = await fetch('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, role, deviceIps: selectedIps })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(err.error || 'Gagal menambahkan user', 'error');
      return;
    }
    showToast(`User @${username} berhasil dibuat!`, 'success');
    closeModal('modal-add-user');
    await fetchUsers();
  } catch (err) {
    showToast('Terjadi kesalahan jaringan: ' + err.message, 'error');
  }
}

function openEditUserModal(username) {
  const u = state.users.find(x => x.username.toLowerCase() === username.toLowerCase());
  if (!u) return;

  document.getElementById('edit-user-username').value = u.username;
  document.getElementById('edit-user-password').value = '';
  document.getElementById('edit-user-role').value = u.role || 'user';

  renderDeviceCheckboxes('edit-user-devices-checkboxes', u.deviceIps || []);
  openModal('modal-edit-user');
}

async function submitEditUser(e) {
  e.preventDefault();
  const username = document.getElementById('edit-user-username').value.trim();
  const password = document.getElementById('edit-user-password').value.trim();
  const role = document.getElementById('edit-user-role').value;

  const selectedIps = [];
  document.querySelectorAll('input[name="edit-user-devices-checkboxes-dev"]:checked').forEach(cb => {
    selectedIps.push(cb.value);
  });

  try {
    const res = await fetch('/api/users/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, role, deviceIps: selectedIps })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(err.error || 'Gagal mengubah user', 'error');
      return;
    }
    showToast(`User @${username} berhasil diperbarui!`, 'success');
    closeModal('modal-edit-user');
    await fetchUsers();
  } catch (err) {
    showToast('Terjadi kesalahan jaringan: ' + err.message, 'error');
  }
}

async function confirmDeleteUser() {
  const username = document.getElementById('edit-user-username').value.trim();
  if (!confirm(`Hapus pengguna @${username}? Semua sesi perangkat aktif user ini akan langsung diputuskan.`)) {
    return;
  }
  try {
    const res = await fetch('/api/users/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(err.error || 'Gagal menghapus user', 'error');
      return;
    }
    showToast(`User @${username} berhasil dihapus.`, 'info');
    closeModal('modal-edit-user');
    await fetchUsers();
    await fetchUserTokens();
  } catch (err) {
    showToast('Terjadi kesalahan jaringan: ' + err.message, 'error');
  }
}

async function quickDeleteUser(username) {
  if (!confirm(`Hapus pengguna @${username}? Semua sesi perangkat aktif user ini akan langsung diputuskan.`)) {
    return;
  }
  try {
    const res = await fetch('/api/users/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(err.error || 'Gagal menghapus user', 'error');
      return;
    }
    showToast(`User @${username} berhasil dihapus.`, 'info');
    await fetchUsers();
    await fetchUserTokens();
  } catch (err) {
    showToast('Terjadi kesalahan jaringan: ' + err.message, 'error');
  }
}

async function revokeUserToken(token) {
  if (!confirm('Putuskan sesi token perangkat ini?')) return;
  try {
    const res = await fetch('/api/users/tokens/revoke', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token })
    });
    if (!res.ok) {
      showToast('Gagal memutuskan sesi', 'error');
      return;
    }
    showToast('Sesi perangkat berhasil diputuskan.', 'info');
    await fetchUserTokens();
  } catch (err) {
    showToast('Terjadi kesalahan jaringan: ' + err.message, 'error');
  }
}
