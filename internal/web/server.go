package web

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"wireguard-ui/internal/auth"
	"wireguard-ui/internal/config"
	"wireguard-ui/internal/wireguard"
)

const (
	DefaultExtensionToken = "stabirush-ext-2026"
	LegacyExtensionToken  = "wg-tailscale-ext-2026"
)

// Server handles HTTP requests and Browser Extension Proxy Tunneling
type Server struct {
	cfg            *config.AppConfig
	mgr            *wireguard.Manager
	authMgr        *auth.Manager
	staticFS       fs.FS
	mux            *http.ServeMux
	proxyMu        sync.RWMutex
	proxyWhitelist map[string]time.Time
}

// NewServer creates a new HTTP server
func NewServer(cfg *config.AppConfig, mgr *wireguard.Manager, staticFS fs.FS) *Server {
	authMgr, _ := auth.NewManager(cfg.DataDir, DefaultExtensionToken)
	if mgr != nil {
		mgr.SetTunnelStatsFunc(GetAndroidTunnelStats)
	}
	s := &Server{
		cfg:            cfg,
		mgr:            mgr,
		authMgr:        authMgr,
		staticFS:       staticFS,
		mux:            http.NewServeMux(),
		proxyWhitelist: make(map[string]time.Time),
	}

	s.routes()
	return s
}

func extractClientIP(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		return remoteAddr
	}
	return host
}

func (s *Server) isPrivateTargetHost(hostPort string) bool {
	host := hostPort
	if h, _, err := net.SplitHostPort(hostPort); err == nil {
		host = h
	}
	if strings.HasPrefix(host, "10.10.0.") ||
		strings.HasPrefix(host, "192.168.101.") ||
		strings.HasPrefix(host, "10.0.0.") {
		return true
	}
	targetIP := net.ParseIP(host)
	if targetIP != nil {
		for _, ns := range s.mgr.GetNamedSubnets() {
			if _, ipNet, err := net.ParseCIDR(ns.CIDR); err == nil && ipNet.Contains(targetIP) {
				return true
			}
		}
	}
	return false
}

func isValidExtToken(tok string) bool {
	return tok == DefaultExtensionToken || tok == LegacyExtensionToken
}

func (s *Server) isProxyAuthorized(r *http.Request, targetHost string) bool {
	if s.isPrivateTargetHost(targetHost) {
		return true
	}
	if isValidExtToken(r.Header.Get("X-WG-Token")) {
		return true
	}
	clientIP := extractClientIP(r.RemoteAddr)
	s.proxyMu.RLock()
	exp, ok := s.proxyWhitelist[clientIP]
	s.proxyMu.RUnlock()
	return ok && time.Now().Before(exp)
}

func (s *Server) isOwnWebUIRequest(r *http.Request) bool {
	if r.Method == http.MethodConnect {
		return false
	}
	if r.URL.Host != "" {
		_, port, err := net.SplitHostPort(r.URL.Host)
		if err != nil || port != s.cfg.WebPort {
			return false
		}
		h, _, _ := net.SplitHostPort(r.URL.Host)
		if h != "10.10.0.1" && h != "127.0.0.1" && h != "localhost" && !strings.HasPrefix(s.cfg.ServerEndpoint, h+":") {
			return false
		}
	}
	if r.Host != "" {
		_, port, err := net.SplitHostPort(r.Host)
		if err != nil && s.isPrivateTargetHost(r.Host) {
			return false
		}
		if err == nil && port != s.cfg.WebPort {
			return false
		}
	}
	return true
}

