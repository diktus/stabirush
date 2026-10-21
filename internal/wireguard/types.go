package wireguard

import "time"

// NamedSubnet represents a named subnet CIDR for Split Tunnel rules
type NamedSubnet struct {
	ID          string `json:"id"`
	Name        string `json:"name"`        // e.g. "Subnet A (Server Kantor)", "Stabirush Mesh"
	CIDR        string `json:"cidr"`        // e.g. "192.168.101.0/24", "10.10.0.0/24"
	Description string `json:"description"` // optional notes
}

// WifiProfileRule defines automatic profile switching and per-SSID subnet filtering when connected to a specific Wi-Fi SSID
type WifiProfileRule struct {
	SSID        string   `json:"ssid"`                  // e.g. "Office_WiFi", "Home_5G", "Starbucks", "*"
	ProfileMode string   `json:"profileMode"`           // "split", "secure", "bypass_wstunnel", "bypass_udp2raw", "disconnect"
	Subnets     []string `json:"subnets,omitempty"`     // Specific subnet CIDRs to tunnel on this SSID (empty = all split subnets)
	SubnetNames string   `json:"subnetNames,omitempty"` // Friendly display of selected subnet names
}

// InterfaceConfig holds the wireguard interface configuration
type InterfaceConfig struct {
	Address    string   `json:"address"`
	MTU        string   `json:"mtu"`
	SaveConfig string   `json:"saveConfig"`
	ListenPort int      `json:"listenPort"`
	PrivateKey string   `json:"-"`
	PublicKey  string   `json:"publicKey"`
	PostUp     string   `json:"postUp"`
	PostDown   string   `json:"postDown"`
	RawLines   []string `json:"-"`
}

// PeerConfig holds configuration and metadata for a peer
type PeerConfig struct {
	Name                string            `json:"name"`
	OS                  string            `json:"os"` // macos, windows, linux, ios, android, browser, router
	PublicKey           string            `json:"publicKey"`
	PrivateKey          string            `json:"privateKey,omitempty"` // Saved client private key (if generated)
	PresharedKey        string            `json:"presharedKey,omitempty"`
	AllowedIPs          string            `json:"allowedIPs"`
	ConfiguredEndpoint  string            `json:"configuredEndpoint,omitempty"`
	PersistentKeepalive int               `json:"persistentKeepalive,omitempty"`
	Enabled             bool              `json:"enabled"`
	CreatedAt           string            `json:"createdAt"`
	RoutingMode         string            `json:"routingMode"`            // "split" or "secure" (0.0.0.0/0)
	TransportMode       string            `json:"transportMode"`          // "udp", "wstunnel", "udp2raw", "auto"
	SplitSubnets        []string          `json:"splitSubnets,omitempty"` // Default split tunnel subnets for this peer
	WifiRules           []WifiProfileRule `json:"wifiRules,omitempty"`
	Comments            []string          `json:"-"`
}

// PeerRuntimeStats holds live information from `wg show <iface> dump`
type PeerRuntimeStats struct {
	Endpoint             string `json:"endpoint"`
	RuntimeAllowedIPs    string `json:"runtimeAllowedIPs,omitempty"`
	LatestHandshake      int64  `json:"latestHandshake"`
	LatestHandshakeHuman string `json:"latestHandshakeHuman"`
	TransferRx           int64  `json:"transferRx"`
	TransferTx           int64  `json:"transferTx"`
	TransferRxHuman      string `json:"transferRxHuman"`
	TransferTxHuman      string `json:"transferTxHuman"`
	TransferTotalHuman   string `json:"transferTotalHuman"`
	IsConnected          bool   `json:"isConnected"`
	Protocol             string `json:"protocol,omitempty"`
}

// Device combines config and live runtime stats for the UI
type Device struct {
	PeerConfig
	PeerRuntimeStats
	AllocatedIP    string   `json:"allocatedIP"`
	SubnetRoutes   []string `json:"subnetRoutes"`
	IsSubnetRouter bool     `json:"isSubnetRouter"`
	HasPrivateKey  bool     `json:"hasPrivateKey"`
}

