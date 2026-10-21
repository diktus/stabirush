package wireguard

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"wireguard-ui/internal/config"
)

// TunnelStatsFunc defines callback for retrieving active TCP/UDP tunnel session statistics
type TunnelStatsFunc func(ip string) (endpoint string, rx int64, tx int64, lastSeen time.Time, isConnected bool, protocol string)

// Manager manages WireGuard state, persistence, and synchronization
type Manager struct {
	mu               sync.Mutex
	cfg              *config.AppConfig
	metadataFile     string
	namedSubnetsFile string
	metadata         map[string]*PeerMetadata // keyed by public key
	namedSubnets     []NamedSubnet
	configVersion    int64
	startTime        time.Time
	tunnelStatsFn    TunnelStatsFunc
}

// PeerMetadata stores supplementary data not part of native wg conf
type PeerMetadata struct {
	Name          string            `json:"name"`
	OS            string            `json:"os"`
	PrivateKey    string            `json:"privateKey,omitempty"`
	PresharedKey  string            `json:"presharedKey,omitempty"`
	Enabled       bool              `json:"enabled"`
	CreatedAt     string            `json:"createdAt"`
	SubnetRoutes  []string          `json:"subnetRoutes,omitempty"`
	RoutingMode   string            `json:"routingMode,omitempty"`   // "split" or "secure"
	TransportMode string            `json:"transportMode,omitempty"` // "udp", "wstunnel", "udp2raw", "auto"
	SplitSubnets  []string          `json:"splitSubnets,omitempty"`
	WifiRules     []WifiProfileRule `json:"wifiRules,omitempty"`
}

var globalManager *Manager

// DefaultNamedSubnets returns the initial named subnets for Stabirush
func DefaultNamedSubnets() []NamedSubnet {
	return []NamedSubnet{
		{
			ID:          "sub-stabirush-mesh",
			Name:        "Stabirush Mesh (wg0)",
			CIDR:        "10.10.0.0/24",
			Description: "Jaringan utama VPN WireGuard Stabirush",
		},
		{
			ID:          "sub-lan-kantor",
			Name:        "Subnet A - LAN Kantor (MikroTik)",
			CIDR:        "192.168.101.0/24",
			Description: "Jaringan lokal kantor melalui router 10.10.0.2",
		},
		{
			ID:          "sub-core-dns",
			Name:        "Subnet B - Core Server & AdGuard",
			CIDR:        "10.0.0.0/24",
			Description: "Server internal & AdGuard Home DNS (10.0.0.154)",
		},
	}
}

// InitManager initializes the Stabirush WireGuard manager
func InitManager(cfg *config.AppConfig) (*Manager, error) {
	m := &Manager{
		cfg:              cfg,
		metadataFile:     filepath.Join(cfg.DataDir, "devices.json"),
		namedSubnetsFile: filepath.Join(cfg.DataDir, "named_subnets.json"),
		metadata:         make(map[string]*PeerMetadata),
		namedSubnets:     DefaultNamedSubnets(),
		configVersion:    time.Now().Unix(),
		startTime:        time.Now(),
	}

	_ = m.loadMetadata()
	_ = m.loadNamedSubnets()
	m.EnsureBypassServices()
	globalManager = m
	return m, nil
}

// GetManager returns the active manager
func GetManager() *Manager {
	return globalManager
}

// SetTunnelStatsFunc registers a callback to query active TCP/UDP tunnel session metrics
func (m *Manager) SetTunnelStatsFunc(fn TunnelStatsFunc) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tunnelStatsFn = fn
}

// GetConfigVersion returns the latest configuration revision timestamp
func (m *Manager) GetConfigVersion() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.configVersion
}

// BumpConfigVersion increments/updates the sync revision timestamp
func (m *Manager) BumpConfigVersion() {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().Unix()
	if now <= m.configVersion {
		m.configVersion++
	} else {
		m.configVersion = now
	}
}

func (m *Manager) loadNamedSubnets() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	data, err := os.ReadFile(m.namedSubnetsFile)
	if err != nil {
		// Save defaults
		raw, _ := json.MarshalIndent(m.namedSubnets, "", "  ")
		_ = os.WriteFile(m.namedSubnetsFile, raw, 0644)
		return nil
	}
	var list []NamedSubnet
	if err := json.Unmarshal(data, &list); err == nil && len(list) > 0 {
		m.namedSubnets = list
	}
	return nil
}

