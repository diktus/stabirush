package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// User represents a Stabirush account
type User struct {
	ID           string   `json:"id"`
	Username     string   `json:"username"`
	PasswordHash string   `json:"passwordHash"`
	Role         string   `json:"role"` // "admin" or "user"
	DeviceIPs    []string `json:"deviceIps"`
	CreatedAt    string   `json:"createdAt"`
}

// DeviceToken represents an active session token bound to a specific user and device
type DeviceToken struct {
	Token       string    `json:"token"`
	UserID      string    `json:"userId"`
	Username    string    `json:"username"`
	DeviceName  string    `json:"deviceName"`
	AllocatedIP string    `json:"allocatedIp"`
	PublicKey   string    `json:"publicKey"`
	CreatedAt   time.Time `json:"createdAt"`
	LastUsedAt  time.Time `json:"lastUsedAt"`
}

// Manager manages user accounts and per-device authentication tokens
type Manager struct {
	dataDir     string
	mu          sync.RWMutex
	users       map[string]*User        // keyed by username
	tokens      map[string]*DeviceToken // keyed by token string
	masterToken string
}

// HashPassword hashes a plain text password with a fixed salt
func HashPassword(plain string) string {
	h := sha256.New()
	h.Write([]byte("stabirush_salt_2026:" + plain))
	return hex.EncodeToString(h.Sum(nil))
}

// NewManager initializes the AuthManager and loads users & tokens from disk
func NewManager(dataDir, masterToken string) (*Manager, error) {
	if dataDir == "" {
		dataDir = "/home/wireguard-ui/data"
	}
	_ = os.MkdirAll(dataDir, 0755)

	m := &Manager{
		dataDir:     dataDir,
		users:       make(map[string]*User),
		tokens:      make(map[string]*DeviceToken),
		masterToken: masterToken,
	}

	m.loadUsers()
	m.loadTokens()
	return m, nil
}

func (m *Manager) loadUsers() {
	m.mu.Lock()
	defer m.mu.Unlock()

	path := filepath.Join(m.dataDir, "users.json")
	data, err := os.ReadFile(path)
	if err == nil {
		var list []*User
		if err := json.Unmarshal(data, &list); err == nil && len(list) > 0 {
			for _, u := range list {
				m.users[strings.ToLower(u.Username)] = u
			}
			return
		}
	}

	// Initialize default admin account if users.json does not exist
	initPass := os.Getenv("STABIRUSH_ADMIN_PASSWORD")
	if initPass == "" {
		b := make([]byte, 8)
		_, _ = rand.Read(b)
		initPass = hex.EncodeToString(b)
		log.Printf("[Bootstrap] Initial admin created with username 'admin' and temporary password: %s", initPass)
	}

	defaultUsers := []*User{
		{
			ID:           "usr_admin",
			Username:     "admin",
			PasswordHash: HashPassword(initPass),
			Role:         "admin",
			DeviceIPs:    []string{"10.10.0.2", "10.10.0.3", "10.10.0.4"},
			CreatedAt:    time.Now().Format("2006-01-02 15:04"),
		},
	}

	for _, u := range defaultUsers {
		m.users[strings.ToLower(u.Username)] = u
	}
	m.saveUsersLocked()
}

func (m *Manager) saveUsersLocked() {
	var list []*User
	for _, u := range m.users {
		list = append(list, u)
	}
	bytes, err := json.MarshalIndent(list, "", "  ")
	if err == nil {
		_ = os.WriteFile(filepath.Join(m.dataDir, "users.json"), bytes, 0600)
	}
}

func (m *Manager) loadTokens() {
	m.mu.Lock()
	defer m.mu.Unlock()

	path := filepath.Join(m.dataDir, "device_tokens.json")
	data, err := os.ReadFile(path)
	if err == nil {
		var list []*DeviceToken
		if err := json.Unmarshal(data, &list); err == nil {
			for _, t := range list {
				m.tokens[t.Token] = t
			}
		}
	}
}

func (m *Manager) saveTokensLocked() {
	var list []*DeviceToken
	for _, t := range m.tokens {
		list = append(list, t)
	}
	bytes, err := json.MarshalIndent(list, "", "  ")
	if err == nil {
		_ = os.WriteFile(filepath.Join(m.dataDir, "device_tokens.json"), bytes, 0600)
	}
}

// Authenticate verifies username and password, generates a per-device session token
func (m *Manager) Authenticate(username, password, deviceName string, defaultIP, defaultPubKey string) (*DeviceToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	u := m.users[strings.ToLower(strings.TrimSpace(username))]
	if u == nil {
		return nil, errors.New("Pengguna tidak ditemukan")
	}

	if HashPassword(password) != u.PasswordHash {
		return nil, errors.New("Password salah")
	}

	// Generate random 24-byte hex token (sbr_dev_...)
	rawBytes := make([]byte, 24)
	if _, err := rand.Read(rawBytes); err != nil {
		return nil, err
	}
	tokenStr := fmt.Sprintf("sbr_dev_%s", hex.EncodeToString(rawBytes))

	assignedIP := defaultIP
	if assignedIP == "" && len(u.DeviceIPs) > 0 {
		assignedIP = u.DeviceIPs[0]
	}
	if assignedIP == "" {
		assignedIP = "10.10.0.3"
	}

	dName := deviceName
	if dName == "" {
		dName = "Android Device (" + u.Username + ")"
	}

	tok := &DeviceToken{
		Token:       tokenStr,
		UserID:      u.ID,
		Username:    u.Username,
		DeviceName:  dName,
		AllocatedIP: assignedIP,
		PublicKey:   defaultPubKey,
		CreatedAt:   time.Now(),
		LastUsedAt:  time.Now(),
	}

	m.tokens[tokenStr] = tok
	m.saveTokensLocked()
	return tok, nil
}

