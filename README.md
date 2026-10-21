# Tailscale-Style WireGuard Web UI Management

A high-performance, ultra-lightweight WireGuard web management console designed to replicate the clean, modern user experience of **Tailscale Admin Console** (`login.tailscale.com/admin/machines`).

Built entirely in **Go** using the standard library. It compiles into a single, self-contained static binary with **embedded frontend assets** (HTML, CSS, JS, SVG, and QR generator) — requiring zero external runtimes, zero NodeJS, and zero Python dependencies.

---

## ⚡ Highlights & Resource Efficiency

- **Memory Footprint**: Only **~1.7 MB – 5 MB RAM** (vs 60–150 MB for Node.js / Python alternatives).
- **CPU Usage**: Near **0.0%** when idle.
- **Single Static Binary**: All assets and UI dependencies are embedded via `//go:embed`.
- **Zero Disruptions**: Uses dynamic WireGuard kernel calls (`wg set` / sync) to add, edit, or remove peers on the fly without restarting or dropping existing tunnels.
- **Automatic Backups**: Creates timestamped backups of `/etc/wireguard/wg0.conf` before every modification.

---

## 🎨 Tailscale-Inspired UI Features

1. **Machines Console**:
   - Clean Tailscale design with Inter/system typography and signature 3x3 dot matrix branding.
   - Platform identification with official badges (Apple macOS/iOS, Windows, Linux, Android, Router, Browser).
   - Real-time status indicators (emerald pulsing green dot for active peers with handshakes < 3 mins, gray for idle/offline).
   - Monospace WireGuard IP addresses with instant 1-click clipboard copy.
   - Live telemetry: Sent & Received traffic counters (MiB / GiB) and relative handshake times refreshed seamlessly every 3 seconds.
   - Search by name, IP, or public key (with `/` keyboard shortcut) and filter pills (`All`, `Connected`, `Offline`, `Subnets`).

2. **Add Device Modal**:
   - Choose device platform (Apple, Windows, Linux, Android, Router).
   - Automatic calculation and suggestion of the next available IP in `10.10.0.0/24`.
   - Routing mode selection:
     - **Split Tunnel**: WireGuard and internal subnets only (`10.10.0.0/24`, `192.168.101.0/24`).
     - **Full Tunnel**: All Internet traffic redirected through VPN (`0.0.0.0/0, ::/0`).
   - In-browser high-contrast QR Code generation for instant mobile scanning (via WireGuard iOS & Android apps).
   - One-click copy and `.conf` file download.

3. **Tailscale Subnet Routers**:
   - Detects and highlights peers advertising subnets (e.g. `192.168.101.0/24`).
   - Dedicated "Subnets & Routes" tab detailing router nodes and active routing status.

4. **DNS & Firewall Inspection**:
   - Details `wg0` interface setup (`10.10.0.1/24`, UDP port 51820, MTU 1380).
   - Displays DNS integration (AdGuard Home redirection on `10.0.0.154:53`).
   - Displays live IPTables `PostUp` / `PostDown` rules and Kernel IP Forwarding status (`net.ipv4.ip_forward = 1`).

5. **Raw Config & Safety**:
   - View and edit `/etc/wireguard/wg0.conf` directly from the web browser.
   - Automatic backups stored in `/etc/wireguard/wg0.conf.bak_<timestamp>`.

6. **Customizable Settings**:
   - Server public endpoint (IP or DDNS domain name).
   - Default client DNS, MTU, Keepalive.
   - Optional HTTP Basic Authentication for web interface security.
   - Dark / Light mode toggle with persistent local preference.

---

## 🚀 Accessing the Web UI

- **From any connected WireGuard peer**:
  [http://10.10.0.1:51821](http://10.10.0.1:51821)
- **From server local network / public IP**:
  [http://168.110.193.226:51821](http://168.110.193.226:51821)

---

## 🛠️ System Service Management

The Web UI is configured as a systemd service:

```bash
# Check service status
systemctl status wireguard-ui

# Restart service
systemctl restart wireguard-ui

# View live service logs
journalctl -u wireguard-ui -f
```

## 📦 Directory Structure

```
/home/wireguard-ui/
├── main.go                     # Application entrypoint & static asset embed
├── wireguard-ui                # Compiled static standalone binary (~7MB)
├── internal/
│   ├── config/config.go        # Server settings and persistence
│   ├── wireguard/
│   │   ├── types.go            # Data structures
│   │   ├── parser.go           # wg0.conf parser and writer with backup
│   │   └── manager.go          # Runtime wg sync, keygen (ecdh), and metrics
│   └── web/
│       └── server.go           # REST API & static file HTTP handler
├── web/
│   └── static/
│       ├── index.html          # Tailscale-style single page app
│       ├── style.css           # Tailscale design system styles
│       ├── app.js              # Real-time state & modal management
│       └── qrcode.min.js       # Offline client-side QR generator
└── data/
    ├── settings.json           # App configuration settings
    └── devices.json            # Machine metadata & client profiles
```
