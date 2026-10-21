package wireguard

import (
	"bufio"
	"crypto/ecdh"
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// DerivePublicKey computes the WireGuard public key from a base64-encoded private key
func DerivePublicKey(privKeyB64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(privKeyB64))
	if err != nil {
		return "", fmt.Errorf("invalid base64 private key: %w", err)
	}
	priv, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil {
		return "", fmt.Errorf("invalid x25519 private key: %w", err)
	}
	return base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes()), nil
}

// ConfigFile represents the parsed wg0.conf
type ConfigFile struct {
	Interface InterfaceConfig
	Peers     []*PeerConfig
	FilePath  string
}

// ParseConfigFile parses a wireguard configuration file
func ParseConfigFile(filePath string) (*ConfigFile, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	cf := &ConfigFile{
		FilePath: filePath,
		Peers:    make([]*PeerConfig, 0),
	}

	scanner := bufio.NewScanner(file)
	var currentSection string
	var currentPeer *PeerConfig
	var preSectionComments []string

	for scanner.Scan() {
		rawLine := scanner.Text()
		line := strings.TrimSpace(rawLine)

		// Empty line
		if line == "" {
			continue
		}

		// Comment line
		if strings.HasPrefix(line, "#") {
			if currentPeer != nil && currentSection == "peer" {
				// Comment inside an existing peer section
				currentPeer.Comments = append(currentPeer.Comments, line)
				extractMetadataFromComments(currentPeer, []string{line})
			} else {
				preSectionComments = append(preSectionComments, line)
			}
			continue
		}

		// Section headers
		if strings.EqualFold(line, "[Interface]") {
			currentSection = "interface"
			cf.Interface.RawLines = append(cf.Interface.RawLines, preSectionComments...)
			preSectionComments = nil
			continue
		}

		if strings.EqualFold(line, "[Peer]") {
			currentSection = "peer"
			if currentPeer != nil && currentPeer.PublicKey != "" {
				cf.Peers = append(cf.Peers, currentPeer)
			}
			currentPeer = &PeerConfig{
				Enabled:  true,
				Comments: preSectionComments,
			}
			extractMetadataFromComments(currentPeer, preSectionComments)
			preSectionComments = nil
			continue
		}

		// Parse key = value
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}

		key := strings.TrimSpace(parts[0])
		val := strings.TrimSpace(parts[1])

		if currentSection == "interface" {
			switch strings.ToLower(key) {
			case "address":
				cf.Interface.Address = val
			case "mtu":
				cf.Interface.MTU = val
			case "saveconfig":
				cf.Interface.SaveConfig = val
			case "listenport":
				if p, err := strconv.Atoi(val); err == nil {
					cf.Interface.ListenPort = p
				}
			case "privatekey":
				cf.Interface.PrivateKey = val
				if pub, err := DerivePublicKey(val); err == nil {
					cf.Interface.PublicKey = pub
				}
			case "postup":
				cf.Interface.PostUp = val
			case "postdown":
				cf.Interface.PostDown = val
			}
		} else if currentSection == "peer" && currentPeer != nil {
			switch strings.ToLower(key) {
			case "publickey":
				currentPeer.PublicKey = val
			case "presharedkey":
				currentPeer.PresharedKey = val
			case "allowedips":
				currentPeer.AllowedIPs = val
			case "endpoint":
				currentPeer.ConfiguredEndpoint = val
			case "persistentkeepalive":
				if k, err := strconv.Atoi(val); err == nil {
					currentPeer.PersistentKeepalive = k
				}
			}
		}
	}

	if currentPeer != nil && currentPeer.PublicKey != "" {
		cf.Peers = append(cf.Peers, currentPeer)
	}

	if err := scanner.Err(); err != nil {
		return nil, err
	}

	return cf, nil
}

// extractMetadataFromComments checks comments for Name or OS
func extractMetadataFromComments(peer *PeerConfig, comments []string) {
	for _, c := range comments {
		c = strings.TrimPrefix(c, "#")
		c = strings.TrimSpace(c)
		if c == "" {
			continue
		}

		if strings.HasPrefix(strings.ToLower(c), "name:") {
			peer.Name = strings.TrimSpace(c[5:])
			continue
		}
		if strings.HasPrefix(strings.ToLower(c), "os:") {
			peer.OS = strings.TrimSpace(c[3:])
			continue
		}
		if strings.HasPrefix(strings.ToLower(c), "created:") {
			peer.CreatedAt = strings.TrimSpace(c[8:])
			continue
		}

		// If no explicit prefix, and we haven't set name yet, use this comment as friendly name
		if peer.Name == "" && !strings.Contains(c, "Rules") && !strings.Contains(c, "Ubuntu") {
			peer.Name = c
		}
	}

	// Guess OS from Name if not set
	if peer.OS == "" && peer.Name != "" {
		lower := strings.ToLower(peer.Name)
		if strings.Contains(lower, "mac") || strings.Contains(lower, "apple") || strings.Contains(lower, "darwin") {
			peer.OS = "macos"
		} else if strings.Contains(lower, "win") {
			peer.OS = "windows"
		} else if strings.Contains(lower, "linux") || strings.Contains(lower, "ubuntu") || strings.Contains(lower, "debian") {
			peer.OS = "linux"
		} else if strings.Contains(lower, "iphone") || strings.Contains(lower, "ipad") || strings.Contains(lower, "ios") {
			peer.OS = "ios"
		} else if strings.Contains(lower, "android") {
			peer.OS = "android"
		} else if strings.Contains(lower, "edge") || strings.Contains(lower, "chrome") || strings.Contains(lower, "browser") {
			peer.OS = "browser"
		} else if strings.Contains(lower, "router") || strings.Contains(lower, "mikrotik") || strings.Contains(lower, "openwrt") {
			peer.OS = "router"
		} else {
			peer.OS = "device"
		}
	}
}