func (m *Manager) saveNamedSubnetsLocked() error {
	now := time.Now().Unix()
	if now <= m.configVersion {
		m.configVersion++
	} else {
		m.configVersion = now
	}
	data, err := json.MarshalIndent(m.namedSubnets, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(m.namedSubnetsFile, data, 0644)
}

// GetNamedSubnets returns all configured named subnets (and auto-registers any new peer advertised subnet)
func (m *Manager) GetNamedSubnets() []NamedSubnet {
	m.mu.Lock()
	defer m.mu.Unlock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	changed := false
	if err == nil {
		for _, p := range cf.Peers {
			_, subs := parseAllowedIPs(p.AllowedIPs)
			for _, sub := range subs {
				found := false
				for _, ns := range m.namedSubnets {
					if ns.CIDR == sub {
						found = true
						break
					}
				}
				if !found {
					pName := p.Name
					if meta, ok := m.metadata[p.PublicKey]; ok && meta.Name != "" {
						pName = meta.Name
					}
					if pName == "" {
						pName = "Router"
					}
					m.namedSubnets = append(m.namedSubnets, NamedSubnet{
						ID:          fmt.Sprintf("sub-%d", len(m.namedSubnets)+1),
						Name:        fmt.Sprintf("Subnet %s (%s)", sub, pName),
						CIDR:        sub,
						Description: fmt.Sprintf("Advertised by %s", pName),
					})
					changed = true
				}
			}
		}
	}
	if changed {
		_ = m.saveNamedSubnetsLocked()
	}

	out := make([]NamedSubnet, len(m.namedSubnets))
	copy(out, m.namedSubnets)
	return out
}

// SaveNamedSubnets replaces the list of named subnets
func (m *Manager) SaveNamedSubnets(list []NamedSubnet) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	cleaned := make([]NamedSubnet, 0, len(list))
	for i, item := range list {
		cidr := strings.TrimSpace(item.CIDR)
		name := strings.TrimSpace(item.Name)
		if cidr == "" {
			continue
		}
		if !strings.Contains(cidr, "/") {
			cidr += "/24"
		}
		if name == "" {
			name = fmt.Sprintf("Subnet %d (%s)", i+1, cidr)
		}
		id := strings.TrimSpace(item.ID)
		if id == "" {
			id = fmt.Sprintf("sub-%d-%d", time.Now().Unix(), i+1)
		}
		cleaned = append(cleaned, NamedSubnet{
			ID:          id,
			Name:        name,
			CIDR:        cidr,
			Description: strings.TrimSpace(item.Description),
		})
	}
	if len(cleaned) == 0 {
		cleaned = DefaultNamedSubnets()
	}
	m.namedSubnets = cleaned
	return m.saveNamedSubnetsLocked()
}

// UpsertNamedSubnet adds or updates a single named subnet by CIDR or ID
func (m *Manager) UpsertNamedSubnet(sub NamedSubnet) ([]NamedSubnet, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	cidr := strings.TrimSpace(sub.CIDR)
	if cidr == "" {
		return nil, fmt.Errorf("subnet CIDR wajib diisi")
	}
	if !strings.Contains(cidr, "/") {
		cidr += "/24"
	}
	if _, _, err := net.ParseCIDR(cidr); err != nil {
		return nil, fmt.Errorf("format CIDR tidak valid: %s", cidr)
	}
	name := strings.TrimSpace(sub.Name)
	if name == "" {
		name = "Subnet " + cidr
	}

	updated := false
	for i := range m.namedSubnets {
		if (sub.ID != "" && m.namedSubnets[i].ID == sub.ID) || m.namedSubnets[i].CIDR == cidr {
			m.namedSubnets[i].Name = name
			m.namedSubnets[i].CIDR = cidr
			m.namedSubnets[i].Description = strings.TrimSpace(sub.Description)
			updated = true
			break
		}
	}
	if !updated {
		id := strings.TrimSpace(sub.ID)
		if id == "" {
			id = fmt.Sprintf("sub-%d", time.Now().UnixNano()%1000000)
		}
		m.namedSubnets = append(m.namedSubnets, NamedSubnet{
			ID:          id,
			Name:        name,
			CIDR:        cidr,
			Description: strings.TrimSpace(sub.Description),
		})
	}

	if err := m.saveNamedSubnetsLocked(); err != nil {
		return nil, err
	}
	out := make([]NamedSubnet, len(m.namedSubnets))
	copy(out, m.namedSubnets)
	return out, nil
}

// DeleteNamedSubnet removes a named subnet by ID or CIDR
func (m *Manager) DeleteNamedSubnet(idOrCidr string) ([]NamedSubnet, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	filtered := make([]NamedSubnet, 0, len(m.namedSubnets))
	for _, ns := range m.namedSubnets {
		if ns.ID != idOrCidr && ns.CIDR != idOrCidr {
			filtered = append(filtered, ns)
		}
	}
	m.namedSubnets = filtered
	_ = m.saveNamedSubnetsLocked()
	out := make([]NamedSubnet, len(m.namedSubnets))
	copy(out, m.namedSubnets)
	return out, nil
}

func (m *Manager) loadMetadata() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	data, err := os.ReadFile(m.metadataFile)
	if err != nil {
		return nil
	}
	return json.Unmarshal(data, &m.metadata)
}

func (m *Manager) saveMetadata() error {
	now := time.Now().Unix()
	if now <= m.configVersion {
		m.configVersion++
	} else {
		m.configVersion = now
	}
	data, err := json.MarshalIndent(m.metadata, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(m.metadataFile, data, 0644)
}

// GenerateKeyPair generates a new WireGuard curve25519 keypair
func GenerateKeyPair() (privKeyB64, pubKeyB64 string, err error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return "", "", fmt.Errorf("failed to generate key: %w", err)
	}
	privKeyB64 = base64.StdEncoding.EncodeToString(priv.Bytes())
	pubKeyB64 = base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
	return privKeyB64, pubKeyB64, nil
}

// GetRuntimeStats executes `wg show <iface> dump` and parses metrics
func (m *Manager) GetRuntimeStats() (map[string]*PeerRuntimeStats, error) {
	cmd := exec.Command("wg", "show", m.cfg.WGInterface, "dump")
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("error running wg show: %w", err)
	}

	stats := make(map[string]*PeerRuntimeStats)
	lines := strings.Split(string(out), "\n")
	now := time.Now().Unix()

	for i, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || i == 0 {
			continue
		}

		fields := strings.Split(line, "\t")
		if len(fields) < 8 {
			continue
		}

		pubKey := fields[0]
		endpoint := fields[2]
		allowedIPs := fields[3]
		handshake, _ := strconv.ParseInt(fields[4], 10, 64)
		rx, _ := strconv.ParseInt(fields[5], 10, 64)
		tx, _ := strconv.ParseInt(fields[6], 10, 64)

		if endpoint == "(none)" {
			endpoint = ""
		}

		isConnected := (handshake > 0) && (now-handshake < 180)

		stats[pubKey] = &PeerRuntimeStats{
			Endpoint:             endpoint,
			RuntimeAllowedIPs:    allowedIPs,
			LatestHandshake:      handshake,
			LatestHandshakeHuman: formatHandshake(handshake, now),
			TransferRx:           rx,
			TransferTx:           tx,
			TransferRxHuman:      FormatBytes(rx),
			TransferTxHuman:      FormatBytes(tx),
			TransferTotalHuman:   FormatBytes(rx + tx),
			IsConnected:          isConnected,
		}
	}

	return stats, nil
}