// Handler returns the http.Handler with middleware and Proxy Gateway
func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 1. Check if this is a Browser Extension Proxy / CONNECT Tunnel request
		if !s.isOwnWebUIRequest(r) {
			s.handleProxyRequest(w, r)
			return
		}

		// 2. Allow CORS preflight for /api/extension/ and /api/android/
		if strings.HasPrefix(r.URL.Path, "/api/extension/") || strings.HasPrefix(r.URL.Path, "/api/android/") {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-WG-Token")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}

		// 3. Basic Auth check if configured (skip for extension ping & android endpoints)
		if s.cfg.AuthUsername != "" && s.cfg.AuthPassword != "" &&
			r.URL.Path != "/api/extension/ping" &&
			!strings.HasPrefix(r.URL.Path, "/api/auth/") &&
			!strings.HasPrefix(r.URL.Path, "/api/android/") {
			user, pass, ok := r.BasicAuth()
			if !ok || subtle.ConstantTimeCompare([]byte(user), []byte(s.cfg.AuthUsername)) != 1 ||
				subtle.ConstantTimeCompare([]byte(pass), []byte(s.cfg.AuthPassword)) != 1 {
				w.Header().Set("WWW-Authenticate", `Basic realm="Stabirush WireGuard Management"`)
				http.Error(w, "Unauthorized", http.StatusUnauthorized)
				return
			}
		}

		// Security headers
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("X-XSS-Protection", "1; mode=block")

		s.mux.ServeHTTP(w, r)
	})
}

func (s *Server) handleProxyRequest(w http.ResponseWriter, r *http.Request) {
	targetHost := r.Host
	if r.URL.Host != "" {
		targetHost = r.URL.Host
	}

	if !s.isProxyAuthorized(r, targetHost) {
		http.Error(w, "Proxy access forbidden: please enable the Stabirush Browser Extension", http.StatusForbidden)
		return
	}

	// HTTPS / TCP Tunnel (CONNECT)
	if r.Method == http.MethodConnect {
		destAddr := targetHost
		if _, _, err := net.SplitHostPort(destAddr); err != nil {
			destAddr = net.JoinHostPort(destAddr, "443")
		}

		destConn, err := net.DialTimeout("tcp", destAddr, 10*time.Second)
		if err != nil {
			http.Error(w, "Failed to connect to target: "+err.Error(), http.StatusBadGateway)
			return
		}

		hijacker, ok := w.(http.Hijacker)
		if !ok {
			destConn.Close()
			http.Error(w, "Hijacking not supported", http.StatusInternalServerError)
			return
		}

		clientConn, _, err := hijacker.Hijack()
		if err != nil {
			destConn.Close()
			return
		}

		_ = clientConn.SetDeadline(time.Time{})
		_, _ = clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

		go func() {
			defer destConn.Close()
			defer clientConn.Close()
			_, _ = io.Copy(destConn, clientConn)
		}()
		go func() {
			defer destConn.Close()
			defer clientConn.Close()
			_, _ = io.Copy(clientConn, destConn)
		}()
		return
	}

	// Standard HTTP Proxy forwarding
	outReq := r.Clone(r.Context())
	if outReq.URL.Scheme == "" {
		outReq.URL.Scheme = "http"
	}
	if outReq.URL.Host == "" {
		outReq.URL.Host = r.Host
	}
	outReq.RequestURI = ""
	outReq.Header.Del("Proxy-Connection")
	outReq.Header.Del("Proxy-Authorization")

	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ResponseHeaderTimeout: 30 * time.Second,
	}

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		http.Error(w, "Bad Gateway: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func (s *Server) routes() {
	// Authentication & User Management Endpoints
	s.mux.HandleFunc("/api/auth/login", s.handleAuthLogin)
	s.mux.HandleFunc("/api/auth/logout", s.handleAuthLogout)
	s.mux.HandleFunc("/api/auth/me", s.handleAuthMe)
	s.mux.HandleFunc("/api/users", s.handleUsers)
	s.mux.HandleFunc("/api/users/update", s.handleUserUpdate)
	s.mux.HandleFunc("/api/users/delete", s.handleUserDelete)
	s.mux.HandleFunc("/api/users/tokens", s.handleUserTokens)
	s.mux.HandleFunc("/api/users/tokens/revoke", s.handleUserTokenRevoke)

	// API Endpoints
	s.mux.HandleFunc("/api/status", s.handleStatus)
	s.mux.HandleFunc("/api/devices", s.handleDevices)
	s.mux.HandleFunc("/api/devices/next-ip", s.handleNextIP)
	s.mux.HandleFunc("/api/device", s.handleDeviceQuery)
	s.mux.HandleFunc("/api/device/config", s.handleDeviceConfigQuery)
	s.mux.HandleFunc("/api/device/custom-client", s.handleDeviceCustomClient)
	s.mux.HandleFunc("/api/device/rekey", s.handleDeviceRekey)
	s.mux.HandleFunc("/api/devices/", s.handleDeviceDetail)
	s.mux.HandleFunc("/api/subnets", s.handleSubnets)
	s.mux.HandleFunc("/api/named-subnets", s.handleNamedSubnets)
	s.mux.HandleFunc("/api/bypass", s.handleBypass)
	s.mux.HandleFunc("/api/config/raw", s.handleRawConfig)
	s.mux.HandleFunc("/api/settings", s.handleSettings)
	s.mux.HandleFunc("/api/extension/ping", s.handleExtensionPing)
	s. mux.HandleFunc("/api/android/pac", s.handleAndroidPAC)
	s.mux.HandleFunc("/api/android/sync", s.handleAndroidSync)
	s.mux.HandleFunc("/api/android/tunnel", s.handleAndroidTunnel)
	initAndroidTunBridge(s.authMgr)

	// Static assets
	fileServer := http.FileServer(http.FS(s.staticFS))
	s.mux.Handle("/static/", http.StripPrefix("/static/", fileServer))
	s.mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		data, err := fs.ReadFile(s.staticFS, "index.html")
		if err != nil {
			http.Error(w, "index.html not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(data)
	})
}

