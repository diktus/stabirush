package web

import (
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
	"wireguard-ui/internal/auth"
)

const (
	tunDeviceName = "stabirush0"
	cIFF_TUN      = 0x0001
	cIFF_NO_PI    = 0x1000
	cTUNSETIFF    = 0x400454ca
)

type ifReq struct {
	Name  [16]byte
	Flags uint16
	pad   [22]byte
}

type tunClientSession struct {
	ip          string
	remoteAddr  string
	conn        net.Conn
	udpConn     *net.UDPConn
	udpAddr     *net.UDPAddr
	isUDP       bool
	connectedAt time.Time
	lastSeen    time.Time
	rxBytes     int64
	txBytes     int64
	writeMu     sync.Mutex
}

func (c *tunClientSession) sendFrame(payload []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	if c.isUDP && c.udpConn != nil && c.udpAddr != nil {
		if len(payload) > 0 {
			pkt := make([]byte, 4+len(payload))
			copy(pkt[0:4], []byte("SBRU"))
			copy(pkt[4:], payload)
			_, err := c.udpConn.WriteToUDP(pkt, c.udpAddr)
			if err == nil {
				atomic.AddInt64(&c.txBytes, int64(len(payload)))
			}
			return err
		}
		_, err := c.udpConn.WriteToUDP([]byte("SBRK"), c.udpAddr)
		return err
	}

	if c.conn != nil {
		_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		frame := make([]byte, 2+len(payload))
		binary.BigEndian.PutUint16(frame[0:2], uint16(len(payload)))
		if len(payload) > 0 {
			copy(frame[2:], payload)
			atomic.AddInt64(&c.txBytes, int64(len(payload)))
		}
		_, err := c.conn.Write(frame)
		return err
	}
	return nil
}

type androidTunBridge struct {
	tunFile *os.File
	mu      sync.RWMutex
	clients map[string]*tunClientSession
	cumRx   map[string]int64
	cumTx   map[string]int64
}

var (
	globalTunBridge *androidTunBridge
	globalAuthMgr   *auth.Manager
	tunOnce         sync.Once
)

func initAndroidTunBridge(mgr ...*auth.Manager) *androidTunBridge {
	if len(mgr) > 0 && mgr[0] != nil {
		globalAuthMgr = mgr[0]
	}
	tunOnce.Do(func() {
		f, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
		if err != nil {
			log.Printf("[tun-bridge] failed to open /dev/net/tun: %v", err)
			return
		}

		var req ifReq
		copy(req.Name[:], tunDeviceName)
		req.Flags = cIFF_TUN | cIFF_NO_PI

		_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(cTUNSETIFF), uintptr(unsafe.Pointer(&req)))
		if errno != 0 {
			log.Printf("[tun-bridge] ioctl TUNSETIFF failed: %v", errno)
			_ = f.Close()
			return
		}

		// Bring up stabirush0 and configure forwarding + NAT + TCP MSS Clamping
		setupCmds := []string{
			"ip addr add 10.10.0.1/32 dev stabirush0 2>/dev/null || true",
			"ip link set dev stabirush0 up mtu 1280",
			"sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true",
			"sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true",
			"sysctl -w net.ipv4.conf.default.rp_filter=0 >/dev/null 2>&1 || true",
			"sysctl -w net.ipv4.conf.stabirush0.rp_filter=0 >/dev/null 2>&1 || true",
			"sysctl -w net.ipv4.conf.wg0.rp_filter=0 >/dev/null 2>&1 || true",
			"iptables -t mangle -C FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || iptables -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu",
			"iptables -t mangle -C OUTPUT -p tcp --tcp-flags SYN,RST SYN -d 10.10.0.0/24 -j TCPMSS --set-mss 1240 2>/dev/null || iptables -t mangle -I OUTPUT 1 -p tcp --tcp-flags SYN,RST SYN -d 10.10.0.0/24 -j TCPMSS --set-mss 1240",
			"iptables -C FORWARD -i stabirush0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i stabirush0 -j ACCEPT",
			"iptables -C FORWARD -o stabirush0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -o stabirush0 -j ACCEPT",
			"iptables -t nat -C POSTROUTING -s 10.10.0.0/24 -o wg0 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -s 10.10.0.0/24 -o wg0 -j MASQUERADE",
			"iptables -t nat -C POSTROUTING -s 10.10.0.0/24 -o enp0s6 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -s 10.10.0.0/24 -o enp0s6 -j MASQUERADE",
		}
		for _, c := range setupCmds {
			_ = exec.Command("sh", "-c", c).Run()
		}

		b := &androidTunBridge{
			tunFile: f,
			clients: make(map[string]*tunClientSession),
			cumRx:   make(map[string]int64),
			cumTx:   make(map[string]int64),
		}
		globalTunBridge = b
		go b.readTunLoop()
		go b.startUDPListener()
		log.Printf("[tun-bridge] Linux TUN interface %s initialized (TCP+UDP)", tunDeviceName)
	})
	return globalTunBridge
}