// GetDevices returns all configured devices combined with live runtime stats
func (m *Manager) GetDevices() ([]*Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		return nil, err
	}

	stats, _ := m.GetRuntimeStats()
	devices := make([]*Device, 0, len(cf.Peers))

	for _, peer := range cf.Peers {
		dev := &Device{
			PeerConfig: *peer,
		}

		// Merge metadata if present
		if meta, exists := m.metadata[peer.PublicKey]; exists {
			if meta.Name != "" {
				dev.Name = meta.Name
			}
			if meta.OS != "" {
				dev.OS = meta.OS
			}
			if meta.PrivateKey != "" {
				dev.PrivateKey = meta.PrivateKey
			}
			if meta.CreatedAt != "" {
				dev.CreatedAt = meta.CreatedAt
			}
			dev.Enabled = meta.Enabled
			dev.RoutingMode = normalizeRoutingMode(meta.RoutingMode)
			dev.TransportMode = normalizeTransportMode(meta.TransportMode)
			dev.SplitSubnets = meta.SplitSubnets
			if len(meta.WifiRules) > 0 {
				dev.WifiRules = m.enrichWifiRuleSubnetNames(meta.WifiRules)
			} else {
				dev.WifiRules = m.enrichWifiRuleSubnetNames(DefaultWifiRules())
			}
		} else {
			// Seed metadata if not present
			if dev.Name == "" {
				dev.Name = fmt.Sprintf("device-%s", parsePrimaryIP(peer.AllowedIPs))
			}
			if dev.OS == "" {
				dev.OS = "device"
			}
			dev.CreatedAt = time.Now().Format("2006-01-02 15:04")
			dev.RoutingMode = "split"
			dev.TransportMode = "udp"
			dev.WifiRules = m.enrichWifiRuleSubnetNames(DefaultWifiRules())
			m.metadata[peer.PublicKey] = &PeerMetadata{
				Name:          dev.Name,
				OS:            dev.OS,
				Enabled:       dev.Enabled,
				CreatedAt:     dev.CreatedAt,
				RoutingMode:   dev.RoutingMode,
				TransportMode: dev.TransportMode,
				WifiRules:     dev.WifiRules,
			}
			_ = m.saveMetadata()
		}

		dev.HasPrivateKey = dev.PrivateKey != ""

		// Parse primary allocated IP & subnets
		dev.AllocatedIP, dev.SubnetRoutes = parseAllowedIPs(peer.AllowedIPs)
		dev.IsSubnetRouter = len(dev.SubnetRoutes) > 0

		// Merge runtime stats
		if s, ok := stats[peer.PublicKey]; ok {
			dev.PeerRuntimeStats = *s
			if s.IsConnected {
				dev.Protocol = "WireGuard"
			}
		} else {
			dev.LatestHandshakeHuman = "never"
			dev.TransferRxHuman = "0 B"
			dev.TransferTxHuman = "0 B"
			dev.TransferTotalHuman = "0 B"
			dev.IsConnected = false
		}

		// Merge TCP/UDP bypass tunnel stats if active
		if m.tunnelStatsFn != nil && dev.AllocatedIP != "" {
			endpoint, rx, tx, lastSeen, isConn, proto := m.tunnelStatsFn(dev.AllocatedIP)
			if isConn {
				dev.IsConnected = true
				dev.Protocol = proto
				if endpoint != "" {
					dev.Endpoint = endpoint
				}
				dev.TransferRx += rx
				dev.TransferTx += tx
				dev.TransferRxHuman = FormatBytes(dev.TransferRx)
				dev.TransferTxHuman = FormatBytes(dev.TransferTx)
				dev.TransferTotalHuman = FormatBytes(dev.TransferRx + dev.TransferTx)

				epoch := lastSeen.Unix()
				if epoch > dev.LatestHandshake {
					dev.LatestHandshake = epoch
					dev.LatestHandshakeHuman = formatHandshake(epoch, time.Now().Unix())
				}
			} else if rx > 0 || tx > 0 {
				dev.TransferRx += rx
				dev.TransferTx += tx
				dev.TransferRxHuman = FormatBytes(dev.TransferRx)
				dev.TransferTxHuman = FormatBytes(dev.TransferTx)
				dev.TransferTotalHuman = FormatBytes(dev.TransferRx + dev.TransferTx)
			}
		}

		devices = append(devices, dev)
	}

	return devices, nil
}

func (m *Manager) enrichWifiRuleSubnetNames(rules []WifiProfileRule) []WifiProfileRule {
	out := make([]WifiProfileRule, len(rules))
	for i, r := range rules {
		out[i] = r
		if len(r.Subnets) > 0 {
			var names []string
			for _, cidr := range r.Subnets {
				cidr = strings.TrimSpace(cidr)
				if cidr == "" || cidr == "*" {
					continue
				}
				label := cidr
				for _, ns := range m.namedSubnets {
					if ns.CIDR == cidr {
						label = fmt.Sprintf("%s (%s)", ns.Name, ns.CIDR)
						break
					}
				}
				names = append(names, label)
			}
			out[i].SubnetNames = strings.Join(names, ", ")
		} else {
			out[i].SubnetNames = "Semua Subnet Private"
		}
	}
	return out
}

func normalizeRoutingMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "secure", "full":
		return "secure"
	default:
		return "split"
	}
}

func normalizeTransportMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "wstunnel", "udp2raw", "auto":
		return strings.ToLower(strings.TrimSpace(mode))
	default:
		return "udp"
	}
}

// GetNextAvailableIP computes the next sequential free IP in 10.10.0.0/24
func (m *Manager) GetNextAvailableIP() (string, error) {
	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		return "", err
	}

	usedIPs := make(map[string]bool)
	usedIPs["10.10.0.1"] = true // Server IP

	for _, p := range cf.Peers {
		ip := parsePrimaryIP(p.AllowedIPs)
		if ip != "" {
			usedIPs[ip] = true
		}
	}

	for i := 2; i <= 254; i++ {
		candidate := fmt.Sprintf("10.10.0.%d", i)
		if !usedIPs[candidate] {
			return candidate, nil
		}
	}

	return "", fmt.Errorf("no available IPs left in subnet")
}

// CreateDevice registers a new peer in wg0.conf and in kernel WireGuard
func (m *Manager) CreateDevice(req CreateDeviceRequest) (*CreateDeviceResponse, error) {
	m.mu.Lock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		m.mu.Unlock()
		return nil, fmt.Errorf("failed to parse wg config: %w", err)
	}

	// Determine IP
	allocatedIP := strings.TrimSpace(req.AllocatedIP)
	if allocatedIP == "" {
		m.mu.Unlock()
		nextIP, err := m.GetNextAvailableIP()
		m.mu.Lock()
		if err != nil {
			m.mu.Unlock()
			return nil, err
		}
		allocatedIP = nextIP
	}

	// Generate keypair if not custom
	privKey := strings.TrimSpace(req.CustomPrivKey)
	pubKey := strings.TrimSpace(req.CustomPubKey)

	if privKey == "" || pubKey == "" {
		var err error
		privKey, pubKey, err = GenerateKeyPair()
		if err != nil {
			m.mu.Unlock()
			return nil, err
		}
	}

	// Build peer AllowedIPs
	allowedIPs := allocatedIP + "/32"
	if req.AdvertisedRoute != "" {
		routes := strings.Split(req.AdvertisedRoute, ",")
		for _, r := range routes {
			r = strings.TrimSpace(r)
			if r != "" {
				allowedIPs += ", " + r
			}
		}
	}

	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = fmt.Sprintf("device-%s", allocatedIP)
	}
	osType := strings.TrimSpace(req.OS)
	if osType == "" {
		osType = "device"
	}

	keepalive := req.Keepalive
	if keepalive <= 0 {
		keepalive = m.cfg.DefaultKeepalive
	}

	routingMode := normalizeRoutingMode(req.RoutingMode)
	transportMode := normalizeTransportMode(req.TransportMode)
	wifiRules := req.WifiRules
	if len(wifiRules) == 0 {
		wifiRules = DefaultWifiRules()
	}
	wifiRules = m.enrichWifiRuleSubnetNames(wifiRules)

	peer := &PeerConfig{
		Name:                name,
		OS:                  osType,
		PublicKey:           pubKey,
		PrivateKey:          privKey,
		AllowedIPs:          allowedIPs,
		PersistentKeepalive: keepalive,
		Enabled:             true,
		CreatedAt:           time.Now().Format("2006-01-02 15:04:05"),
		RoutingMode:         routingMode,
		TransportMode:       transportMode,
		SplitSubnets:        req.SplitSubnets,
		WifiRules:           wifiRules,
	}

	// Check if already exists
	for _, p := range cf.Peers {
		if p.PublicKey == pubKey {
			m.mu.Unlock()
			return nil, fmt.Errorf("peer with public key %s already exists", pubKey)
		}
	}

	cf.Peers = append(cf.Peers, peer)

	// Save to wg0.conf
	if err := cf.SaveConfigFile(m.cfg.WGConfigFile); err != nil {
		m.mu.Unlock()
		return nil, fmt.Errorf("failed to write wg config: %w", err)
	}

	// Apply dynamically to WireGuard kernel
	cleanIPs := strings.ReplaceAll(allowedIPs, " ", "")
	cmd := exec.Command("wg", "set", m.cfg.WGInterface, "peer", pubKey, "allowed-ips", cleanIPs)
	if out, err := cmd.CombinedOutput(); err != nil {
		fmt.Printf("Warning: failed to apply peer to kernel: %v (%s)\n", err, string(out))
	}

	// Save metadata
	m.metadata[pubKey] = &PeerMetadata{
		Name:          name,
		OS:            osType,
		PrivateKey:    privKey,
		Enabled:       true,
		CreatedAt:     peer.CreatedAt,
		RoutingMode:   routingMode,
		TransportMode: transportMode,
		SplitSubnets:  req.SplitSubnets,
		WifiRules:     wifiRules,
	}
	_ = m.saveMetadata()
	m.mu.Unlock()

	clientConf, _ := m.GetDeviceConfigWithSubnets(pubKey, routingMode, transportMode, req.SplitSubnets)

	dev := Device{
		PeerConfig:    *peer,
		AllocatedIP:   allocatedIP,
		HasPrivateKey: true,
	}

	return &CreateDeviceResponse{
		Device:     dev,
		ConfigFile: clientConf,
		QRText:     clientConf,
	}, nil
}