func cidrToWildcardPattern(cidr string) string {
	cidr = strings.TrimSpace(cidr)
	if cidr == "" {
		return ""
	}
	parts := strings.Split(cidr, "/")
	ipParts := strings.Split(parts[0], ".")
	if len(ipParts) != 4 {
		return ""
	}
	if len(parts) == 2 && parts[1] == "32" {
		return parts[0]
	}
	if len(parts) == 2 && parts[1] == "16" {
		return fmt.Sprintf("%s.%s.*", ipParts[0], ipParts[1])
	}
	return fmt.Sprintf("%s.%s.%s.*", ipParts[0], ipParts[1], ipParts[2])
}

func (s *Server) handleAndroidPAC(w http.ResponseWriter, r *http.Request) {
	clientIP := extractClientIP(r.RemoteAddr)
	s.proxyMu.Lock()
	s.proxyWhitelist[clientIP] = time.Now().Add(24 * time.Hour)
	s.proxyMu.Unlock()

	proxyHost := r.URL.Query().Get("proxy")
	if proxyHost == "" {
		proxyHost = "168.110.193.226:" + s.cfg.WebPort
	}
	mode := r.URL.Query().Get("mode")
	subnetsParam := strings.TrimSpace(r.URL.Query().Get("subnets"))

	w.Header().Set("Content-Type", "application/x-ns-proxy-autoconfig")
	if mode == "secure" || mode == "full" {
		pac := fmt.Sprintf(`function FindProxyForURL(url, host) {
  if (host === "localhost" || host === "127.0.0.1" || host === "168.110.193.226") {
    return "DIRECT";
  }
  return "PROXY %s; DIRECT";
}`, proxyHost)
		_, _ = w.Write([]byte(pac))
		return
	}

	var conds []string
	conds = append(conds, `shExpMatch(host, "10.10.0.1")`)
	if subnetsParam != "" && subnetsParam != "*" {
		for _, sub := range strings.Split(subnetsParam, ",") {
			pat := cidrToWildcardPattern(sub)
			if pat != "" {
				conds = append(conds, fmt.Sprintf(`shExpMatch(host, %q)`, pat))
			}
		}
	} else {
		for _, ns := range s.mgr.GetNamedSubnets() {
			pat := cidrToWildcardPattern(ns.CIDR)
			if pat != "" {
				conds = append(conds, fmt.Sprintf(`shExpMatch(host, %q)`, pat))
			}
		}
	}
	conds = append(conds, `shExpMatch(host, "*.stabirush")`, `shExpMatch(host, "*.wg")`)

	pac := fmt.Sprintf(`function FindProxyForURL(url, host) {
  if (%s) {
    return "PROXY %s; DIRECT";
  }
  return "DIRECT";
}`, strings.Join(conds, " || "), proxyHost)
	_, _ = w.Write([]byte(pac))
}

