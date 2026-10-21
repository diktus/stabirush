package network.stabirush.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.wireguard.android.backend.GoBackend
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StabirushVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "network.stabirush.vpn.CONNECT"
        const val ACTION_DISCONNECT = "network.stabirush.vpn.DISCONNECT"
        const val ACTION_STATUS_BROADCAST = "network.stabirush.vpn.STATUS"
        const val ACTION_SYNC_BROADCAST = "network.stabirush.vpn.SYNC_UPDATED"

        private const val CHANNEL_ID = "stabirush_vpn_channel"
        private const val NOTIF_ID = 1010
        const val PREFS_NAME = "stabirush_prefs"

        @Volatile var isRunning: Boolean = false
        @Volatile var activeRoutingMode: String = "split"     // "split" or "secure" (0.0.0.0/0)
        @Volatile var activeTransport: String = "auto"        // "auto", "udp", "tcp", "tcp_fallback"
        @Volatile var activeAssignedIp: String = "10.10.0.3"
        @Volatile var activeServerHost: String = "vpn.jogjacloud.com"
        @Volatile var activeSubnetsCidr: String = "10.10.0.0/24,192.168.101.0/24,10.0.0.0/24"
        @Volatile var activeSubnetNames: String = "Semua Subnet Private"
        @Volatile var lastSyncedVersion: Long = 0
        @Volatile var lastPingMs: Int = -1

        // Real-time Traffic and Two-Way Handshake Verification
        @Volatile var isVerified: Boolean = false
        @Volatile var isFallbackActive: Boolean = false
        val txBytes = AtomicLong(0)
        val rxBytes = AtomicLong(0)
        val txPackets = AtomicLong(0)
        val rxPackets = AtomicLong(0)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    @Volatile private var currentTunnelSocket: Socket? = null
    @Volatile private var currentWgHandle: Int = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_DISCONNECT == intent.action) {
            stopVpn()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val serverHost = intent?.getStringExtra("serverHost") ?: prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        val assignedIp = intent?.getStringExtra("assignedIp") ?: prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        val routingMode = intent?.getStringExtra("routingMode") ?: prefs.getString("routingMode", "split") ?: "split"
        val transportMode = intent?.getStringExtra("transportMode") ?: prefs.getString("transportMode", "auto") ?: "auto"
        val subnetsCidr = intent?.getStringExtra("activeSubnets") ?: prefs.getString("activeSubnets", "10.10.0.0/24,192.168.101.0/24,10.0.0.0/24") ?: "10.10.0.0/24,192.168.101.0/24,10.0.0.0/24"
        val subnetNames = intent?.getStringExtra("activeSubnetNames") ?: prefs.getString("activeSubnetNames", "Semua Subnet Private") ?: "Semua Subnet Private"

        startVpn(serverHost, assignedIp, routingMode, transportMode, subnetsCidr, subnetNames)
        return START_STICKY
    }

    private fun startVpn(
        serverHost: String,
        assignedIp: String,
        routingMode: String,
        requestedTransport: String,
        subnetsCidr: String,
        subnetNames: String
    ) {
        stopVpnInternal()

        activeServerHost = serverHost
        activeAssignedIp = assignedIp
        activeRoutingMode = routingMode
        activeTransport = requestedTransport
        activeSubnetsCidr = if (subnetsCidr.isBlank()) "10.10.0.0/24,192.168.101.0/24,10.0.0.0/24" else subnetsCidr.trim()
        activeSubnetNames = if (subnetNames.isBlank()) "Semua Subnet Private" else subnetNames.trim()

        createNotificationChannel()
        val initNotif = buildNotification("Menghubungkan ke Stabirush (${routingMode.uppercase()})...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, initNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, initNotif)
        }

        tunnelThread = Thread({
            try {
                val resolvedHost = if (serverHost == "10.10.0.1" || serverHost.isBlank()) "vpn.jogjacloud.com" else serverHost
                val cleanHost = resolvedHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val cleanIp = assignedIp.split("/")[0].trim()
                val effectiveIp = if (cleanIp.isNotEmpty()) cleanIp else "10.10.0.3"

                // 1. Resolve server IP address BEFORE establishing tun0 (avoids DNS loop inside tun0)
                val serverIp = try {
                    if (cleanHost.matches(Regex("^[0-9.]+$"))) {
                        cleanHost
                    } else {
                        InetAddress.getByName(cleanHost).hostAddress ?: "168.110.193.226"
                    }
                } catch (e: Exception) {
                    Log.w("Stabirush", "Failed to resolve $cleanHost, using fallback IP 168.110.193.226: ${e.message}")
                    "168.110.193.226"
                }
                Log.d("Stabirush", "Resolved serverIp before TUN: cleanHost=$cleanHost -> serverIp=$serverIp")

                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val devToken = prefs.getString("authToken", "") ?: ""
                val tokenToSend = devToken

                // Periodic Live Stats Broadcaster (every 1 second)
                val statsBroadcaster = Thread({
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(1000)
                            if (!isRunning) break
                            broadcastStatus(if (isVerified) "Connected" else "Memverifikasi...")
                        } catch (ie: InterruptedException) {
                            break
                        } catch (ignored: Exception) {}
                    }
                }, "StabirushStatsBroadcaster")
                statsBroadcaster.isDaemon = true
                statsBroadcaster.start()

                // Background Live Cloud Sync & keepalive checker
                val syncThread = Thread({
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(15000)
                            if (!isRunning) break
                            checkServerLiveSync(resolvedHost, effectiveIp)
                        } catch (ie: InterruptedException) {
                            break
                        } catch (ignored: Exception) {}
                    }
                }, "StabirushSyncThread")
                syncThread.isDaemon = true
                syncThread.start()

                isRunning = true
                val isExplicitTcp = (requestedTransport == "wstunnel" || requestedTransport == "tcp")

                if (isExplicitTcp) {
                    // Explicit TCP/TLS 443 Tunnel Mode
                    activeTransport = "tcp"
                    isFallbackActive = false
                    updateNotification("Menghubungkan ke TCP 443 (TLS)...")
                    broadcastStatus("Menghubungkan ke TCP 443...")
                    Log.d("Stabirush", "Explicit TCP mode requested. Establishing TUN for TCP 443...")

                    val builder = Builder()
                    val sLabel = if (routingMode == "secure") "Secure 0.0.0.0/0 (TCP)" else "Split: $activeSubnetNames (TCP)"
                    builder.setSession("Stabirush · $sLabel")
                    builder.setMtu(1420)
                    builder.addAddress(effectiveIp, 32)

                    if (routingMode.equals("secure", ignoreCase = true) || routingMode.equals("full", ignoreCase = true)) {
                        builder.addRoute("0.0.0.0", 0)
                        builder.addDnsServer("10.0.0.154")
                        builder.addDnsServer("1.1.1.1")
                    } else {
                        addSplitSubnetRoutes(builder, activeSubnetsCidr)
                        builder.addRoute("10.10.0.1", 32)
                        builder.addRoute("10.0.0.154", 32)
                        builder.addRoute("1.1.1.1", 32)
                        builder.addRoute("8.8.8.8", 32)
                        builder.addDnsServer("10.0.0.154")
                        builder.addDnsServer("1.1.1.1")
                    }

                    val pfd = builder.establish()
                    if (pfd == null) {
                        isRunning = false
                        broadcastStatus("Permission ditolak atau gagal membuat tun0")
                        stopSelf()
                        return@Thread
                    }
                    vpnInterface = pfd
                    val tunIn = FileInputStream(pfd.fileDescriptor)
                    val tunOut = FileOutputStream(pfd.fileDescriptor)

                    runTcpTunnelLoop(cleanHost, serverIp, effectiveIp, routingMode, tokenToSend, tunIn, tunOut)
                } else {
                    // Default WireGuard UDP 51820 Mode
                    activeTransport = "wireguard"
                    isFallbackActive = false
                    val wgSuccess = runWireguardTunnel(serverIp, effectiveIp, routingMode, requestedTransport == "auto")

                    if (!wgSuccess && isRunning) {
                        if (requestedTransport == "wireguard" || requestedTransport == "udp") {
                            broadcastStatus("WireGuard gagal: pastikan jaringan mendukung UDP 51820")
                            return@Thread
                        }

                        // Auto-fallback to TCP/TLS 443
                        isFallbackActive = true
                        activeTransport = "tcp_fallback"
                        updateNotification("⚠️ WireGuard UDP RX=0 (terblokir ISP) · Beralih ke TCP 443...")
                        broadcastStatus("WireGuard terblokir -> Fallback ke TCP 443...")
                        Log.d("Stabirush", "WireGuard failed or blocked. Establishing TUN for TCP 443 fallback...")

                        val builder = Builder()
                        builder.setSession("Stabirush · " + if (routingMode == "secure") "Secure 0.0.0.0/0 (TCP Fallback)" else "Split: $activeSubnetNames")
                        builder.setMtu(1420)
                        builder.addAddress(effectiveIp, 32)

                        if (routingMode.equals("secure", ignoreCase = true) || routingMode.equals("full", ignoreCase = true)) {
                            builder.addRoute("0.0.0.0", 0)
                            builder.addDnsServer("10.0.0.154")
                            builder.addDnsServer("1.1.1.1")
                        } else {
                            addSplitSubnetRoutes(builder, activeSubnetsCidr)
                            builder.addRoute("10.10.0.1", 32)
                            builder.addRoute("10.0.0.154", 32)
                            builder.addRoute("1.1.1.1", 32)
                            builder.addRoute("8.8.8.8", 32)
                            builder.addDnsServer("10.0.0.154")
                            builder.addDnsServer("1.1.1.1")
                        }

                        val pfd = builder.establish()
                        if (pfd == null) {
                            isRunning = false
                            broadcastStatus("Permission ditolak atau gagal membuat tun0")
                            stopSelf()
                            return@Thread
                        }
                        vpnInterface = pfd
                        val tunIn = FileInputStream(pfd.fileDescriptor)
                        val tunOut = FileOutputStream(pfd.fileDescriptor)

                        runTcpTunnelLoop(cleanHost, serverIp, effectiveIp, routingMode, tokenToSend, tunIn, tunOut)
                    }
                }
            } catch (e: Exception) {
                Log.e("Stabirush", "Tunnel thread exception: ${e.message}", e)
                if (isRunning) {
                    broadcastStatus("Tunnel terhenti: ${e.message}")
                }
            } finally {
                Log.d("Stabirush", "Tunnel thread finished, stopping VPN...")
                stopVpnInternal()
                broadcastStatus("Disconnected")
            }
        }, "StabirushTunnelThread")
        tunnelThread?.start()
    }

    private fun runWireguardTunnel(
        serverIp: String,
        cleanIp: String,
        routingMode: String,
        isAutoMode: Boolean
    ): Boolean {
        if (!GoBackend.isLibraryLoaded()) {
            Log.e("Stabirush", "GoBackend native library is not loaded!")
            return false
        }

        updateNotification("Mengaktifkan Standalone WireGuard (UDP 51820)...")
        broadcastStatus("Mengaktifkan WireGuard Engine...")

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val privKey = prefs.getString("clientPrivateKey", "") ?: ""
        val effectivePrivKey = if (privKey.isNotEmpty()) privKey else "IEyVQmA+ijVcRkF2Ba3LY2d3/eu4HvrcBYD2ZfXz8UU="
        val serverPubKey = prefs.getString("serverPublicKey", "") ?: ""
        val effectiveServerPubKey = if (serverPubKey.isNotEmpty()) serverPubKey else "J6gLYFFjvZ8WKyvej80RRTET4ygNWkdmaJjOQSou8UM="

        val clientPrivHex = WireGuardHelper.base64ToHex(effectivePrivKey)
        val serverPubHex = WireGuardHelper.base64ToHex(effectiveServerPubKey)

        if (clientPrivHex.length != 64 || serverPubHex.length != 64) {
            Log.e("Stabirush", "Invalid key length for WireGuard UAPI: priv=${clientPrivHex.length}, pub=${serverPubHex.length}")
            return false
        }

        val serverEndpoint = "$serverIp:51820"
        val isSecure = routingMode.equals("secure", ignoreCase = true) || routingMode.equals("full", ignoreCase = true)

        val allowedIps = if (isSecure) {
            listOf("0.0.0.0/0")
        } else {
            val list = mutableListOf("10.10.0.0/24")
            for (sub in activeSubnetsCidr.split(",")) {
                val s = sub.trim()
                if (s.isNotEmpty() && !list.contains(s)) list.add(s)
            }
            if (!list.contains("10.0.0.0/24")) list.add("10.0.0.0/24")
            list
        }

        val uapiConfig = WireGuardHelper.buildUapiConfig(
            clientPrivateKeyHex = clientPrivHex,
            serverPublicKeyHex = serverPubHex,
            serverEndpoint = serverEndpoint,
            allowedIps = allowedIps,
            keepalive = 25
        )
        Log.d("Stabirush", "Building Standalone WireGuard tunnel with UAPI config (endpoint=$serverEndpoint, isSecure=$isSecure)...")

        val builder = Builder()
        val sessionName = "Stabirush · " + if (isSecure) "Secure (0.0.0.0/0)" else "Split: $activeSubnetNames"
        builder.setSession(sessionName)
        builder.setMtu(1280)
        builder.addAddress(cleanIp, 32)

        if (isSecure) {
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("10.10.0.1")
            builder.addDnsServer("1.1.1.1")
        } else {
            addSplitSubnetRoutes(builder, activeSubnetsCidr)
            builder.addRoute("10.10.0.1", 32)
            builder.addRoute("10.0.0.154", 32)
            builder.addDnsServer("10.10.0.1")
        }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e("Stabirush", "VpnService.Builder establish() failed (null)")
            return false
        }
        vpnInterface = pfd

        // Duplicate the TUN fd to avoid calling pfd.detachFd() which triggers Android 15/16 fdsan abort
        val dupFd = Os.dup(pfd.fileDescriptor)
        val fdField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
        fdField.isAccessible = true
        val tunFd = fdField.getInt(dupFd)

        val handle = GoBackend.wgTurnOn("stabirush0", tunFd, uapiConfig)
        if (handle < 0) {
            Log.e("Stabirush", "GoBackend.wgTurnOn failed with error code: $handle")
            try {
                Os.close(dupFd)
            } catch (ignored: Exception) {}
            return false
        }
        currentWgHandle = handle
        Log.d("Stabirush", "WireGuard GoBackend initialized successfully! handle=$handle")

        // Socket protection is crucial to prevent routing loops
        val sockV4 = GoBackend.wgGetSocketV4(handle)
        if (sockV4 >= 0) {
            val pOk = protect(sockV4)
            Log.d("Stabirush", "Protected WireGuard IPv4 socket fd $sockV4: $pOk")
        }
        val sockV6 = GoBackend.wgGetSocketV6(handle)
        if (sockV6 >= 0) {
            protect(sockV6)
        }

        // Wait and verify handshake
        var verified = false
        var latency = -1
        for (attempt in 1..25) {
            if (!isRunning) return false
            try { Thread.sleep(200) } catch (ie: InterruptedException) { return false }

            val uapiOutput = GoBackend.wgGetConfig(handle)
            val stats = WireGuardHelper.parseUapiStats(uapiOutput)
            if (stats.rxBytes > 0 || stats.lastHandshakeSec > 0) {
                verified = true
                Log.d("Stabirush", "WireGuard handshake confirmed on attempt $attempt! RX: ${stats.rxBytes}, Handshake: ${stats.lastHandshakeSec}")
                break
            }
        }

        if (!verified && isAutoMode) {
            Log.w("Stabirush", "WireGuard GoBackend could not verify handshake (RX=0) in auto mode, falling back to TCP...")
            if (currentWgHandle >= 0) {
                val h = currentWgHandle
                currentWgHandle = -1
                GoBackend.wgTurnOff(h)
            }
            return false
        }

        // Successfully verified WireGuard 2-way data!
        isRunning = true
        isVerified = true
        isFallbackActive = false
        activeTransport = "wireguard"
        if (latency > 0) lastPingMs = latency

        val mLabel = if (isSecure) "🔒 WireGuard (0.0.0.0/0)" else "Split: $activeSubnetNames"
        updateNotification("$mLabel · ⚡ Standalone WG · $cleanIp")
        broadcastStatus("Connected")

        // Monitoring loop while WireGuard is connected
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(2000)
                if (!isRunning) break

                val uapiOutput = GoBackend.wgGetConfig(handle)
                val stats = WireGuardHelper.parseUapiStats(uapiOutput)
                if (stats.rxBytes > 0) {
                    rxBytes.set(stats.rxBytes)
                    txBytes.set(stats.txBytes)
                }

                val currentLat = testWireGuardReachability()
                if (currentLat >= 0) {
                    lastPingMs = currentLat
                } else if (isAutoMode && stats.rxBytes == 0L) {
                    Log.w("Stabirush", "WireGuard reachability lost during active session!")
                    if (currentWgHandle >= 0) {
                        val h = currentWgHandle
                        currentWgHandle = -1
                        GoBackend.wgTurnOff(h)
                    }
                    return false // Triggers automatic fallback to TCP
                }
            } catch (ie: InterruptedException) {
                break
            } catch (ignored: Exception) {}
        }
        return true
    }

    private fun testWireGuardReachability(): Int {
        val targets = listOf(
            Pair("10.0.0.154", 8090),
            Pair("10.10.0.1", 51821),
            Pair("10.0.0.154", 53)
        )
        for ((host, port) in targets) {
            val t0 = System.currentTimeMillis()
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 800)
                s.close()
                return (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
            } catch (ignored: Exception) {}
        }
        return -1
    }

    private fun runTcpTunnelLoop(
        cleanHost: String,
        serverIp: String,
        cleanIp: String,
        routingMode: String,
        token: String,
        tunIn: FileInputStream,
        tunOut: FileOutputStream
    ) {
        val tunReadBuf = ByteArray(32767)
        var tunClosed = false

        while (isRunning && !tunClosed && !Thread.currentThread().isInterrupted) {
            var channel: java.nio.channels.SocketChannel? = null
            var sock: Socket? = null
            val sessionAlive = AtomicBoolean(true)
            try {
                val tStart = System.currentTimeMillis()
                val isDomain = cleanHost.contains(".") && !cleanHost.matches(Regex("^[0-9.]+$"))
                val targetPort = if (isDomain) 443 else 80

                channel = java.nio.channels.SocketChannel.open()
                channel.configureBlocking(true)
                val baseSock = channel.socket()
                baseSock.tcpNoDelay = true
                baseSock.keepAlive = true
                protect(baseSock)

                Log.d("Stabirush", "Connecting TCP socket to $serverIp:$targetPort (SNI=$cleanHost)...")
                baseSock.connect(InetSocketAddress(serverIp, targetPort), 7000)
                Log.d("Stabirush", "TCP connected! Initiating SSL...")

                val finalSock: Socket = if (isDomain) {
                    val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    val ssl = sslFactory.createSocket(baseSock, cleanHost, targetPort, true) as javax.net.ssl.SSLSocket
                    ssl.startHandshake()
                    Log.d("Stabirush", "SSL handshake complete!")
                    ssl
                } else {
                    baseSock
                }
                sock = finalSock
                currentTunnelSocket = finalSock

                val rawOut = BufferedOutputStream(finalSock.getOutputStream(), 65536)
                val rawIn = BufferedInputStream(finalSock.getInputStream(), 65536)

                val wsKey = "c3RhYmlydXNoMjAyNg=="
                val req = "GET /api/android/tunnel?ip=" + URLEncoder.encode(cleanIp, "UTF-8") +
                        "&mode=" + URLEncoder.encode(routingMode, "UTF-8") +
                        " HTTP/1.1\r\nHost: " + cleanHost + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "X-WG-Token: " + token + "\r\n\r\n"
                val reqBytes = req.toByteArray(Charsets.UTF_8)
                rawOut.write(reqBytes)
                rawOut.flush()
                txPackets.incrementAndGet()
                txBytes.addAndGet(reqBytes.size.toLong())

                val (respBytes, statusLine) = readHttpHeaders(rawIn)
                Log.d("Stabirush", "TCP Handshake status: $statusLine")
                if (!statusLine.contains("101") && !statusLine.contains("200")) {
                    throw Exception("Handshake gagal: $statusLine")
                }
                lastPingMs = (System.currentTimeMillis() - tStart).toInt()
                rxPackets.incrementAndGet()
                rxBytes.addAndGet(respBytes.toLong())

                // TCP two-way handshake verified!
                isVerified = true
                val mLabel = if (routingMode.equals("secure", ignoreCase = true)) "🔒 Secure (0.0.0.0/0)" else "Split ($activeSubnetNames)"
                val tLabel = if (isFallbackActive) "🛡️ TCP:443 (Fallback)" else "⚡ TCP:443"
                updateNotification("$mLabel · $tLabel · $cleanIp")
                broadcastStatus("Connected")

                val writeLock = Any()

                // Downlink thread: Server (stabirush0) -> Android (tun0)
                val downlink = Thread({
                    val dis = DataInputStream(rawIn)
                    val pkt = ByteArray(32768)
                    try {
                        while (isRunning && sessionAlive.get()) {
                            val len = dis.readUnsignedShort()
                            if (len == 0) {
                                // Keepalive pong
                                rxPackets.incrementAndGet()
                                continue
                            }
                            if (len > pkt.size) break
                            dis.readFully(pkt, 0, len)
                            val rx = rxPackets.incrementAndGet()
                            rxBytes.addAndGet(len.toLong())
                            if (rx <= 10 || rx % 50 == 0L) {
                                val sIp = "${pkt[12].toUByte()}.${pkt[13].toUByte()}.${pkt[14].toUByte()}.${pkt[15].toUByte()}"
                                val dIp = "${pkt[16].toUByte()}.${pkt[17].toUByte()}.${pkt[18].toUByte()}.${pkt[19].toUByte()}"
                                Log.d("Stabirush", "DL #$rx: $sIp -> $dIp ($len bytes)")
                            }
                            try {
                                synchronized(tunOut) {
                                    tunOut.write(pkt, 0, len)
                                }
                            } catch (we: Exception) {
                                Log.w("Stabirush", "tunOut write packet warning: ${we.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Stabirush", "TCP Downlink loop terminated: ${e.message}")
                        sessionAlive.set(false)
                        try { sock?.close() } catch (ignored: Exception) {}
                    }
                }, "StabirushTcpDownlink")
                downlink.isDaemon = true
                downlink.start()

                // Heartbeat thread every 10 seconds
                val keepalive = Thread({
                    try {
                        while (isRunning && sessionAlive.get()) {
                            Thread.sleep(10000)
                            if (!isRunning || !sessionAlive.get()) break
                            synchronized(writeLock) {
                                rawOut.write(0)
                                rawOut.write(0)
                                rawOut.flush()
                            }
                            txPackets.incrementAndGet()
                            txBytes.addAndGet(2)
                        }
                    } catch (e: Exception) {
                        Log.e("Stabirush", "TCP Keepalive terminated: ${e.message}")
                        sessionAlive.set(false)
                        try { sock?.close() } catch (ignored: Exception) {}
                    }
                }, "StabirushTcpKeepalive")
                keepalive.isDaemon = true
                keepalive.start()

                // Uplink loop: Android (tun0) -> Server (stabirush0)
                while (isRunning && sessionAlive.get() && !tunClosed && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        tunIn.read(tunReadBuf)
                    } catch (e: Exception) {
                        Log.d("Stabirush", "tunIn read terminated: ${e.message}")
                        tunClosed = true
                        -1
                    }
                    if (n > 0) {
                        if ((tunReadBuf[0].toInt() shr 4) != 4) {
                            continue
                        }
                        val tx = txPackets.incrementAndGet()
                        txBytes.addAndGet(n.toLong())
                        if (tx <= 10 || tx % 50 == 0L) {
                            val sIp = "${tunReadBuf[12].toUByte()}.${tunReadBuf[13].toUByte()}.${tunReadBuf[14].toUByte()}.${tunReadBuf[15].toUByte()}"
                            val dIp = "${tunReadBuf[16].toUByte()}.${tunReadBuf[17].toUByte()}.${tunReadBuf[18].toUByte()}.${tunReadBuf[19].toUByte()}"
                            Log.d("Stabirush", "UL #$tx: $sIp -> $dIp ($n bytes)")
                        }
                        try {
                            synchronized(writeLock) {
                                rawOut.write((n ushr 8) and 0xFF)
                                rawOut.write(n and 0xFF)
                                rawOut.write(tunReadBuf, 0, n)
                                rawOut.flush()
                            }
                        } catch (e: Exception) {
                            Log.e("Stabirush", "TCP Uplink write error: ${e.message}")
                            sessionAlive.set(false)
                            break
                        }
                    } else if (n == 0) {
                        Thread.sleep(10)
                    } else {
                        tunClosed = true
                        break
                    }
                }
            } catch (e: Exception) {
                sessionAlive.set(false)
                Log.w("Stabirush", "TCP session exception: ${e.message}")
            } finally {
                sessionAlive.set(false)
                try {
                    sock?.close()
                } catch (ignored: Exception) {}
                try {
                    channel?.close()
                } catch (ignored: Exception) {}
            }

            if (tunClosed || Thread.currentThread().isInterrupted || !isRunning) {
                Log.d("Stabirush", "Exiting TCP tunnel loop cleanly (tunClosed=$tunClosed, isRunning=$isRunning)")
                break
            }

            // If still running, pause briefly and auto-reconnect the tunnel socket
            if (isRunning) {
                broadcastStatus("Reconnecting tunnel...")
                try {
                    Thread.sleep(1500)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun readHttpHeaders(input: InputStream): Pair<Int, String> {
        var state = 0
        var count = 0
        val sb = StringBuilder()
        var firstLine = ""
        var inFirstLine = true
        while (state < 4) {
            val b = input.read()
            if (b == -1) throw Exception("EOF membaca handshake tunnel")
            count++
            val c = b.toChar()
            if (inFirstLine) {
                if (c == '\r' || c == '\n') {
                    if (sb.isNotEmpty()) {
                        firstLine = sb.toString()
                        inFirstLine = false
                    }
                } else {
                    sb.append(c)
                }
            }
            if ((state == 0 || state == 2) && b == '\r'.code) {
                state++
            } else if ((state == 1 || state == 3) && b == '\n'.code) {
                state++
            } else {
                state = if (b == '\r'.code) 1 else 0
            }
        }
        return Pair(count, firstLine)
    }

    private fun addSplitSubnetRoutes(builder: Builder, subnetsCsv: String) {
        var hasMesh = false
        var hasCore = false
        val parts = subnetsCsv.split(",")
        for (raw in parts) {
            val cidr = raw.trim()
            if (cidr.isEmpty() || cidr == "*") continue
            try {
                var ipPart = cidr
                var prefix = 24
                if (cidr.contains("/")) {
                    val sp = cidr.split("/")
                    ipPart = sp[0].trim()
                    prefix = sp[1].trim().toInt()
                }
                val netAddr = normalizeNetworkAddress(ipPart, prefix)
                builder.addRoute(netAddr, prefix)
                if (netAddr == "10.10.0.0" && prefix <= 24) {
                    hasMesh = true
                }
                if (netAddr == "10.0.0.0" && prefix <= 24) {
                    hasCore = true
                }
            } catch (ignored: Exception) {}
        }
        if (!hasMesh) {
            try {
                builder.addRoute("10.10.0.0", 24)
            } catch (ignored: Exception) {}
        }
        if (!hasCore) {
            try {
                builder.addRoute("10.0.0.0", 24)
            } catch (ignored: Exception) {}
        }
    }

    private fun normalizeNetworkAddress(ip: String, prefix: Int): String {
        val octets = ip.split(".")
        if (octets.size != 4) return ip
        try {
            val o0 = octets[0].toInt()
            val o1 = octets[1].toInt()
            val o2 = octets[2].toInt()
            val o3 = octets[3].toInt()
            val addr = ((o0 and 0xFF) shl 24) or ((o1 and 0xFF) shl 16) or ((o2 and 0xFF) shl 8) or (o3 and 0xFF)
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val net = addr and mask
            return "${(net ushr 24) and 0xFF}.${(net ushr 16) and 0xFF}.${(net ushr 8) and 0xFF}.${net and 0xFF}"
        } catch (e: Exception) {
            return ip
        }
    }

    private fun checkServerLiveSync(serverHost: String, assignedIp: String) {
        var conn: HttpURLConnection? = null
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val pubKey = prefs.getString("selectedPubKey", "") ?: ""
            val hosts = if (serverHost == "10.10.0.1") arrayOf("10.10.0.1", "vpn.jogjacloud.com") else arrayOf(serverHost, "10.10.0.1")
            var ok = false
            val devToken = prefs.getString("authToken", "") ?: ""
            val tokenToSend = devToken

            for (h in hosts) {
                try {
                    val cleanH = h.removePrefix("https://").removePrefix("http://").trimEnd('/')
                    val proto = if (cleanH.matches(Regex("^[0-9.]+$"))) "http" else "https"
                    val urlStr = "$proto://$cleanH/api/android/sync?key=" +
                            URLEncoder.encode(pubKey, "UTF-8") +
                            "&ip=" + URLEncoder.encode(assignedIp, "UTF-8")
                    conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.setRequestProperty("X-WG-Token", tokenToSend)
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    if (conn.responseCode == 200) {
                        ok = true
                        break
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {
                    conn?.disconnect()
                }
            }
            if (!ok || conn == null) return

            val br = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) sb.append(line)
            br.close()

            val root = JSONObject(sb.toString())
            val serverVer = root.optLong("configVersion", 0L)
            val localVer = prefs.getLong("configVersion", 0L)

            if (serverVer > 0L && serverVer != localVer) {
                lastSyncedVersion = serverVer
                val ed = prefs.edit()
                ed.putLong("configVersion", serverVer)

                val namedArr = root.optJSONArray("namedSubnets")
                if (namedArr != null) {
                    ed.putString("namedSubnetsJson", namedArr.toString())
                }

                val activeDev = root.optJSONObject("activeDevice")
                if (activeDev != null) {
                    val rulesArr = activeDev.optJSONArray("wifiRules")
                    if (rulesArr != null) {
                        ed.putString("wifiRulesJson", rulesArr.toString())
                    }
                    val pk = activeDev.optString("publicKey", "")
                    if (pk.isNotEmpty()) ed.putString("selectedPubKey", pk)
                    val pName = activeDev.optString("name", "")
                    if (pName.isNotEmpty()) ed.putString("selectedPeerName", pName)
                }
                ed.apply()

                val syncBroadcast = Intent(ACTION_SYNC_BROADCAST)
                syncBroadcast.setPackage(packageName)
                syncBroadcast.putExtra("configVersion", serverVer)
                sendBroadcast(syncBroadcast)
            }
        } catch (ignored: Exception) {
        } finally {
            conn?.disconnect()
        }
    }

    private fun stopVpn() {
        stopVpnInternal()
        stopForeground(true)
        stopSelf()
        broadcastStatus("Disconnected")
    }

    private fun stopVpnInternal() {
        val wasRunning = isRunning
        isRunning = false
        isVerified = false
        isFallbackActive = false
        lastPingMs = -1

        // Turn off standalone WireGuard Go backend
        if (currentWgHandle >= 0) {
            val h = currentWgHandle
            currentWgHandle = -1
            Log.d("Stabirush", "Turning off standalone WireGuard GoBackend handle: $h")
            try {
                GoBackend.wgTurnOff(h)
            } catch (t: Throwable) {
                Log.e("Stabirush", "Error turning off WireGuard handle $h: ${t.message}")
            }
        }

        try {
            currentTunnelSocket?.close()
        } catch (ignored: Exception) {}
        currentTunnelSocket = null

        tunnelThread?.interrupt()
        tunnelThread = null

        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {}
        vpnInterface = null
    }

    private fun broadcastStatus(msg: String) {
        val i = Intent(ACTION_STATUS_BROADCAST)
        i.setPackage(packageName)
        i.putExtra("status", msg)
        i.putExtra("running", isRunning)
        i.putExtra("routingMode", activeRoutingMode)
        i.putExtra("transportMode", activeTransport)
        i.putExtra("activeSubnets", activeSubnetsCidr)
        i.putExtra("activeSubnetNames", activeSubnetNames)
        i.putExtra("pingMs", lastPingMs)
        i.putExtra("txBytes", txBytes.get())
        i.putExtra("rxBytes", rxBytes.get())
        i.putExtra("txPackets", txPackets.get())
        i.putExtra("rxPackets", rxPackets.get())
        i.putExtra("isVerified", isVerified)
        i.putExtra("isFallbackActive", isFallbackActive)
        sendBroadcast(i)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Stabirush WireGuard VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, openIntent, flags)

        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return nb.setContentTitle("Stabirush WireGuard VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }
}