// UpdateDevice updates friendly name, OS, allowed IPs, enabled state, routing mode, transport mode, and Wi-Fi SSID rules
func (m *Manager) UpdateDevice(pubKey string, name, osType, allowedIPs string, enabled bool) error {
	return m.UpdateDeviceFull(pubKey, name, osType, allowedIPs, enabled, "", "", nil)
}

// UpdateDeviceFull updates all device attributes including Secure mode, TCP bypass transport, and Wi-Fi SSID rules
func (m *Manager) UpdateDeviceFull(pubKey string, name, osType, allowedIPs string, enabled bool, routingMode, transportMode string, wifiRules []WifiProfileRule) error {
	return m.UpdateDeviceWithSubnets(pubKey, name, osType, allowedIPs, enabled, routingMode, transportMode, nil, wifiRules)
}

// UpdateDeviceWithSubnets updates all device attributes including SplitSubnets and Wi-Fi SSID rules
func (m *Manager) UpdateDeviceWithSubnets(pubKey string, name, osType, allowedIPs string, enabled bool, routingMode, transportMode string, splitSubnets []string, wifiRules []WifiProfileRule) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		return err
	}

	found := false
	var targetAllowedIPs string
	for _, p := range cf.Peers {
		if p.PublicKey == pubKey {
			found = true
			if name != "" {
				p.Name = name
			}
			if osType != "" {
				p.OS = osType
			}
			if allowedIPs != "" {
				p.AllowedIPs = allowedIPs
			}
			targetAllowedIPs = p.AllowedIPs
			p.Enabled = enabled
			break
		}
	}

	if !found {
		return fmt.Errorf("device not found: %s", pubKey)
	}

	if err := cf.SaveConfigFile(m.cfg.WGConfigFile); err != nil {
		return err
	}

	// Update kernel runtime
	if enabled {
		cleanIPs := strings.ReplaceAll(targetAllowedIPs, " ", "")
		cmd := exec.Command("wg", "set", m.cfg.WGInterface, "peer", pubKey, "allowed-ips", cleanIPs)
		_ = cmd.Run()
	} else {
		cmd := exec.Command("wg", "set", m.cfg.WGInterface, "peer", pubKey, "remove")
		_ = cmd.Run()
	}

	// Update metadata
	meta, exists := m.metadata[pubKey]
	if !exists || meta == nil {
		meta = &PeerMetadata{
			Name:          name,
			OS:            osType,
			Enabled:       enabled,
			RoutingMode:   "split",
			TransportMode: "udp",
			WifiRules:     DefaultWifiRules(),
		}
		m.metadata[pubKey] = meta
	}
	if name != "" {
		meta.Name = name
	}
	if osType != "" {
		meta.OS = osType
	}
	meta.Enabled = enabled
	if routingMode != "" {
		meta.RoutingMode = normalizeRoutingMode(routingMode)
	}
	if transportMode != "" {
		meta.TransportMode = normalizeTransportMode(transportMode)
	}
	if splitSubnets != nil {
		meta.SplitSubnets = splitSubnets
	}
	if wifiRules != nil {
		meta.WifiRules = m.enrichWifiRuleSubnetNames(wifiRules)
	}
	return m.saveMetadata()
}

// RegenerateDeviceKey generates a new PrivateKey/PublicKey pair for an existing peer so QR code & .conf work completely
func (m *Manager) RegenerateDeviceKey(oldPubKey string) (*CreateDeviceResponse, error) {
	m.mu.Lock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		m.mu.Unlock()
		return nil, err
	}

	var targetPeer *PeerConfig
	for _, p := range cf.Peers {
		if p.PublicKey == oldPubKey {
			targetPeer = p
			break
		}
	}

	if targetPeer == nil {
		m.mu.Unlock()
		return nil, fmt.Errorf("device not found")
	}

	privKey, newPubKey, err := GenerateKeyPair()
	if err != nil {
		m.mu.Unlock()
		return nil, err
	}

	targetPeer.PublicKey = newPubKey
	targetPeer.PrivateKey = privKey

	if err := cf.SaveConfigFile(m.cfg.WGConfigFile); err != nil {
		m.mu.Unlock()
		return nil, err
	}

	// Replace peer in kernel
	_ = exec.Command("wg", "set", m.cfg.WGInterface, "peer", oldPubKey, "remove").Run()
	if targetPeer.Enabled {
		cleanIPs := strings.ReplaceAll(targetPeer.AllowedIPs, " ", "")
		_ = exec.Command("wg", "set", m.cfg.WGInterface, "peer", newPubKey, "allowed-ips", cleanIPs).Run()
	}

	// Migrate metadata
	oldMeta := m.metadata[oldPubKey]
	delete(m.metadata, oldPubKey)
	newMeta := &PeerMetadata{
		Name:          targetPeer.Name,
		OS:            targetPeer.OS,
		PrivateKey:    privKey,
		Enabled:       targetPeer.Enabled,
		CreatedAt:     time.Now().Format("2006-01-02 15:04:05"),
		RoutingMode:   "split",
		TransportMode: "udp",
		WifiRules:     DefaultWifiRules(),
	}
	if oldMeta != nil {
		if oldMeta.Name != "" {
			newMeta.Name = oldMeta.Name
		}
		if oldMeta.OS != "" {
			newMeta.OS = oldMeta.OS
		}
		if oldMeta.RoutingMode != "" {
			newMeta.RoutingMode = oldMeta.RoutingMode
		}
		if oldMeta.TransportMode != "" {
			newMeta.TransportMode = oldMeta.TransportMode
		}
		if len(oldMeta.SplitSubnets) > 0 {
			newMeta.SplitSubnets = oldMeta.SplitSubnets
		}
		if len(oldMeta.WifiRules) > 0 {
			newMeta.WifiRules = oldMeta.WifiRules
		}
	}
	m.metadata[newPubKey] = newMeta
	_ = m.saveMetadata()
	m.mu.Unlock()

	confText, _ := m.GetDeviceConfigWithSubnets(newPubKey, newMeta.RoutingMode, newMeta.TransportMode, newMeta.SplitSubnets)

	allocatedIP, subnets := parseAllowedIPs(targetPeer.AllowedIPs)
	dev := Device{
		PeerConfig:     *targetPeer,
		AllocatedIP:    allocatedIP,
		SubnetRoutes:   subnets,
		IsSubnetRouter: len(subnets) > 0,
		HasPrivateKey:  true,
	}
	dev.RoutingMode = newMeta.RoutingMode
	dev.TransportMode = newMeta.TransportMode
	dev.SplitSubnets = newMeta.SplitSubnets
	dev.WifiRules = newMeta.WifiRules

	return &CreateDeviceResponse{
		Device:     dev,
		ConfigFile: confText,
		QRText:     confText,
	}, nil
}