// Authentication Handlers
func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Username   string `json:"username"`
		Password   string `json:"password"`
		DeviceName string `json:"deviceName"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
		return
	}

	devices, _ := s.mgr.GetDevices()
	var matchedIP, matchedPubKey string
	for _, d := range devices {
		if req.DeviceName != "" && (strings.Contains(strings.ToLower(d.Name), strings.ToLower(req.DeviceName)) || strings.Contains(strings.ToLower(req.DeviceName), strings.ToLower(d.Name))) {
			matchedIP = d.AllocatedIP
			matchedPubKey = d.PublicKey
			break
		}
	}
	if matchedIP == "" {
		for _, d := range devices {
			if strings.Contains(strings.ToLower(d.Name), "phone") || d.OS == "android" {
				matchedIP = d.AllocatedIP
				matchedPubKey = d.PublicKey
				break
			}
		}
	}
	if matchedIP == "" && len(devices) > 0 {
		matchedIP = "10.10.0.3"
	}

	tok, err := s.authMgr.Authenticate(req.Username, req.Password, req.DeviceName, matchedIP, matchedPubKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
		"token":   tok.Token,
		"user": map[string]interface{}{
			"id":       tok.UserID,
			"username": tok.Username,
		},
		"device": map[string]interface{}{
			"name":        tok.DeviceName,
			"allocatedIP": tok.AllocatedIP,
			"publicKey":   tok.PublicKey,
		},
	})
}

func (s *Server) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	token := r.Header.Get("X-WG-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	s.authMgr.RevokeToken(token)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
		"message": "Berhasil logout",
	})
}

func (s *Server) handleAuthMe(w http.ResponseWriter, r *http.Request) {
	token := r.Header.Get("X-WG-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	tok, ok := s.authMgr.ValidateToken(token)
	if !ok {
		writeError(w, http.StatusUnauthorized, "Sesi tidak valid atau telah berakhir")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
		"user": map[string]interface{}{
			"id":       tok.UserID,
			"username": tok.Username,
		},
		"device": map[string]interface{}{
			"name":        tok.DeviceName,
			"allocatedIP": tok.AllocatedIP,
			"publicKey":   tok.PublicKey,
		},
	})
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, s.authMgr.GetUsers())

	case http.MethodPost:
		var req struct {
			Username  string   `json:"username"`
			Password  string   `json:"password"`
			Role      string   `json:"role"`
			DeviceIPs []string `json:"deviceIps"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}
		u, err := s.authMgr.CreateUser(req.Username, req.Password, req.Role, req.DeviceIPs)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusCreated, u)

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleUserUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	var req struct {
		Username  string   `json:"username"`
		Password  string   `json:"password"`
		Role      string   `json:"role"`
		DeviceIPs []string `json:"deviceIps"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
		return
	}
	u, err := s.authMgr.UpdateUser(req.Username, req.Password, req.Role, req.DeviceIPs)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, u)
}

func (s *Server) handleUserDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	var req struct {
		Username string `json:"username"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.Username == "" {
		req.Username = r.URL.Query().Get("username")
	}
	if req.Username == "" {
		writeError(w, http.StatusBadRequest, "Missing username")
		return
	}
	if err := s.authMgr.DeleteUser(req.Username); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted", "username": req.Username})
}

func (s *Server) handleUserTokens(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.authMgr.GetTokens())
}