// ValidateToken checks if a token is valid, updating its last used timestamp
func (m *Manager) ValidateToken(token string) (*DeviceToken, bool) {
	if token == "" {
		return nil, false
	}

	// Support master extension token for backward compatibility and admin tools
	if m.masterToken != "" && token == m.masterToken {
		return &DeviceToken{
			Token:       token,
			UserID:      "usr_master",
			Username:    "master_admin",
			DeviceName:  "Master Extension",
			AllocatedIP: "10.10.0.3",
			CreatedAt:   time.Now(),
			LastUsedAt:  time.Now(),
		}, true
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	tok, ok := m.tokens[token]
	if !ok {
		return nil, false
	}

	tok.LastUsedAt = time.Now()
	return tok, true
}

// RevokeToken removes an active device token
func (m *Manager) RevokeToken(token string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.tokens[token]; ok {
		delete(m.tokens, token)
		m.saveTokensLocked()
		return true
	}
	return false
}

// GetUsers returns a copy of all users without sensitive hashes
func (m *Manager) GetUsers() []*User {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var list []*User
	for _, u := range m.users {
		copyU := *u
		copyU.PasswordHash = "" // never expose password hashes
		list = append(list, &copyU)
	}
	return list
}

// CreateUser creates a new user account with hashed password
func (m *Manager) CreateUser(username, password, role string, deviceIPs []string) (*User, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	if username == "" {
		return nil, errors.New("Username tidak boleh kosong")
	}
	if len(password) < 4 {
		return nil, errors.New("Password minimal 4 karakter")
	}
	if role != "admin" {
		role = "user"
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.users[username]; exists {
		return nil, fmt.Errorf("User '%s' sudah terdaftar", username)
	}

	var cleanIPs []string
	for _, ip := range deviceIPs {
		ip = strings.TrimSpace(ip)
		if ip != "" {
			cleanIPs = append(cleanIPs, ip)
		}
	}

	rawID := make([]byte, 4)
	_, _ = rand.Read(rawID)
	userID := fmt.Sprintf("usr_%s_%s", username, hex.EncodeToString(rawID))

	newUser := &User{
		ID:           userID,
		Username:     username,
		PasswordHash: HashPassword(password),
		Role:         role,
		DeviceIPs:    cleanIPs,
		CreatedAt:    time.Now().Format("2006-01-02 15:04"),
	}

	m.users[username] = newUser
	m.saveUsersLocked()

	resp := *newUser
	resp.PasswordHash = ""
	return &resp, nil
}

// UpdateUser modifies role, deviceIPs, and optionally changes password
func (m *Manager) UpdateUser(username, newPassword, role string, deviceIPs []string) (*User, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	m.mu.Lock()
	defer m.mu.Unlock()

	u, exists := m.users[username]
	if !exists {
		return nil, fmt.Errorf("User '%s' tidak ditemukan", username)
	}

	if role == "admin" || role == "user" {
		u.Role = role
	}

	if newPassword != "" {
		if len(newPassword) < 4 {
			return nil, errors.New("Password baru minimal 4 karakter")
		}
		u.PasswordHash = HashPassword(newPassword)
	}

	var cleanIPs []string
	for _, ip := range deviceIPs {
		ip = strings.TrimSpace(ip)
		if ip != "" {
			cleanIPs = append(cleanIPs, ip)
		}
	}
	u.DeviceIPs = cleanIPs

	m.saveUsersLocked()

	resp := *u
	resp.PasswordHash = ""
	return &resp, nil
}

// DeleteUser removes a user account and revokes all active device tokens
func (m *Manager) DeleteUser(username string) error {
	username = strings.ToLower(strings.TrimSpace(username))
	m.mu.Lock()
	defer m.mu.Unlock()

	if username == "admin" {
		return errors.New("Akun admin utama tidak boleh dihapus")
	}

	if _, exists := m.users[username]; !exists {
		return fmt.Errorf("User '%s' tidak ditemukan", username)
	}

	delete(m.users, username)
	m.saveUsersLocked()

	// Revoke all tokens belonging to this user
	var toDelete []string
	for tokStr, t := range m.tokens {
		if strings.ToLower(t.Username) == username {
			toDelete = append(toDelete, tokStr)
		}
	}
	for _, tokStr := range toDelete {
		delete(m.tokens, tokStr)
	}
	if len(toDelete) > 0 {
		m.saveTokensLocked()
	}

	return nil
}

// GetTokens returns all active session tokens
func (m *Manager) GetTokens() []*DeviceToken {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var list []*DeviceToken
	for _, t := range m.tokens {
		list = append(list, t)
	}
	return list
}