// DeleteDevice removes a peer from config and kernel
func (m *Manager) DeleteDevice(pubKey string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		return err
	}

	newPeers := make([]*PeerConfig, 0, len(cf.Peers))
	for _, p := range cf.Peers {
		if p.PublicKey != pubKey {
			newPeers = append(newPeers, p)
		}
	}
	cf.Peers = newPeers

	if err := cf.SaveConfigFile(m.cfg.WGConfigFile); err != nil {
		return err
	}

	// Remove from kernel
	cmd := exec.Command("wg", "set", m.cfg.WGInterface, "peer", pubKey, "remove")
	_ = cmd.Run()

	delete(m.metadata, pubKey)
	return m.saveMetadata()
}

// GetDeviceConfig generates client configuration using saved peer preferences
func (m *Manager) GetDeviceConfig(pubKey string) (string, error) {
	return m.GetDeviceConfigWithOptions(pubKey, "", "")
}

// GetDeviceConfigWithOptions generates client configuration with specified routingMode ("split" or "secure")
// and transportMode ("udp", "wstunnel", "udp2raw")
func (m *Manager) GetDeviceConfigWithOptions(pubKey, routingMode, transportMode string) (string, error) {
	return m.GetDeviceConfigWithSubnets(pubKey, routingMode, transportMode, nil)
}