func (s *Server) handleUserTokenRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	var req struct {
		Token string `json:"token"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.Token == "" {
		req.Token = r.URL.Query().Get("token")
	}
	if req.Token == "" {
		writeError(w, http.StatusBadRequest, "Missing token")
		return
	}
	if ok := s.authMgr.RevokeToken(req.Token); !ok {
		writeError(w, http.StatusNotFound, "Token not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "revoked", "token": req.Token})
}

// handleAndroidSync provides two-way cloud synchronization between the Web UI and Native Android APK
func (s *Server) handleAndroidSync(w http.ResponseWriter, r *http.Request) {
	token := r.Header.Get("X-WG-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	tok, ok := s.authMgr.ValidateToken(token)
	if !ok {
		http.Error(w, "Unauthorized: Sesi tidak valid atau telah berakhir. Silakan login kembali.", http.StatusUnauthorized)
		return
	}

	clientIP := extractClientIP(r.RemoteAddr)
	s.proxyMu.Lock()
	s.proxyWhitelist[clientIP] = time.Now().Add(24 * time.Hour)
	s.proxyMu.Unlock()

	if r.Method == http.MethodPost || r.Method == http.MethodPut {
		var payload struct {
			PublicKey     string                      `json:"publicKey"`
			RoutingMode   string                      `json:"routingMode"`
			TransportMode string                      `json:"transportMode"`
			SplitSubnets  []string                    `json:"splitSubnets"`
			WifiRules     []wireguard.WifiProfileRule `json:"wifiRules"`
			NamedSubnets  []wireguard.NamedSubnet     `json:"namedSubnets"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid sync JSON: "+err.Error())
			return
		}
		if len(payload.NamedSubnets) > 0 {
			_ = s.mgr.SaveNamedSubnets(payload.NamedSubnets)
		}
		if payload.PublicKey != "" {
			devices, _ := s.mgr.GetDevices()
			for _, d := range devices {
				if d.PublicKey == payload.PublicKey {
					_ = s.mgr.UpdateDeviceWithSubnets(
						d.PublicKey,
						d.Name,
						d.OS,
						d.AllowedIPs,
						d.Enabled,
						payload.RoutingMode,
						payload.TransportMode,
						payload.SplitSubnets,
						payload.WifiRules,
					)
					break
				}
			}
		}
	}

	devices, err := s.mgr.GetDevices()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	namedSubnets := s.mgr.GetNamedSubnets()
	bypass := s.mgr.GetBypassStatus()

	reqKey := strings.TrimSpace(r.URL.Query().Get("key"))
	reqIP := strings.TrimSpace(r.URL.Query().Get("ip"))

	var activeDev *wireguard.Device
	for _, d := range devices {
		cleanAlloc := d.AllocatedIP
		if strings.Contains(cleanAlloc, "/") {
			cleanAlloc = strings.Split(cleanAlloc, "/")[0]
		}
		if tok.AllocatedIP != "" && cleanAlloc == tok.AllocatedIP {
			activeDev = d
			break
		}
		if tok.PublicKey != "" && d.PublicKey == tok.PublicKey {
			activeDev = d
			break
		}
		if reqKey != "" && d.PublicKey == reqKey {
			activeDev = d
			break
		}
		if reqIP != "" && (d.AllocatedIP == reqIP || cleanAlloc == reqIP) {
			activeDev = d
			break
		}
	}
	if activeDev == nil {
		// Pick an android device if present, or fallback to first non-router peer
		for _, d := range devices {
			if d.OS == "android" || d.OS == "ios" {
				activeDev = d
				break
			}
		}
		if activeDev == nil && len(devices) > 0 {
			activeDev = devices[0]
		}
	}

	serverHost := "168.110.193.226"
	if h, _, err := net.SplitHostPort(s.cfg.ServerEndpoint); err == nil && h != "" {
		serverHost = h
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "synced",
		"configVersion":  s.mgr.GetConfigVersion(),
		"serverHost":     serverHost,
		"serverEndpoint": s.cfg.ServerEndpoint,
		"serverPublicKey": s.mgr.GetServerPublicKey(),
		"webPort":        s.cfg.WebPort,
		"tailnetName":    s.cfg.TailnetName,
		"defaultDns":     s.cfg.DefaultDNS,
		"defaultMtu":     s.cfg.DefaultMTU,
		"bypass":         bypass,
		"namedSubnets":   namedSubnets,
		"activeDevice":   activeDev,
		"currentUser": map[string]any{
			"username": tok.Username,
			"userId":   tok.UserID,
			"device":   tok.DeviceName,
		},
	})
}