func (b *androidTunBridge) startUDPListener() {
	addr := &net.UDPAddr{Port: 51821}
	uconn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		log.Printf("[tun-bridge] UDP listener on :51821 failed: %v", err)
		return
	}
	log.Printf("[tun-bridge] UDP tunnel listener active on 0.0.0.0:51821")
	buf := make([]byte, 65535)
	for {
		n, raddr, err := uconn.ReadFromUDP(buf)
		if err != nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		if n < 4 {
			continue
		}
		magic := string(buf[0:4])
		switch magic {
		case "SBRA": // Handshake / Auth Registration
			if n < 5 {
				continue
			}
			tokLen := int(buf[4])
			if n < 5+tokLen {
				continue
			}
			token := string(buf[5 : 5+tokLen])
			clientIP := "10.10.0.3"
			if globalAuthMgr != nil {
				tok, ok := globalAuthMgr.ValidateToken(token)
				if !ok {
					log.Printf("[tun-bridge] UDP auth rejected for token %s from %v", token, raddr)
					continue
				}
				if tok.AllocatedIP != "" {
					clientIP = tok.AllocatedIP
				}
			}
			sess := &tunClientSession{
				ip:          clientIP,
				remoteAddr:  raddr.String(),
				udpConn:     uconn,
				udpAddr:     raddr,
				isUDP:       true,
				connectedAt: time.Now(),
				lastSeen:    time.Now(),
			}
			b.registerSession(clientIP, sess)

			var resp [16]byte
			copy(resp[0:4], []byte("SBRO"))
			parsedIP := net.ParseIP(clientIP).To4()
			if parsedIP != nil {
				copy(resp[4:8], parsedIP)
			}
			gwIP := net.ParseIP("10.10.0.1").To4()
			if gwIP != nil {
				copy(resp[8:12], gwIP)
			}
			binary.BigEndian.PutUint32(resp[12:16], uint32(time.Now().Unix()))
			_, _ = uconn.WriteToUDP(resp[:], raddr)
			log.Printf("[tun-bridge] UDP tunnel handshake verified for client %s from %v", clientIP, raddr)

		case "SBRK": // Keepalive ping
			b.mu.RLock()
			for _, s := range b.clients {
				if s.isUDP && s.udpAddr != nil && s.udpAddr.String() == raddr.String() {
					s.lastSeen = time.Now()
					break
				}
			}
			b.mu.RUnlock()
			_, _ = uconn.WriteToUDP([]byte("SBRK"), raddr)

		case "SBRU": // Data packet
			if n <= 4 {
				continue
			}
			payload := buf[4:n]
			if len(payload) < 20 || (payload[0]>>4) != 4 {
				continue
			}
			srcIP := fmt.Sprintf("%d.%d.%d.%d", payload[12], payload[13], payload[14], payload[15])
			b.mu.RLock()
			sess := b.clients[srcIP]
			b.mu.RUnlock()
			if sess != nil && sess.isUDP {
				sess.udpAddr = raddr
				sess.lastSeen = time.Now()
				atomic.AddInt64(&sess.rxBytes, int64(len(payload)))
			}
			_, _ = b.tunFile.Write(payload)
		}
	}
}

func (b *androidTunBridge) readTunLoop() {
	buf := make([]byte, 32768)
	for {
		n, err := b.tunFile.Read(buf)
		if err != nil {
			if err == io.EOF {
				return
			}
			time.Sleep(20 * time.Millisecond)
			continue
		}
		if n < 20 {
			continue
		}
		version := buf[0] >> 4
		if version != 4 {
			continue
		}
		srcIP := fmt.Sprintf("%d.%d.%d.%d", buf[12], buf[13], buf[14], buf[15])
		dstIP := fmt.Sprintf("%d.%d.%d.%d", buf[16], buf[17], buf[18], buf[19])
		log.Printf("[tun-bridge] TUN stabirush0 read: %s -> %s (%d bytes)", srcIP, dstIP, n)

		b.mu.RLock()
		sess := b.clients[dstIP]
		if sess == nil && len(b.clients) == 1 {
			for _, only := range b.clients {
				sess = only
				break
			}
		}
		b.mu.RUnlock()

		if sess != nil {
			pktCopy := make([]byte, n)
			copy(pktCopy, buf[:n])
			if err := sess.sendFrame(pktCopy); err != nil {
				log.Printf("[tun-bridge] Error sending frame to %s: %v", dstIP, err)
			}
		} else {
			log.Printf("[tun-bridge] Drop return packet: No session found for dstIP=%s", dstIP)
		}
	}
}

