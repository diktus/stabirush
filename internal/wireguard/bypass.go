package wireguard

import (
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var (
	bypassMu    sync.Mutex
	wstunnelCmd *exec.Cmd
	udp2rawCmd  *exec.Cmd
)

// EnsureBypassServices starts or stops wstunnel and udp2raw daemons according to AppConfig
func (m *Manager) EnsureBypassServices() {
	bypassMu.Lock()
	defer bypassMu.Unlock()

	wsPort := m.cfg.WstunnelPort
	if wsPort <= 0 {
		wsPort = 8443
	}
	wsPrefix := m.cfg.WstunnelPrefix
	if wsPrefix == "" {
		wsPrefix = "stabirush"
	}

	udpPort := m.cfg.Udp2rawPort
	if udpPort <= 0 {
		udpPort = 4096
	}
	udpPass := m.cfg.Udp2rawPassword
	if udpPass == "" {
		udpPass = "stabirush-raw-secret"
	}

	// 1. Manage wstunnel
	if m.cfg.WstunnelEnabled {
		if !isProcessAlive(wstunnelCmd) {
			_ = exec.Command("pkill", "-f", "wstunnel server").Run()
			ensureTCPPortAllowed(wsPort)
			listenAddr := fmt.Sprintf("wss://0.0.0.0:%d", wsPort)
			cmd := exec.Command("/usr/local/bin/wstunnel", "server", listenAddr,
				"--restrict-to", "127.0.0.1:51820",
				"--restrict-http-upgrade-path-prefix", wsPrefix,
			)
			if err := cmd.Start(); err == nil {
				wstunnelCmd = cmd
				go func(c *exec.Cmd) {
					_ = c.Wait()
				}(cmd)
			}
		}
	} else {
		if isProcessAlive(wstunnelCmd) {
			_ = wstunnelCmd.Process.Kill()
			wstunnelCmd = nil
		}
		_ = exec.Command("pkill", "-f", "wstunnel server").Run()
	}

	// 2. Manage udp2raw
	if m.cfg.Udp2rawEnabled {
		if !isProcessAlive(udp2rawCmd) {
			_ = exec.Command("pkill", "-f", "udp2raw -s").Run()
			ensureTCPPortAllowed(udpPort)
			listenAddr := fmt.Sprintf("0.0.0.0:%d", udpPort)
			cmd := exec.Command("/usr/local/bin/udp2raw",
				"-s",
				"-l", listenAddr,
				"-r", "127.0.0.1:51820",
				"-k", udpPass,
				"--raw-mode", "faketcp",
				"-a",
			)
			if err := cmd.Start(); err == nil {
				udp2rawCmd = cmd
				go func(c *exec.Cmd) {
					_ = c.Wait()
				}(cmd)
			}
		}
	} else {
		if isProcessAlive(udp2rawCmd) {
			_ = udp2rawCmd.Process.Kill()
			udp2rawCmd = nil
		}
		_ = exec.Command("pkill", "-f", "udp2raw -s").Run()
	}
}

// RestartBypassServices forces a restart of wstunnel and udp2raw (e.g. after config update)
func (m *Manager) RestartBypassServices() {
	bypassMu.Lock()
	if isProcessAlive(wstunnelCmd) {
		_ = wstunnelCmd.Process.Kill()
		wstunnelCmd = nil
	}
	_ = exec.Command("pkill", "-f", "wstunnel server").Run()

	if isProcessAlive(udp2rawCmd) {
		_ = udp2rawCmd.Process.Kill()
		udp2rawCmd = nil
	}
	_ = exec.Command("pkill", "-f", "udp2raw -s").Run()
	bypassMu.Unlock()

	time.Sleep(150 * time.Millisecond)
	m.EnsureBypassServices()
}

func isProcessAlive(cmd *exec.Cmd) bool {
	if cmd == nil || cmd.Process == nil {
		return false
	}
	err := cmd.Process.Signal(syscall.Signal(0))
	return err == nil
}

func ensureTCPPortAllowed(port int) {
	pStr := strconv.Itoa(port)
	check := exec.Command("iptables", "-C", "INPUT", "-p", "tcp", "--dport", pStr, "-j", "ACCEPT")
	if err := check.Run(); err != nil {
		_ = exec.Command("iptables", "-I", "INPUT", "-p", "tcp", "--dport", pStr, "-j", "ACCEPT").Run()
	}
}

// GetBypassStatus returns current status of wstunnel & udp2raw daemons
func (m *Manager) GetBypassStatus() BypassStatus {
	bypassMu.Lock()
	defer bypassMu.Unlock()

	serverHost := m.getServerHostOnly()

	wsRunning := isProcessAlive(wstunnelCmd)
	wsPID := 0
	if wsRunning {
		wsPID = wstunnelCmd.Process.Pid
	}

	udpRunning := isProcessAlive(udp2rawCmd)
	udpPID := 0
	if udpRunning {
		udpPID = udp2rawCmd.Process.Pid
	}

	return BypassStatus{
		WstunnelEnabled: m.cfg.WstunnelEnabled,
		WstunnelRunning: wsRunning,
		WstunnelPID:     wsPID,
		WstunnelPort:    m.cfg.WstunnelPort,
		WstunnelPrefix:  m.cfg.WstunnelPrefix,
		WstunnelURL:     fmt.Sprintf("wss://%s:%d (prefix: /%s)", serverHost, m.cfg.WstunnelPort, m.cfg.WstunnelPrefix),
		Udp2rawEnabled:  m.cfg.Udp2rawEnabled,
		Udp2rawRunning:  udpRunning,
		Udp2rawPID:      udpPID,
		Udp2rawPort:     m.cfg.Udp2rawPort,
		Udp2rawPassword: m.cfg.Udp2rawPassword,
		Udp2rawEndpoint: fmt.Sprintf("%s:%d (faketcp)", serverHost, m.cfg.Udp2rawPort),
		TargetWG:        "127.0.0.1:51820 (wg0)",
	}
}

func (m *Manager) getServerHostOnly() string {
	ep := m.cfg.ServerEndpoint
	if h, _, err := net.SplitHostPort(ep); err == nil && h != "" {
		return h
	}
	return "168.110.193.226"
}

// DefaultWifiRules returns sensible default Wi-Fi SSID switching rules including per-SSID subnet filtering
func DefaultWifiRules() []WifiProfileRule {
	return []WifiProfileRule{
		{
			SSID:        "WiFi_Kantor_A",
			ProfileMode: "split",
			Subnets:     []string{"192.168.101.0/24"},
			SubnetNames: "Subnet A - LAN Kantor (MikroTik) (192.168.101.0/24)",
		},
		{
			SSID:        "Home_WiFi, Kantor_Pusat",
			ProfileMode: "split",
			Subnets:     []string{},
			SubnetNames: "Semua Subnet Private",
		},
		{
			SSID:        "Cafe*, Airport*, Public*, Hotel*",
			ProfileMode: "secure",
		},
		{
			SSID:        "Restricted*, Campus*, Firewall*",
			ProfileMode: "bypass_wstunnel",
		},
		{
			SSID:        "*",
			ProfileMode: "secure",
		},
	}
}

// GenerateCustomClientScript builds a custom WireGuard auto-switching & TCP-bypass client script
func (m *Manager) GenerateCustomClientScript(pubKey, platform string) (content string, filename string, err error) {
	splitUDP, err := m.GetDeviceConfigWithOptions(pubKey, "split", "udp")
	if err != nil {
		return "", "", err
	}
	secureUDP, err := m.GetDeviceConfigWithOptions(pubKey, "secure", "udp")
	if err != nil {
		return "", "", err
	}
	splitWS, err := m.GetDeviceConfigWithOptions(pubKey, "split", "wstunnel")
	if err != nil {
		return "", "", err
	}
	secureWS, err := m.GetDeviceConfigWithOptions(pubKey, "secure", "wstunnel")
	if err != nil {
		return "", "", err
	}
	splitU2R, err := m.GetDeviceConfigWithOptions(pubKey, "split", "udp2raw")
	if err != nil {
		return "", "", err
	}
	secureU2R, err := m.GetDeviceConfigWithOptions(pubKey, "secure", "udp2raw")
	if err != nil {
		return "", "", err
	}

	m.mu.Lock()
	meta := m.metadata[pubKey]
	var rules []WifiProfileRule
	devName := "peer"
	if meta != nil {
		rules = meta.WifiRules
		if meta.Name != "" {
			devName = meta.Name
		}
	}
	m.mu.Unlock()

	if len(rules) == 0 {
		rules = DefaultWifiRules()
	}

	serverHost := m.getServerHostOnly()
	wsPort := m.cfg.WstunnelPort
	wsPrefix := m.cfg.WstunnelPrefix
	u2rPort := m.cfg.Udp2rawPort
	u2rPass := m.cfg.Udp2rawPassword

	cleanName := strings.ToLower(strings.ReplaceAll(devName, " ", "-"))

	if platform == "ios" || platform == "mobileconfig" {
		mc, err := m.generateMobileConfig(pubKey, devName, rules, splitUDP, secureUDP)
		return mc, fmt.Sprintf("stabirush-%s.mobileconfig", cleanName), err
	}

	if platform == "windows" {
		ps1 := generateWindowsPowerShellClient(devName, serverHost, wsPort, wsPrefix, u2rPort, u2rPass, rules, splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R)
		return ps1, fmt.Sprintf("stabirush-client-%s.ps1", cleanName), nil
	}

	sh := generateUnixCustomClient(devName, serverHost, wsPort, wsPrefix, u2rPort, u2rPass, rules, splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R)
	return sh, fmt.Sprintf("stabirush-client-%s.sh", cleanName), nil
}

func generateUnixCustomClient(devName, serverHost string, wsPort int, wsPrefix string, u2rPort int, u2rPass string, rules []WifiProfileRule, splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R string) string {
	var rulesBash strings.Builder
	for _, r := range rules {
		ssids := strings.Split(r.SSID, ",")
		subComment := ""
		if len(r.Subnets) > 0 {
			subComment = " # Subnets: " + strings.Join(r.Subnets, ", ")
		}
		for _, s := range ssids {
			s = strings.TrimSpace(s)
			if s == "" {
				continue
			}
			rulesBash.WriteString(fmt.Sprintf("    %s) echo %q; return ;;%s\n", s, r.ProfileMode, subComment))
		}
	}

	return fmt.Sprintf(`#!/usr/bin/env bash
# ==============================================================================
# STABIRUSH CUSTOM WIREGUARD CLIENT (Auto Wi-Fi SSID Switcher & TCP Bypass)
# Device: %s
# Server: %s (WG UDP: 51820 | wstunnel TCP: %d | udp2raw TCP: %d)
# ==============================================================================
# Usage:
#   sudo ./stabirush-client.sh daemon            # Auto-switch profile by Wi-Fi SSID + Auto TCP Bypass if UDP blocked
#   sudo ./stabirush-client.sh up split          # Connect Split Tunnel (Private IPs only)
#   sudo ./stabirush-client.sh up secure         # Connect Secure Mode (Full Tunnel 0.0.0.0/0)
#   sudo ./stabirush-client.sh up wstunnel       # Connect via TCP WebSocket (wstunnel) -> wg0
#   sudo ./stabirush-client.sh up udp2raw        # Connect via FakeTCP (udp2raw) -> wg0
#   sudo ./stabirush-client.sh down              # Disconnect Stabirush VPN & TCP tunnels
#   sudo ./stabirush-client.sh status            # Show current connection, Wi-Fi SSID, and transport mode
# ==============================================================================

set -e

SERVER_IP=%q
WSTUNNEL_PORT=%d
WSTUNNEL_PREFIX=%q
UDP2RAW_PORT=%d
UDP2RAW_PASS=%q
WORK_DIR="/tmp/stabirush-wg"
IFACE_NAME="stabirush0"
ACTIVE_MODE=""

mkdir -p "$WORK_DIR"
chmod 700 "$WORK_DIR"

# Write pre-configured WireGuard profiles
cat << 'EOF_SPLIT_UDP' > "$WORK_DIR/split-udp.conf"
%sEOF_SPLIT_UDP

cat << 'EOF_SECURE_UDP' > "$WORK_DIR/secure-udp.conf"
%sEOF_SECURE_UDP

cat << 'EOF_SPLIT_WS' > "$WORK_DIR/split-wstunnel.conf"
%sEOF_SPLIT_WS

cat << 'EOF_SECURE_WS' > "$WORK_DIR/secure-wstunnel.conf"
%sEOF_SECURE_WS

cat << 'EOF_SPLIT_U2R' > "$WORK_DIR/split-udp2raw.conf"
%sEOF_SPLIT_U2R

cat << 'EOF_SECURE_U2R' > "$WORK_DIR/secure-udp2raw.conf"
%sEOF_SECURE_U2R

chmod 600 "$WORK_DIR"/*.conf

# Detect current Wi-Fi SSID across Linux & macOS
get_current_ssid() {
  local ssid=""
  if command -v nmcli >/dev/null 2>&1; then
    ssid=$(nmcli -t -f active,ssid dev wifi 2>/dev/null | grep '^yes:' | cut -d: -f2- | head -n1)
  fi
  if [ -z "$ssid" ] && command -v iwgetid >/dev/null 2>&1; then
    ssid=$(iwgetid -r 2>/dev/null || true)
  fi
  if [ -z "$ssid" ] && [ "$(uname -s)" = "Darwin" ]; then
    ssid=$(networksetup -getairportnetwork en0 2>/dev/null | sed 's/Current Wi-Fi Network: //' || true)
  fi
  echo "$ssid"
}

# Match SSID to configured Stabirush profile rule
match_ssid_rule() {
  local ssid="$1"
  case "$ssid" in
%s    *) echo "secure" ;;
  esac
}

# Check if UDP 51820 is blocked or unreachable (returns 0 if healthy, 1 if UDP blocked)
check_udp_health() {
  if ping -c 1 -W 2 10.10.0.1 >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

stop_tcp_helpers() {
  pkill -f "wstunnel client.*127.0.0.1:51820" 2>/dev/null || true
  pkill -f "udp2raw -c.*127.0.0.1:51820" 2>/dev/null || true
}

bring_down() {
  for conf in "$WORK_DIR"/*.conf; do
    wg-quick down "$conf" 2>/dev/null || true
  done
  stop_tcp_helpers
  ACTIVE_MODE=""
}

bring_up() {
  local target="$1"
  if [ "$ACTIVE_MODE" = "$target" ]; then
    return 0
  fi
  echo "[Stabirush] Switching profile -> $target"
  bring_down

  case "$target" in
    disconnect)
      echo "[Stabirush] Trusted Wi-Fi detected: VPN disconnected per SSID rule."
      ACTIVE_MODE="disconnect"
      ;;
    split)
      wg-quick up "$WORK_DIR/split-udp.conf"
      ACTIVE_MODE="split"
      ;;
    secure|full)
      wg-quick up "$WORK_DIR/secure-udp.conf"
      ACTIVE_MODE="secure"
      ;;
    wstunnel|bypass_wstunnel)
      wg-quick up "$WORK_DIR/split-wstunnel.conf"
      ACTIVE_MODE="bypass_wstunnel"
      ;;
    secure_wstunnel)
      wg-quick up "$WORK_DIR/secure-wstunnel.conf"
      ACTIVE_MODE="secure_wstunnel"
      ;;
    udp2raw|bypass_udp2raw)
      wg-quick up "$WORK_DIR/split-udp2raw.conf"
      ACTIVE_MODE="bypass_udp2raw"
      ;;
    secure_udp2raw)
      wg-quick up "$WORK_DIR/secure-udp2raw.conf"
      ACTIVE_MODE="secure_udp2raw"
      ;;
    *)
      wg-quick up "$WORK_DIR/split-udp.conf"
      ACTIVE_MODE="split"
      ;;
  esac
}

# Auto-failover from UDP to TCP (wstunnel / udp2raw) if UDP handshake is blocked
ensure_connectivity_or_bypass() {
  sleep 3
  if check_udp_health; then
    echo "[Stabirush] WireGuard tunnel healthy (10.10.0.1 reachable)."
    return 0
  fi

  echo "[Stabirush] WARNING: UDP 51820 appears blocked! Failing over to TCP Bypass..."
  if command -v wstunnel >/dev/null 2>&1; then
    if [ "$ACTIVE_MODE" = "secure" ]; then
      bring_up "secure_wstunnel"
    else
      bring_up "bypass_wstunnel"
    fi
  elif command -v udp2raw >/dev/null 2>&1; then
    if [ "$ACTIVE_MODE" = "secure" ]; then
      bring_up "secure_udp2raw"
    else
      bring_up "bypass_udp2raw"
    fi
  else
    echo "[Stabirush] Install 'wstunnel' or 'udp2raw' on client for automatic TCP encapsulation."
  fi
}

case "${1:-status}" in
  up)
    MODE="${2:-split}"
    bring_up "$MODE"
    if [ "$MODE" = "split" ] || [ "$MODE" = "secure" ]; then
      ensure_connectivity_or_bypass
    fi
    ;;
  down)
    bring_down
    echo "[Stabirush] Disconnected."
    ;;
  daemon|--auto)
    echo "[Stabirush] Starting Wi-Fi SSID Auto-Switcher & UDP->TCP Bypass Daemon..."
    trap 'bring_down; exit 0' INT TERM
    while true; do
      SSID=$(get_current_ssid)
      DESIRED=$(match_ssid_rule "$SSID")
      echo "[Stabirush] Current Wi-Fi SSID: '${SSID:-<Ethernet/Unknown>}' -> Rule: $DESIRED"
      if [ "$ACTIVE_MODE" != "$DESIRED" ] && [ "$ACTIVE_MODE" != "secure_wstunnel" ] && [ "$ACTIVE_MODE" != "bypass_wstunnel" ]; then
        bring_up "$DESIRED"
      fi
      if [ "$ACTIVE_MODE" = "split" ] || [ "$ACTIVE_MODE" = "secure" ]; then
        ensure_connectivity_or_bypass
      fi
      sleep 10
    done
    ;;
  status|*)
    echo "=== Stabirush Custom WireGuard Client ==="
    echo "Detected Wi-Fi SSID : $(get_current_ssid)"
    echo "Matched SSID Rule   : $(match_ssid_rule "$(get_current_ssid)")"
    wg show
    ;;
esac
`, devName, serverHost, wsPort, u2rPort, serverHost, wsPort, wsPrefix, u2rPort, u2rPass,
		splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R, rulesBash.String())
}

func generateWindowsPowerShellClient(devName, serverHost string, wsPort int, wsPrefix string, u2rPort int, u2rPass string, rules []WifiProfileRule, splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R string) string {
	var rulesPS strings.Builder
	for _, r := range rules {
		ssids := strings.Split(r.SSID, ",")
		for _, s := range ssids {
			s = strings.TrimSpace(s)
			if s == "" {
				continue
			}
			rulesPS.WriteString(fmt.Sprintf("    if ($ssid -like %q) { return %q }\n", s, r.ProfileMode))
		}
	}

	return fmt.Sprintf(`# ==============================================================================
# STABIRUSH CUSTOM WIREGUARD CLIENT FOR WINDOWS (Wi-Fi SSID Switcher & TCP Bypass)
# Device: %s | Server: %s
# Run in Administrator PowerShell:
#   .\stabirush-client.ps1 -Action daemon
#   .\stabirush-client.ps1 -Action up -Mode secure
#   .\stabirush-client.ps1 -Action up -Mode wstunnel
# ==============================================================================
param(
    [string]$Action = "daemon",
    [string]$Mode = "split"
)

$ServerIP = "%s"
$WstunnelPort = %d
$WstunnelPrefix = "%s"
$Udp2rawPort = %d
$Udp2rawPass = "%s"
$WorkDir = "$env:ProgramData\Stabirush"
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$Profiles = @{
    "split" = @'
%s
'@
    "secure" = @'
%s
'@
    "bypass_wstunnel" = @'
%s
'@
    "secure_wstunnel" = @'
%s
'@
    "bypass_udp2raw" = @'
%s
'@
    "secure_udp2raw" = @'
%s
'@
}

foreach ($k in $Profiles.Keys) {
    Set-Content -Path "$WorkDir\stabirush-$k.conf" -Value $Profiles[$k] -Encoding Ascii
}

function Get-CurrentSSID {
    $out = netsh wlan show interfaces | Select-String "^\s*SSID\s*:\s*(.+)$"
    if ($out) {
        return $out.Matches[0].Groups[1].Value.Trim()
    }
    return ""
}

function Get-ProfileForSSID([string]$ssid) {
%s    return "secure"
}

function Start-StabirushProfile([string]$targetMode) {
    Write-Host "[Stabirush] Activating profile: $targetMode" -ForegroundColor Cyan
    wireguard.exe /uninstalltunnelservice stabirush 2>$null
    Get-Process wstunnel, udp2raw -ErrorAction SilentlyContinue | Stop-Process -Force
    if ($targetMode -eq "disconnect") {
        return
    }
    if ($targetMode -like "*wstunnel*") {
        Start-Process -WindowStyle Hidden -FilePath "wstunnel.exe" -ArgumentList "client -L udp://127.0.0.1:51820:127.0.0.1:51820?timeout_sec=0 wss://${ServerIP}:${WstunnelPort} --http-upgrade-path-prefix ${WstunnelPrefix}"
        Start-Sleep -Seconds 1
    }
    Copy-Item "$WorkDir\stabirush-$targetMode.conf" "$WorkDir\stabirush.conf" -Force
    wireguard.exe /installtunnelservice "$WorkDir\stabirush.conf"
}

if ($Action -eq "up") {
    Start-StabirushProfile $Mode
    Start-Sleep -Seconds 4
    if (-not (Test-Connection -ComputerName 10.10.0.1 -Count 1 -Quiet)) {
        Write-Host "[Stabirush] UDP 51820 blocked! Switching to TCP (wstunnel)..." -ForegroundColor Yellow
        if ($Mode -eq "secure") { Start-StabirushProfile "secure_wstunnel" }
        else { Start-StabirushProfile "bypass_wstunnel" }
    }
} elseif ($Action -eq "down") {
    Start-StabirushProfile "disconnect"
} else {
    $active = ""
    while ($true) {
        $ssid = Get-CurrentSSID
        $desired = Get-ProfileForSSID $ssid
        if ($active -ne $desired) {
            Start-StabirushProfile $desired
            $active = $desired
        }
        Start-Sleep -Seconds 5
        if (($active -eq "split" -or $active -eq "secure") -and -not (Test-Connection -ComputerName 10.10.0.1 -Count 1 -Quiet)) {
            Write-Host "[Stabirush] UDP blocked on Wi-Fi '$ssid'! Failing over to TCP (wstunnel)..." -ForegroundColor Yellow
            if ($active -eq "secure") { Start-StabirushProfile "secure_wstunnel"; $active = "secure_wstunnel" }
            else { Start-StabirushProfile "bypass_wstunnel"; $active = "bypass_wstunnel" }
        }
        Start-Sleep -Seconds 10
    }
}
`, devName, serverHost, serverHost, wsPort, wsPrefix, u2rPort, u2rPass,
		splitUDP, secureUDP, splitWS, secureWS, splitU2R, secureU2R, rulesPS.String())
}

func (m *Manager) generateMobileConfig(pubKey, devName string, rules []WifiProfileRule, splitConf, secureConf string) (string, error) {
	var disconnectSSIDs, connectSSIDs []string
	for _, r := range rules {
		for _, s := range strings.Split(r.SSID, ",") {
			s = strings.TrimSpace(s)
			if s == "" || s == "*" || strings.Contains(s, "*") {
				continue
			}
			if r.ProfileMode == "disconnect" {
				disconnectSSIDs = append(disconnectSSIDs, fmt.Sprintf("              <string>%s</string>", s))
			} else {
				connectSSIDs = append(connectSSIDs, fmt.Sprintf("              <string>%s</string>", s))
			}
		}
	}

	var onDemandXML strings.Builder
	if len(disconnectSSIDs) > 0 {
		onDemandXML.WriteString(fmt.Sprintf(`          <dict>
            <key>Action</key>
            <string>Disconnect</string>
            <key>InterfaceTypeMatch</key>
            <string>WiFi</string>
            <key>SSIDMatch</key>
            <array>
%s
            </array>
          </dict>
`, strings.Join(disconnectSSIDs, "\n")))
	}
	if len(connectSSIDs) > 0 {
		onDemandXML.WriteString(fmt.Sprintf(`          <dict>
            <key>Action</key>
            <string>Connect</string>
            <key>InterfaceTypeMatch</key>
            <string>WiFi</string>
            <key>SSIDMatch</key>
            <array>
%s
            </array>
          </dict>
`, strings.Join(connectSSIDs, "\n")))
	}
	onDemandXML.WriteString(`          <dict>
            <key>Action</key>
            <string>Connect</string>
          </dict>`)

	xml := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadDescription</key>
      <string>Stabirush WireGuard Profile with Wi-Fi SSID Auto-Switch</string>
      <key>PayloadDisplayName</key>
      <string>Stabirush VPN (%s)</string>
      <key>PayloadIdentifier</key>
      <string>network.stabirush.vpn.profile</string>
      <key>PayloadType</key>
      <string>com.apple.vpn.managed</string>
      <key>PayloadUUID</key>
      <string>8A7E9B22-4C11-4E55-9A11-55B128400001</string>
      <key>PayloadVersion</key>
      <integer>1</integer>
      <key>UserDefinedName</key>
      <string>Stabirush · %s</string>
      <key>VPNType</key>
      <string>VPN</string>
      <key>VPNSubType</key>
      <string>com.wireguard.ios</string>
      <key>VPN</key>
      <dict>
        <key>AuthenticationMethod</key>
        <string>Password</string>
        <key>RemoteAddress</key>
        <string>%s</string>
        <key>OnDemandEnabled</key>
        <integer>1</integer>
        <key>OnDemandRules</key>
        <array>
%s
        </array>
      </dict>
      <key>VendorConfig</key>
      <dict>
        <key>WgQuickConfig</key>
        <string>%s</string>
      </dict>
    </dict>
  </array>
  <key>PayloadDisplayName</key>
  <string>Stabirush Auto-SSID WireGuard (%s)</string>
  <key>PayloadIdentifier</key>
  <string>network.stabirush.vpn</string>
  <key>PayloadType</key>
  <string>Configuration</string>
  <key>PayloadUUID</key>
  <string>8A7E9B22-4C11-4E55-9A11-55B128400000</string>
  <key>PayloadVersion</key>
  <integer>1</integer>
</dict>
</plist>
`, devName, devName, m.cfg.ServerEndpoint, onDemandXML.String(), secureConf, devName)

	return xml, nil
}