func (s *Server) handleNamedSubnets(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, s.mgr.GetNamedSubnets())

	case http.MethodPost, http.MethodPut:
		var payload struct {
			Action      string                  `json:"action"` // "upsert" or "replace"
			Subnet      wireguard.NamedSubnet   `json:"subnet"`
			SubnetsList []wireguard.NamedSubnet `json:"subnets"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}
		if payload.Action == "replace" && len(payload.SubnetsList) > 0 {
			if err := s.mgr.SaveNamedSubnets(payload.SubnetsList); err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			writeJSON(w, http.StatusOK, s.mgr.GetNamedSubnets())
			return
		}
		list, err := s.mgr.UpsertNamedSubnet(payload.Subnet)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, list)

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		if id == "" {
			id = r.URL.Query().Get("cidr")
		}
		list, err := s.mgr.DeleteNamedSubnet(id)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, list)

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleExtensionPing(w http.ResponseWriter, r *http.Request) {
	token := r.Header.Get("X-WG-Token")
	if !isValidExtToken(token) {
		writeError(w, http.StatusUnauthorized, "Invalid Stabirush extension token")
		return
	}

	clientIP := extractClientIP(r.RemoteAddr)
	s.proxyMu.Lock()
	s.proxyWhitelist[clientIP] = time.Now().Add(24 * time.Hour)
	s.proxyMu.Unlock()

	patterns := []string{"10.10.0.*", "192.168.101.*", "10.0.0.*"}
	for _, ns := range s.mgr.GetNamedSubnets() {
		pat := cidrToWildcardPattern(ns.CIDR)
		if pat != "" {
			found := false
			for _, p := range patterns {
				if p == pat {
					found = true
					break
				}
			}
			if !found {
				patterns = append(patterns, pat)
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "authorized",
		"app":           "Stabirush",
		"clientIP":      clientIP,
		"patterns":      patterns,
		"namedSubnets":  s.mgr.GetNamedSubnets(),
		"configVersion": s.mgr.GetConfigVersion(),
	})
}

// JSON helpers
func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

var nonAlphaNum = regexp.MustCompile(`[^a-zA-Z0-9_-]+`)

func safeFilename(name, pubKey, mode, transport string) string {
	clean := strings.Trim(nonAlphaNum.ReplaceAllString(strings.ToLower(name), "-"), "-")
	if clean == "" {
		if len(pubKey) >= 8 {
			clean = strings.ReplaceAll(pubKey[:8], "/", "_")
		} else {
			clean = "peer"
		}
	}
	suffix := ""
	if mode == "secure" || mode == "full" {
		suffix += "-secure"
	}
	if transport == "wstunnel" || transport == "udp2raw" {
		suffix += "-" + transport
	}
	return fmt.Sprintf("stabirush-%s%s.conf", clean, suffix)
}

// API Handlers
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	status, err := s.mgr.GetServerStatus()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (s *Server) handleBypass(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.mgr.EnsureBypassServices()
		writeJSON(w, http.StatusOK, s.mgr.GetBypassStatus())

	case http.MethodPost:
		var payload struct {
			Action          string `json:"action"` // "save", "restart"
			WstunnelEnabled *bool  `json:"wstunnelEnabled"`
			WstunnelPort    int    `json:"wstunnelPort"`
			WstunnelPrefix  string `json:"wstunnelPrefix"`
			Udp2rawEnabled  *bool  `json:"udp2rawEnabled"`
			Udp2rawPort     int    `json:"udp2rawPort"`
			Udp2rawPassword string `json:"udp2rawPassword"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}

		wsEnabled := s.cfg.WstunnelEnabled
		if payload.WstunnelEnabled != nil {
			wsEnabled = *payload.WstunnelEnabled
		}
		u2rEnabled := s.cfg.Udp2rawEnabled
		if payload.Udp2rawEnabled != nil {
			u2rEnabled = *payload.Udp2rawEnabled
		}

		if err := s.cfg.UpdateBypass(wsEnabled, payload.WstunnelPort, payload.WstunnelPrefix, u2rEnabled, payload.Udp2rawPort, payload.Udp2rawPassword); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}

		s.mgr.BumpConfigVersion()
		s.mgr.RestartBypassServices()
		writeJSON(w, http.StatusOK, s.mgr.GetBypassStatus())

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		devices, err := s.mgr.GetDevices()
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, devices)

	case http.MethodPost:
		var req wireguard.CreateDeviceRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid request JSON: "+err.Error())
			return
		}

		resp, err := s.mgr.CreateDevice(req)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusCreated, resp)

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleNextIP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	ip, err := s.mgr.GetNextAvailableIP()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"nextIP": ip})
}