// GetDeviceConfigWithSubnets generates client configuration with optional custom split subnets filter
func (m *Manager) GetDeviceConfigWithSubnets(pubKey, routingMode, transportMode string, customSubnets []string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	cf, err := ParseConfigFile(m.cfg.WGConfigFile)
	if err != nil {
		return "", err
	}

	var targetPeer *PeerConfig
	for _, p := range cf.Peers {
		if p.PublicKey == pubKey {
			targetPeer = p
			break
		}
	}

	if targetPeer == nil {
		return "", fmt.Errorf("peer not found")
	}

	meta := m.metadata[pubKey]
	privKey := ""
	savedRouting := "split"
	savedTransport := "udp"
	var savedSubnets []string
	if meta != nil {
		if meta.PrivateKey != "" {
			privKey = meta.PrivateKey
		}
		if meta.RoutingMode != "" {
			savedRouting = normalizeRoutingMode(meta.RoutingMode)
		}
		if meta.TransportMode != "" {
			savedTransport = normalizeTransportMode(meta.TransportMode)
		}
		savedSubnets = meta.SplitSubnets
	}
	if privKey == "" {
		privKey = "<YOUR_CLIENT_PRIVATE_KEY>"
	}

	rMode := savedRouting
	if routingMode != "" {
		rMode = normalizeRoutingMode(routingMode)
	}
	tMode := savedTransport
	if transportMode != "" {
		tMode = normalizeTransportMode(transportMode)
	}

	activeSubnets := customSubnets
	if len(activeSubnets) == 0 {
		activeSubnets = savedSubnets
	}

	allocatedIP := parsePrimaryIP(targetPeer.AllowedIPs)
	serverPubKey := cf.Interface.PublicKey
	if serverPubKey == "" {
		serverPubKey = "J6gLYFFjvZ8WKyvej80RRTET4ygNWkdmaJjOQSou8UM="
	}

	keepalive := targetPeer.PersistentKeepalive
	if keepalive <= 0 {
		keepalive = m.cfg.DefaultKeepalive
	}

	var clientAllowedIPs string
	var modeComment string
	if rMode == "secure" {
		clientAllowedIPs = "0.0.0.0/0, ::/0"
		modeComment = "# Stabirush Mode: SECURE (Full Tunnel 0.0.0.0/0)\n"
	} else if len(activeSubnets) > 0 && activeSubnets[0] != "*" {
		// Specific Split Subnets selected
		var cidrList []string
		var nameComments []string
		hasGateway := false
		for _, s := range activeSubnets {
			s = strings.TrimSpace(s)
			if s == "" {
				continue
			}
			if s == "10.10.0.0/24" || s == "10.10.0.1/32" {
				hasGateway = true
			}
			cidrList = append(cidrList, s)
			for _, ns := range m.namedSubnets {
				if ns.CIDR == s {
					nameComments = append(nameComments, fmt.Sprintf("%s (%s)", ns.Name, ns.CIDR))
					break
				}
			}
		}
		if !hasGateway {
			// Ensure Stabirush DNS/gateway IP is reachable
			cidrList = append([]string{"10.10.0.1/32"}, cidrList...)
		}
		clientAllowedIPs = strings.Join(cidrList, ", ")
		if len(nameComments) > 0 {
			modeComment = fmt.Sprintf("# Stabirush Mode: SPLIT TUNNEL (Subnets: %s)\n", strings.Join(nameComments, ", "))
		} else {
			modeComment = fmt.Sprintf("# Stabirush Mode: SPLIT TUNNEL (Subnets: %s)\n", clientAllowedIPs)
		}
	} else {
		// All Split Subnets (default)
		var cidrs []string
		cidrs = append(cidrs, "10.10.0.0/24")
		for _, ns := range m.namedSubnets {
			if !containsStr(cidrs, ns.CIDR) {
				cidrs = append(cidrs, ns.CIDR)
			}
		}
		for _, p := range cf.Peers {
			_, subnets := parseAllowedIPs(p.AllowedIPs)
			for _, sub := range subnets {
				if !containsStr(cidrs, sub) {
					cidrs = append(cidrs, sub)
				}
			}
		}
		clientAllowedIPs = strings.Join(cidrs, ", ")
		modeComment = "# Stabirush Mode: SPLIT TUNNEL (All Private Subnets)\n"
	}

	serverHost := m.getServerHostOnly()
	endpoint := m.cfg.ServerEndpoint
	mtu := m.cfg.DefaultMTU
	var hookLines string

	if tMode == "wstunnel" {
		endpoint = "127.0.0.1:51820"
		mtu = "1280"
		wsPort := m.cfg.WstunnelPort
		wsPrefix := m.cfg.WstunnelPrefix
		modeComment += fmt.Sprintf("# Transport: TCP Bypass via wstunnel (wss://%s:%d /%s -> wg0)\n", serverHost, wsPort, wsPrefix)
		hookLines = fmt.Sprintf(`Table = off
PreUp = gw=$(ip route show default 2>/dev/null | awk '{print $3; exit}'); [ -n "$gw" ] && ip route replace %s/32 via "$gw" || true; nohup wstunnel client -L "udp://127.0.0.1:51820:127.0.0.1:51820?timeout_sec=0" wss://%s:%d --http-upgrade-path-prefix %s >/tmp/stabirush-wstunnel.log 2>&1 & sleep 1
PostUp = ip route replace 10.10.0.0/24 dev %%i; %s
PostDown = pkill -f "wstunnel client.*%s:%d" || true; ip route del %s/32 2>/dev/null || true
`, serverHost, serverHost, wsPort, wsPrefix, buildPostUpRouteCmd(rMode, clientAllowedIPs), serverHost, wsPort, serverHost)
	} else if tMode == "udp2raw" {
		endpoint = "127.0.0.1:51820"
		mtu = "1280"
		u2rPort := m.cfg.Udp2rawPort
		u2rPass := m.cfg.Udp2rawPassword
		modeComment += fmt.Sprintf("# Transport: TCP Bypass via udp2raw FakeTCP (%s:%d -> wg0)\n", serverHost, u2rPort)
		hookLines = fmt.Sprintf(`Table = off
PreUp = gw=$(ip route show default 2>/dev/null | awk '{print $3; exit}'); [ -n "$gw" ] && ip route replace %s/32 via "$gw" || true; nohup udp2raw -c -l 127.0.0.1:51820 -r %s:%d -k "%s" --raw-mode faketcp -a >/tmp/stabirush-udp2raw.log 2>&1 & sleep 1
PostUp = ip route replace 10.10.0.0/24 dev %%i; %s
PostDown = pkill -f "udp2raw -c.*%s:%d" || true; ip route del %s/32 2>/dev/null || true
`, serverHost, serverHost, u2rPort, u2rPass, buildPostUpRouteCmd(rMode, clientAllowedIPs), serverHost, u2rPort, serverHost)
	}

	return fmt.Sprintf(`%s[Interface]
PrivateKey = %s
Address = %s/32
DNS = %s
MTU = %s
%s
[Peer]
PublicKey = %s
Endpoint = %s
AllowedIPs = %s
PersistentKeepalive = %d
`, modeComment, privKey, allocatedIP, m.cfg.DefaultDNS, mtu, hookLines, serverPubKey, endpoint, clientAllowedIPs, keepalive), nil
}

func containsStr(slice []string, target string) bool {
	for _, s := range slice {
		if s == target {
			return true
		}
	}
	return false
}

func buildPostUpRouteCmd(rMode, allowedIPs string) string {
	if rMode == "secure" {
		return "ip route replace 0.0.0.0/1 dev %i; ip route replace 128.0.0.0/1 dev %i"
	}
	var cmds []string
	for _, part := range strings.Split(allowedIPs, ",") {
		cidr := strings.TrimSpace(part)
		if cidr != "" && cidr != "10.10.0.0/24" {
			cmds = append(cmds, fmt.Sprintf("ip route replace %s dev %%i", cidr))
		}
	}
	if len(cmds) == 0 {
		return "true"
	}
	return strings.Join(cmds, "; ")
}