// SubnetRoute represents an approved or advertised subnet route
type SubnetRoute struct {
	Subnet      string `json:"subnet"`
	SubnetName  string `json:"subnetName"`
	Description string `json:"description,omitempty"`
	DeviceName  string `json:"deviceName"`
	DeviceIP    string `json:"deviceIP"`
	PublicKey   string `json:"publicKey"`
	Active      bool   `json:"active"`
}

// BypassStatus holds live state of TCP bypass daemons (wstunnel & udp2raw)
type BypassStatus struct {
	WstunnelEnabled bool   `json:"wstunnelEnabled"`
	WstunnelRunning bool   `json:"wstunnelRunning"`
	WstunnelPID     int    `json:"wstunnelPid"`
	WstunnelPort    int    `json:"wstunnelPort"`
	WstunnelPrefix  string `json:"wstunnelPrefix"`
	WstunnelQUIC    bool   `json:"wstunnelQuic"`
	WstunnelURL     string `json:"wstunnelUrl"`
	WstunnelQuicURL string `json:"wstunnelQuicUrl"`
	Udp2rawEnabled  bool   `json:"udp2rawEnabled"`
	Udp2rawRunning  bool   `json:"udp2rawRunning"`
	Udp2rawPID      int    `json:"udp2rawPid"`
	Udp2rawPort     int    `json:"udp2rawPort"`
	Udp2rawPassword string `json:"udp2rawPassword"`
	Udp2rawEndpoint string `json:"udp2rawEndpoint"`
	TargetWG        string `json:"targetWg"`
}

// ServerStatus provides overall VPN status and metrics
type ServerStatus struct {
	Interface        string        `json:"interface"`
	PublicKey        string        `json:"publicKey"`
	ListenPort       int           `json:"listenPort"`
	ExternalEndpoint string        `json:"externalEndpoint"`
	Address          string        `json:"address"`
	TotalDevices     int           `json:"totalDevices"`
	ConnectedDevices int           `json:"connectedDevices"`
	TotalRx          int64         `json:"totalRx"`
	TotalTx          int64         `json:"totalTx"`
	TotalRxHuman     string        `json:"totalRxHuman"`
	TotalTxHuman     string        `json:"totalTxHuman"`
	TotalSubnets     int           `json:"totalSubnets"`
	IPForwardEnabled bool          `json:"ipForwardEnabled"`
	MemoryUsageKB    uint64        `json:"memoryUsageKb"`
	StartedAt        time.Time     `json:"startedAt"`
	Uptime           string        `json:"uptime"`
	Bypass           BypassStatus  `json:"bypass"`
	NamedSubnets     []NamedSubnet `json:"namedSubnets,omitempty"`
}

// CreateDeviceRequest represents the payload for creating a new peer
type CreateDeviceRequest struct {
	Name            string            `json:"name"`
	OS              string            `json:"os"`
	AllocatedIP     string            `json:"allocatedIP"`
	ClientDNS       string            `json:"clientDns"`
	ClientMTU       string            `json:"clientMtu"`
	RoutingMode     string            `json:"routingMode"`   // "split" or "secure" ("full")
	TransportMode   string            `json:"transportMode"` // "udp", "wstunnel", "udp2raw", "auto"
	SplitSubnets    []string          `json:"splitSubnets,omitempty"`
	WifiRules       []WifiProfileRule `json:"wifiRules,omitempty"`
	AdvertisedRoute string            `json:"advertisedRoute"`
	Keepalive       int               `json:"keepalive"`
	CustomPubKey    string            `json:"customPubKey,omitempty"`
	CustomPrivKey   string            `json:"customPrivKey,omitempty"`
}

// CreateDeviceResponse returns config and client details
type CreateDeviceResponse struct {
	Device     Device `json:"device"`
	ConfigFile string `json:"configFile"`
	QRText     string `json:"qrText"`
}