// handleDeviceConfigQuery handles GET /api/device/config?key=<pubkey>&mode=<split|secure>&transport=<udp|wstunnel|udp2raw>&subnets=<cidr1,cidr2>&download=true&name=<name>
func (s *Server) handleDeviceConfigQuery(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	pubKey := r.URL.Query().Get("key")
	if pubKey == "" {
		writeError(w, http.StatusBadRequest, "Missing key parameter")
		return
	}

	mode := r.URL.Query().Get("mode")
	transport := r.URL.Query().Get("transport")
	subnetsRaw := strings.TrimSpace(r.URL.Query().Get("subnets"))
	var customSubnets []string
	if subnetsRaw != "" {
		for _, part := range strings.Split(subnetsRaw, ",") {
			if p := strings.TrimSpace(part); p != "" {
				customSubnets = append(customSubnets, p)
			}
		}
	}

	confText, err := s.mgr.GetDeviceConfigWithSubnets(pubKey, mode, transport, customSubnets)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	if r.URL.Query().Get("download") == "true" {
		name := r.URL.Query().Get("name")
		filename := safeFilename(name, pubKey, mode, transport)
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
		w.Header().Set("Content-Type", "application/x-wireguard-profile")
	} else {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	}
	_, _ = w.Write([]byte(confText))
}

// handleDeviceCustomClient handles GET /api/device/custom-client?key=<pubkey>&platform=<linux|macos|windows|ios>
func (s *Server) handleDeviceCustomClient(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	pubKey := r.URL.Query().Get("key")
	if pubKey == "" {
		writeError(w, http.StatusBadRequest, "Missing key parameter")
		return
	}
	platform := r.URL.Query().Get("platform")
	if platform == "" {
		platform = "linux"
	}

	scriptContent, filename, err := s.mgr.GenerateCustomClientScript(pubKey, platform)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
	if platform == "ios" || platform == "mobileconfig" {
		w.Header().Set("Content-Type", "application/x-apple-aspen-config")
	} else {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	}
	_, _ = w.Write([]byte(scriptContent))
}

