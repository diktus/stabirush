package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// AppConfig represents Stabirush server settings
type AppConfig struct {
	WebPort          string `json:"webPort"`
	WGInterface      string `json:"wgInterface"`
	WGConfigFile     string `json:"wgConfigFile"`
	DataDir          string `json:"dataDir"`
	ServerEndpoint   string `json:"serverEndpoint"`
	DefaultDNS       string `json:"defaultDns"`
	DefaultMTU       string `json:"defaultMtu"`
	DefaultKeepalive int    `json:"defaultKeepalive"`
	TailnetName      string `json:"tailnetName"`
	AuthUsername     string `json:"authUsername"`
	AuthPassword     string `json:"authPassword"`

	// TCP Bypass Settings (wstunnel & udp2raw)
	WstunnelEnabled bool   `json:"wstunnelEnabled"`
	WstunnelPort    int    `json:"wstunnelPort"`
	WstunnelPrefix  string `json:"wstunnelPrefix"`
	Udp2rawEnabled  bool   `json:"udp2rawEnabled"`
	Udp2rawPort     int    `json:"udp2rawPort"`
	Udp2rawPassword string `json:"udp2rawPassword"`

	mu sync.RWMutex
}

var globalConfig *AppConfig

// LoadConfig loads configuration from data/settings.json or initializes defaults
func LoadConfig(dataDir string) (*AppConfig, error) {
	if dataDir == "" {
		dataDir = "/home/wireguard-ui/data"
	}
	_ = os.MkdirAll(dataDir, 0755)

	cfg := &AppConfig{
		WebPort:          "51821",
		WGInterface:      "wg0",
		WGConfigFile:     "/etc/wireguard/wg0.conf",
		DataDir:          dataDir,
		ServerEndpoint:   "168.110.193.226:51820",
		DefaultDNS:       "10.10.0.1",
		DefaultMTU:       "1380",
		DefaultKeepalive: 25,
		TailnetName:      "beni.stabirush",
		WstunnelEnabled:  true,
		WstunnelPort:     8443,
		WstunnelPrefix:   "stabirush",
		Udp2rawEnabled:   true,
		Udp2rawPort:      4096,
		Udp2rawPassword:  "stabirush-raw-secret",
	}

	settingsFile := filepath.Join(dataDir, "settings.json")
	if data, err := os.ReadFile(settingsFile); err == nil {
		_ = json.Unmarshal(data, cfg)
		// Migrate old tailnet name to stabirush if still using default
		if strings.Contains(cfg.TailnetName, "tailnet") || strings.Contains(cfg.TailnetName, "tailscale") {
			cfg.TailnetName = "beni.stabirush"
		}
		if cfg.WstunnelPort == 0 {
			cfg.WstunnelPort = 8443
			cfg.WstunnelEnabled = true
		}
		if cfg.WstunnelPrefix == "" {
			cfg.WstunnelPrefix = "stabirush"
		}
		if cfg.Udp2rawPort == 0 {
			cfg.Udp2rawPort = 4096
			cfg.Udp2rawEnabled = true
		}
		if cfg.Udp2rawPassword == "" {
			cfg.Udp2rawPassword = "stabirush-raw-secret"
		}
		_ = cfg.Save()
	} else {
		_ = cfg.Save()
	}

	globalConfig = cfg
	return cfg, nil
}

// Get returns the loaded configuration
func Get() *AppConfig {
	if globalConfig == nil {
		globalConfig, _ = LoadConfig("/home/wireguard-ui/data")
	}
	return globalConfig
}

// Save saves current configuration to settings.json
func (c *AppConfig) Save() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	settingsFile := filepath.Join(c.DataDir, "settings.json")
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(settingsFile, data, 0644)
}

// Update updates config fields
func (c *AppConfig) Update(endpoint, dns, mtu, tailnet string, keepalive int, authUser, authPass string) error {
	c.mu.Lock()
	if endpoint != "" {
		c.ServerEndpoint = endpoint
	}
	if dns != "" {
		c.DefaultDNS = dns
	}
	if mtu != "" {
		c.DefaultMTU = mtu
	}
	if tailnet != "" {
		c.TailnetName = tailnet
	}
	if keepalive > 0 {
		c.DefaultKeepalive = keepalive
	}
	c.AuthUsername = authUser
	c.AuthPassword = authPass
	c.mu.Unlock()

	return c.Save()
}

// UpdateBypass updates TCP bypass configuration
func (c *AppConfig) UpdateBypass(wsEnabled bool, wsPort int, wsPrefix string, udpEnabled bool, udpPort int, udpPass string) error {
	c.mu.Lock()
	c.WstunnelEnabled = wsEnabled
	if wsPort > 0 {
		c.WstunnelPort = wsPort
	}
	if wsPrefix != "" {
		c.WstunnelPrefix = wsPrefix
	}
	c.Udp2rawEnabled = udpEnabled
	if udpPort > 0 {
		c.Udp2rawPort = udpPort
	}
	if udpPass != "" {
		c.Udp2rawPassword = udpPass
	}
	c.mu.Unlock()

	return c.Save()
}