// SaveConfigFile writes the configuration to disk with a backup
func (cf *ConfigFile) SaveConfigFile(filePath string) error {
	// Create backup first
	if _, err := os.Stat(filePath); err == nil {
		bakPath := fmt.Sprintf("%s.bak_%s", filePath, time.Now().Format("20060102_150405"))
		_ = copyFile(filePath, bakPath)
	}

	var sb strings.Builder

	// [Interface]
	sb.WriteString("[Interface]\n")
	if cf.Interface.Address != "" {
		sb.WriteString(fmt.Sprintf("Address = %s\n", cf.Interface.Address))
	}
	if cf.Interface.MTU != "" {
		sb.WriteString(fmt.Sprintf("MTU = %s\n", cf.Interface.MTU))
	}
	if cf.Interface.SaveConfig != "" {
		sb.WriteString(fmt.Sprintf("SaveConfig = %s\n", cf.Interface.SaveConfig))
	}
	if cf.Interface.ListenPort > 0 {
		sb.WriteString(fmt.Sprintf("ListenPort = %d\n", cf.Interface.ListenPort))
	}
	if cf.Interface.PrivateKey != "" {
		sb.WriteString(fmt.Sprintf("PrivateKey = %s\n", cf.Interface.PrivateKey))
	}
	if cf.Interface.PostUp != "" {
		sb.WriteString("\n# Rules IPTables\n")
		sb.WriteString(fmt.Sprintf("PostUp = %s\n", cf.Interface.PostUp))
	}
	if cf.Interface.PostDown != "" {
		sb.WriteString(fmt.Sprintf("PostDown = %s\n", cf.Interface.PostDown))
	}

	// [Peer]s
	for _, peer := range cf.Peers {
		sb.WriteString("\n")
		if !peer.Enabled {
			sb.WriteString("# [Peer] (Disabled)\n")
			if peer.Name != "" {
				sb.WriteString(fmt.Sprintf("# Name: %s\n", peer.Name))
			}
			if peer.OS != "" {
				sb.WriteString(fmt.Sprintf("# OS: %s\n", peer.OS))
			}
			sb.WriteString(fmt.Sprintf("# PublicKey = %s\n", peer.PublicKey))
			sb.WriteString(fmt.Sprintf("# AllowedIPs = %s\n", peer.AllowedIPs))
			if peer.ConfiguredEndpoint != "" {
				sb.WriteString(fmt.Sprintf("# Endpoint = %s\n", peer.ConfiguredEndpoint))
			}
			if peer.PersistentKeepalive > 0 {
				sb.WriteString(fmt.Sprintf("# PersistentKeepalive = %d\n", peer.PersistentKeepalive))
			}
			continue
		}

		sb.WriteString("[Peer]\n")
		if peer.Name != "" {
			sb.WriteString(fmt.Sprintf("# Name: %s\n", peer.Name))
		}
		if peer.OS != "" {
			sb.WriteString(fmt.Sprintf("# OS: %s\n", peer.OS))
		}
		if peer.CreatedAt != "" {
			sb.WriteString(fmt.Sprintf("# Created: %s\n", peer.CreatedAt))
		}
		sb.WriteString(fmt.Sprintf("PublicKey = %s\n", peer.PublicKey))
		if peer.PresharedKey != "" {
			sb.WriteString(fmt.Sprintf("PresharedKey = %s\n", peer.PresharedKey))
		}
		sb.WriteString(fmt.Sprintf("AllowedIPs = %s\n", peer.AllowedIPs))
		if peer.ConfiguredEndpoint != "" {
			sb.WriteString(fmt.Sprintf("Endpoint = %s\n", peer.ConfiguredEndpoint))
		}
		if peer.PersistentKeepalive > 0 {
			sb.WriteString(fmt.Sprintf("PersistentKeepalive = %d\n", peer.PersistentKeepalive))
		}
	}

	return os.WriteFile(filePath, []byte(sb.String()), 0600)
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0600)
}