// handleDeviceQuery handles PUT and DELETE on /api/device?key=<pubkey>
func (s *Server) handleDeviceQuery(w http.ResponseWriter, r *http.Request) {
	pubKey := r.URL.Query().Get("key")

	switch r.Method {
	case http.MethodPut:
		var payload struct {
			PublicKey     string                      `json:"publicKey"`
			Name          string                      `json:"name"`
			OS            string                      `json:"os"`
			AllowedIPs    string                      `json:"allowedIPs"`
			Enabled       *bool                       `json:"enabled"`
			RoutingMode   string                      `json:"routingMode"`
			TransportMode string                      `json:"transportMode"`
			SplitSubnets  []string                    `json:"splitSubnets"`
			WifiRules     []wireguard.WifiProfileRule `json:"wifiRules"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}
		if pubKey == "" {
			pubKey = payload.PublicKey
		}
		if pubKey == "" {
			writeError(w, http.StatusBadRequest, "Missing public key")
			return
		}

		enabledVal := true
		if payload.Enabled != nil {
			enabledVal = *payload.Enabled
		}

		if err := s.mgr.UpdateDeviceWithSubnets(pubKey, payload.Name, payload.OS, payload.AllowedIPs, enabledVal, payload.RoutingMode, payload.TransportMode, payload.SplitSubnets, payload.WifiRules); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "updated", "configVersion": s.mgr.GetConfigVersion()})

	case http.MethodDelete:
		if pubKey == "" {
			writeError(w, http.StatusBadRequest, "Missing key parameter")
			return
		}
		if err := s.mgr.DeleteDevice(pubKey); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

// handleDeviceRekey handles POST /api/device/rekey?key=<pubkey>
func (s *Server) handleDeviceRekey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	pubKey := r.URL.Query().Get("key")
	if pubKey == "" {
		var payload struct {
			PublicKey string `json:"publicKey"`
		}
		_ = json.NewDecoder(r.Body).Decode(&payload)
		pubKey = payload.PublicKey
	}
	if pubKey == "" {
		writeError(w, http.StatusBadRequest, "Missing public key")
		return
	}

	resp, err := s.mgr.RegenerateDeviceKey(pubKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleDeviceDetail provides backwards compatibility for /api/devices/{pubkey}[/config]
func (s *Server) handleDeviceDetail(w http.ResponseWriter, r *http.Request) {
	rawPath := r.URL.EscapedPath()
	subPath := strings.TrimPrefix(rawPath, "/api/devices/")
	if subPath == "" {
		writeError(w, http.StatusBadRequest, "Missing public key")
		return
	}

	isConfig := false
	if strings.HasSuffix(subPath, "/config") {
		isConfig = true
		subPath = strings.TrimSuffix(subPath, "/config")
	}

	pubKey, err := url.PathUnescape(subPath)
	if err != nil || pubKey == "" {
		pubKey = subPath
	}

	if len(pubKey) == 43 && strings.HasSuffix(pubKey, "=") {
		pubKey = "/" + pubKey
	}

	if isConfig {
		if r.Method != http.MethodGet {
			writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
			return
		}
		mode := r.URL.Query().Get("mode")
		transport := r.URL.Query().Get("transport")
		confText, err := s.mgr.GetDeviceConfigWithOptions(pubKey, mode, transport)
		if err != nil {
			writeError(w, http.StatusNotFound, err.Error())
			return
		}

		if r.URL.Query().Get("download") == "true" {
			name := r.URL.Query().Get("name")
			filename := safeFilename(name, pubKey, mode, transport)
			w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
			w.Header().Set("Content-Type", "application/x-wireguard-profile")
		} else {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		}
		_, _ = w.Write([]byte(confText))
		return
	}

	switch r.Method {
	case http.MethodPut:
		var payload struct {
			Name          string                      `json:"name"`
			OS            string                      `json:"os"`
			AllowedIPs    string                      `json:"allowedIPs"`
			Enabled       *bool                       `json:"enabled"`
			RoutingMode   string                      `json:"routingMode"`
			TransportMode string                      `json:"transportMode"`
			SplitSubnets  []string                    `json:"splitSubnets"`
			WifiRules     []wireguard.WifiProfileRule `json:"wifiRules"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}

		enabledVal := true
		if payload.Enabled != nil {
			enabledVal = *payload.Enabled
		}

		if err := s.mgr.UpdateDeviceWithSubnets(pubKey, payload.Name, payload.OS, payload.AllowedIPs, enabledVal, payload.RoutingMode, payload.TransportMode, payload.SplitSubnets, payload.WifiRules); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "updated", "configVersion": s.mgr.GetConfigVersion()})

	case http.MethodDelete:
		if err := s.mgr.DeleteDevice(pubKey); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleSubnets(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
		return
	}
	subnets, err := s.mgr.GetSubnets()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, subnets)
}

func (s *Server) handleRawConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		data, err := os.ReadFile(s.cfg.WGConfigFile)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"content": string(data)})

	case http.MethodPost:
		var payload struct {
			Content string `json:"content"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}

		if _, err := os.Stat(s.cfg.WGConfigFile); err == nil {
			_ = copyFile(s.cfg.WGConfigFile, s.cfg.WGConfigFile+".bak_manual")
		}

		if err := os.WriteFile(s.cfg.WGConfigFile, []byte(payload.Content), 0600); err != nil {
			writeError(w, http.StatusInternalServerError, "Failed to write config: "+err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "saved"})

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, s.cfg)

	case http.MethodPost:
		var payload struct {
			ServerEndpoint   string `json:"serverEndpoint"`
			DefaultDNS       string `json:"defaultDns"`
			DefaultMTU       string `json:"defaultMtu"`
			DefaultKeepalive int    `json:"defaultKeepalive"`
			TailnetName      string `json:"tailnetName"`
			AuthUsername     string `json:"authUsername"`
			AuthPassword     string `json:"authPassword"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			writeError(w, http.StatusBadRequest, "Invalid JSON: "+err.Error())
			return
		}

		if err := s.cfg.Update(
			payload.ServerEndpoint,
			payload.DefaultDNS,
			payload.DefaultMTU,
			payload.TailnetName,
			payload.DefaultKeepalive,
			payload.AuthUsername,
			payload.AuthPassword,
		); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}

		writeJSON(w, http.StatusOK, s.cfg)

	default:
		writeError(w, http.StatusMethodNotAllowed, "Method not allowed")
	}
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0600)
}