func (b *androidTunBridge) registerSession(ip string, sess *tunClientSession) {
	if ip == "" {
		return
	}
	b.mu.Lock()
	b.clients[ip] = sess
	b.mu.Unlock()
	_ = exec.Command("ip", "route", "replace", ip+"/32", "dev", tunDeviceName, "src", "10.10.0.1").Run()
}

func (b *androidTunBridge) unregisterSession(ip string, sess *tunClientSession) {
	if ip == "" {
		return
	}
	b.mu.Lock()
	cur, ok := b.clients[ip]
	if ok && cur == sess {
		if b.cumRx == nil {
			b.cumRx = make(map[string]int64)
		}
		if b.cumTx == nil {
			b.cumTx = make(map[string]int64)
		}
		b.cumRx[ip] += atomic.LoadInt64(&sess.rxBytes)
		b.cumTx[ip] += atomic.LoadInt64(&sess.txBytes)
		delete(b.clients, ip)
		b.mu.Unlock()
		_ = exec.Command("ip", "route", "del", ip+"/32", "dev", tunDeviceName).Run()
		return
	}
	b.mu.Unlock()
}

// GetSessionStats returns live or accumulated bandwidth and status metrics for an IP
func (b *androidTunBridge) GetSessionStats(ip string) (endpoint string, rx int64, tx int64, lastSeen time.Time, isConnected bool, protocol string) {
	if b == nil {
		return "", 0, 0, time.Time{}, false, ""
	}
	b.mu.RLock()
	defer b.mu.RUnlock()

	sess, ok := b.clients[ip]
	var curRx, curTx int64
	if ok && sess != nil {
		lastSeen = sess.lastSeen
		curRx = atomic.LoadInt64(&sess.rxBytes)
		curTx = atomic.LoadInt64(&sess.txBytes)
		endpoint = sess.remoteAddr
		isConnected = time.Since(lastSeen) < 120*time.Second
		protocol = "TCP (TLS)"
		if sess.isUDP {
			protocol = "UDP Tunnel"
		}
	}

	rx = curRx
	tx = curTx
	if b.cumRx != nil {
		rx += b.cumRx[ip]
	}
	if b.cumTx != nil {
		tx += b.cumTx[ip]
	}

	return endpoint, rx, tx, lastSeen, isConnected, protocol
}

// GetAndroidTunnelStats returns active tunnel session metrics for an IP
func GetAndroidTunnelStats(ip string) (endpoint string, rx int64, tx int64, lastSeen time.Time, isConnected bool, protocol string) {
	if globalTunBridge == nil {
		return "", 0, 0, time.Time{}, false, ""
	}
	return globalTunBridge.GetSessionStats(ip)
}