// GetSubnets returns all advertised subnets and named subnets
func (m *Manager) GetSubnets() ([]SubnetRoute, error) {
	devices, err := m.GetDevices()
	if err != nil {
		return nil, err
	}
	named := m.GetNamedSubnets()

	routes := make([]SubnetRoute, 0)
	seen := make(map[string]bool)

	for _, dev := range devices {
		for _, sub := range dev.SubnetRoutes {
			sName := "Subnet " + sub
			sDesc := ""
			for _, ns := range named {
				if ns.CIDR == sub {
					sName = ns.Name
					sDesc = ns.Description
					break
				}
			}
			routes = append(routes, SubnetRoute{
				Subnet:      sub,
				SubnetName:  sName,
				Description: sDesc,
				DeviceName:  dev.Name,
				DeviceIP:    dev.AllocatedIP,
				PublicKey:   dev.PublicKey,
				Active:      dev.IsConnected,
			})
			seen[sub] = true
		}
	}

	// Also append any named subnets that aren't tied to a specific peer's AllowedIPs (e.g. 10.10.0.0/24, 10.0.0.0/24, or custom named subnets)
	for _, ns := range named {
		if !seen[ns.CIDR] {
			routes = append(routes, SubnetRoute{
				Subnet:      ns.CIDR,
				SubnetName:  ns.Name,
				Description: ns.Description,
				DeviceName:  "Stabirush Gateway (wg0)",
				DeviceIP:    "10.10.0.1",
				PublicKey:   "",
				Active:      true,
			})
		}
	}

	return routes, nil
}

// GetServerPublicKey returns the active WireGuard server public key
func (m *Manager) GetServerPublicKey() string {
	st, err := m.GetServerStatus()
	if err == nil && st != nil && st.PublicKey != "" {
		return st.PublicKey
	}
	return "J6gLYFFjvZ8WKyvej80RRTET4ygNWkdmaJjOQSou8UM="
}

// GetServerStatus returns complete health and metrics including TCP bypass status and NamedSubnets
func (m *Manager) GetServerStatus() (*ServerStatus, error) {
	m.EnsureBypassServices()

	devices, err := m.GetDevices()
	if err != nil {
		return nil, err
	}
	namedSubnets := m.GetNamedSubnets()

	var totalRx, totalTx int64
	var connectedCount int

	for _, d := range devices {
		totalRx += d.TransferRx
		totalTx += d.TransferTx
		if d.IsConnected {
			connectedCount++
		}
	}

	// Check IP Forwarding
	ipForward := false
	if data, err := os.ReadFile("/proc/sys/net/ipv4/ip_forward"); err == nil {
		ipForward = strings.TrimSpace(string(data)) == "1"
	}

	// Memory stats
	var mStats runtime.MemStats
	runtime.ReadMemStats(&mStats)

	cf, _ := ParseConfigFile(m.cfg.WGConfigFile)
	serverPubKey := "J6gLYFFjvZ8WKyvej80RRTET4ygNWkdmaJjOQSou8UM="
	address := "10.10.0.1/24"
	listenPort := 51820
	if cf != nil {
		if cf.Interface.PublicKey != "" {
			serverPubKey = cf.Interface.PublicKey
		}
		if cf.Interface.Address != "" {
			address = cf.Interface.Address
		}
		if cf.Interface.ListenPort > 0 {
			listenPort = cf.Interface.ListenPort
		}
	}

	uptimeDur := time.Since(m.startTime)
	uptimeStr := formatDuration(uptimeDur)

	return &ServerStatus{
		Interface:        m.cfg.WGInterface,
		PublicKey:        serverPubKey,
		ListenPort:       listenPort,
		ExternalEndpoint: m.cfg.ServerEndpoint,
		Address:          address,
		TotalDevices:     len(devices),
		ConnectedDevices: connectedCount,
		TotalRx:          totalRx,
		TotalTx:          totalTx,
		TotalRxHuman:     FormatBytes(totalRx),
		TotalTxHuman:     FormatBytes(totalTx),
		TotalSubnets:     len(namedSubnets),
		IPForwardEnabled: ipForward,
		MemoryUsageKB:    mStats.Alloc / 1024,
		StartedAt:        m.startTime,
		Uptime:           uptimeStr,
		Bypass:           m.GetBypassStatus(),
		NamedSubnets:     namedSubnets,
	}, nil
}

// Helper utility functions

func parsePrimaryIP(allowedIPs string) string {
	parts := strings.Split(allowedIPs, ",")
	for _, p := range parts {
		p = strings.TrimSpace(p)
		ip, _, err := net.ParseCIDR(p)
		if err == nil && ip.To4() != nil {
			return ip.String()
		}
	}
	return ""
}

func parseAllowedIPs(allowedIPs string) (primaryIP string, subnets []string) {
	parts := strings.Split(allowedIPs, ",")
	subnets = make([]string, 0)
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		ip, ipNet, err := net.ParseCIDR(p)
		if err == nil {
			if i == 0 && ip.To4() != nil {
				primaryIP = ip.String()
			}
			ones, bits := ipNet.Mask.Size()
			if bits == 32 && ones < 32 {
				subnets = append(subnets, p)
			}
		}
	}
	return primaryIP, subnets
}

func formatHandshake(epoch int64, now int64) string {
	if epoch <= 0 {
		return "never"
	}
	diff := now - epoch
	if diff < 0 {
		diff = 0
	}
	if diff < 60 {
		return fmt.Sprintf("%ds ago", diff)
	}
	if diff < 3600 {
		return fmt.Sprintf("%dm ago", diff/60)
	}
	if diff < 86400 {
		return fmt.Sprintf("%dh ago", diff/3600)
	}
	return fmt.Sprintf("%dd ago", diff/86400)
}

// FormatBytes formats byte counts cleanly
func FormatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(b)/float64(div), "KMGTPE"[exp])
}

func formatDuration(d time.Duration) string {
	d = d.Round(time.Second)
	h := d / time.Hour
	d -= h * time.Hour
	m := d / time.Minute
	d -= m * time.Minute
	s := d / time.Second
	if h > 0 {
		return fmt.Sprintf("%dh %dm %ds", h, m, s)
	}
	return fmt.Sprintf("%dm %ds", m, s)
}