func (s *Server) handleAndroidTunnel(w http.ResponseWriter, r *http.Request) {
	token := r.Header.Get("X-WG-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	tok, ok := s.authMgr.ValidateToken(token)
	if !ok {
		http.Error(w, "Unauthorized: Sesi tidak valid atau telah berakhir. Silakan login kembali.", http.StatusUnauthorized)
		return
	}

	bridge := initAndroidTunBridge()
	if bridge == nil || bridge.tunFile == nil {
		http.Error(w, "TUN bridge unavailable", http.StatusInternalServerError)
		return
	}

	rawIP := strings.TrimSpace(r.URL.Query().Get("ip"))
	if strings.Contains(rawIP, "/") {
		rawIP = strings.Split(rawIP, "/")[0]
	}
	// Securely bind tunnel to the user's authenticated device IP
	if tok.AllocatedIP != "" && tok.Username != "master_admin" {
		rawIP = tok.AllocatedIP
	}
	if net.ParseIP(rawIP) == nil {
		rawIP = "10.10.0.3"
	}

	log.Printf("[tun-bridge] Incoming authenticated tunnel request from %s (user=%s, ip=%s, uri=%s)", r.RemoteAddr, tok.Username, rawIP, r.RequestURI)

	// Also whitelist caller IP for proxy fallback
	remoteIP := extractClientIP(r.RemoteAddr)
	s.proxyMu.Lock()
	s.proxyWhitelist[remoteIP] = time.Now().Add(24 * time.Hour)
	s.proxyMu.Unlock()

	hijacker, ok := w.(http.ResponseWriter).(http.Hijacker)
	if !ok {
		http.Error(w, "Hijacking not supported", http.StatusInternalServerError)
		return
	}

	conn, bufrw, err := hijacker.Hijack()
	if err != nil {
		log.Printf("[tun-bridge] Hijack failed for %s: %v", r.RemoteAddr, err)
		return
	}
	defer conn.Close()

	if tcpConn, ok := conn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
		_ = tcpConn.SetKeepAlive(true)
		_ = tcpConn.SetKeepAlivePeriod(15 * time.Second)
	}
	_ = conn.SetDeadline(time.Time{})

	wsKey := strings.TrimSpace(r.Header.Get("Sec-WebSocket-Key"))
	if wsKey != "" || strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		h := sha1.New()
		h.Write([]byte(wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
		accept := base64.StdEncoding.EncodeToString(h.Sum(nil))
		resp := fmt.Sprintf("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n", accept)
		_, err = conn.Write([]byte(resp))
	} else {
		_, err = conn.Write([]byte("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"))
	}
	if err != nil {
		log.Printf("[tun-bridge] Handshake write failed for %s: %v", r.RemoteAddr, err)
		return
	}
	realClientIP := r.Header.Get("X-Forwarded-For")
	if realClientIP != "" {
		parts := strings.Split(realClientIP, ",")
		realClientIP = strings.TrimSpace(parts[0])
	} else if r.Header.Get("X-Real-IP") != "" {
		realClientIP = strings.TrimSpace(r.Header.Get("X-Real-IP"))
	} else {
		h, _, err := net.SplitHostPort(r.RemoteAddr)
		if err == nil {
			realClientIP = h
		} else {
			realClientIP = r.RemoteAddr
		}
	}

	log.Printf("[tun-bridge] Tunnel established successfully for client %s (%s)", rawIP, realClientIP)

	sess := &tunClientSession{
		ip:          rawIP,
		remoteAddr:  realClientIP,
		conn:        conn,
		connectedAt: time.Now(),
		lastSeen:    time.Now(),
	}
	bridge.registerSession(rawIP, sess)
	var extraIPs []string
	defer func() {
		log.Printf("[tun-bridge] Tunnel session closed for client %s (%s)", rawIP, realClientIP)
		bridge.unregisterSession(rawIP, sess)
		for _, eip := range extraIPs {
			bridge.unregisterSession(eip, sess)
		}
	}()

	var lenBuf [2]byte
	pktBuf := make([]byte, 32768)

	for {
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		if _, err := io.ReadFull(bufrw, lenBuf[:]); err != nil {
			log.Printf("[tun-bridge] ReadFull error from %s: %v", rawIP, err)
			return
		}
		pktLen := int(binary.BigEndian.Uint16(lenBuf[:]))
		if pktLen == 0 {
			sess.lastSeen = time.Now()
			log.Printf("[tun-bridge] Heartbeat keepalive ping received from %s", rawIP)
			_ = sess.sendFrame(nil)
			continue
		}
		if pktLen > len(pktBuf) {
			return
		}
		if _, err := io.ReadFull(bufrw, pktBuf[:pktLen]); err != nil {
			return
		}
		sess.lastSeen = time.Now()
		atomic.AddInt64(&sess.rxBytes, int64(pktLen))
		if pktLen < 20 || (pktBuf[0]>>4) != 4 {
			continue
		}

		srcIP := fmt.Sprintf("%d.%d.%d.%d", pktBuf[12], pktBuf[13], pktBuf[14], pktBuf[15])
		dstIP := fmt.Sprintf("%d.%d.%d.%d", pktBuf[16], pktBuf[17], pktBuf[18], pktBuf[19])
		log.Printf("[tun-bridge] TCP uplink recv from %s: %s -> %s (%d bytes)", rawIP, srcIP, dstIP, pktLen)

		if srcIP != rawIP && strings.HasPrefix(srcIP, "10.10.") {
			known := false
			for _, eip := range extraIPs {
				if eip == srcIP {
					known = true
					break
				}
			}
			if !known {
				extraIPs = append(extraIPs, srcIP)
				bridge.registerSession(srcIP, sess)
			}
		}

		if _, err := bridge.tunFile.Write(pktBuf[:pktLen]); err != nil {
			log.Printf("[tun-bridge] Error writing %d bytes to TUN: %v", pktLen, err)
		}
	}
}
