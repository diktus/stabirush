package network.stabirush.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 101
        private const val REQ_LOC_PERMISSION = 102
    }

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var routingMode: String = "split"     // "split" or "secure"
    private var transportMode: String = "auto"
    private var assignedIp: String = "10.10.0.3"
    private var serverHost: String = "vpn.jogjacloud.com"
    private var selectedPubKey: String = ""
    private var selectedPeerName: String = "Android Device"

    private val namedSubnets = mutableListOf<NamedSubnet>()
    private val wifiRules = mutableListOf<WifiRule>()
    private val activeSubnetsCidr = mutableListOf<String>()

    // Drawer Views
    private lateinit var sidebarOverlay: FrameLayout
    private lateinit var sidebarDrawer: LinearLayout
    private lateinit var drawerPeerText: TextView
    private lateinit var drawerIpText: TextView

    // Top Mode Selector (Split vs Secure)
    private lateinit var btnModeSplit: Button
    private lateinit var btnModeSecure: Button

    // Center Hero Power Button
    private lateinit var heroPowerRing: FrameLayout
    private lateinit var heroPowerCircle: FrameLayout
    private lateinit var heroPowerIcon: TextView
    private lateinit var heroStatusTitle: TextView
    private lateinit var heroStatusSubtitle: TextView

    // Bento Grid Cards
    private lateinit var tvBentoTrafficVal: TextView
    private lateinit var tvBentoTrafficSub: TextView
    private lateinit var tvBentoLatencyVal: TextView
    private lateinit var tvBentoLatencyDot: View
    private lateinit var tvBentoProtocolVal: TextView
    private lateinit var tvBentoProtocolSub: TextView
    private lateinit var tvBentoIpVal: TextView
    private lateinit var tvBentoIpSub: TextView

    // Transport Bar
    private lateinit var btnTransUdp: Button
    private lateinit var btnTransQuic: Button
    private lateinit var btnTransTcp: Button
    private lateinit var btnTransAuto: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                if (intent.action == StabirushVpnService.ACTION_STATUS_BROADCAST) {
                    refreshUiState()
                } else if (intent.action == StabirushVpnService.ACTION_SYNC_BROADCAST) {
                    loadModelsFromPrefs()
                    refreshUiState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)

        val token = prefs.getString("authToken", "") ?: ""
        if (token.isEmpty()) {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        loadModelsFromPrefs()
        buildCyberModernUI()

        syncFromServer(showToastMsg = false)
        requestLocationForSsidAndEvaluate()
    }

    override fun onResume() {
        super.onResume()
        val token = prefs.getString("authToken", "") ?: ""
        if (token.isEmpty()) {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        val filter = IntentFilter().apply {
            addAction(StabirushVpnService.ACTION_STATUS_BROADCAST)
            addAction(StabirushVpnService.ACTION_SYNC_BROADCAST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        loadModelsFromPrefs()
        refreshUiState()
        evaluateWifiSsidRules(applySwitch = false)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action")
        if (action == "connect" && !StabirushVpnService.isRunning) {
            toggleVpnConnection()
        } else if (action == "disconnect" && StabirushVpnService.isRunning) {
            toggleVpnConnection()
        } else if (action == "set_transport") {
            val t = intent.getStringExtra("transport") ?: "udp"
            setTransportMode(t, restartIfConnected = true)
        } else if (action == "set_mode") {
            val m = intent.getStringExtra("mode") ?: "split"
            setRoutingMode(m, restartIfConnected = true)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (ignored: Exception) {}
    }

    private fun loadModelsFromPrefs() {
        if (prefs.getString("clientPrivateKey", "").isNullOrEmpty()) {
            prefs.edit().putString("clientPrivateKey", "IEyVQmA+ijVcRkF2Ba3LY2d3/eu4HvrcBYD2ZfXz8UU=").apply()
        }
        if (prefs.getString("serverEndpoint", "").isNullOrEmpty()) {
            prefs.edit().putString("serverEndpoint", "168.110.193.226:51820").apply()
        }
        routingMode = prefs.getString("routingMode", "split") ?: "split"
        transportMode = prefs.getString("transportMode", "auto") ?: "auto"
        assignedIp = prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        serverHost = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        selectedPubKey = prefs.getString("selectedPubKey", "") ?: ""
        selectedPeerName = prefs.getString("selectedPeerName", "Android Device") ?: "Android Device"

        namedSubnets.clear()
        val nsJson = prefs.getString("namedSubnetsJson", null)
        if (nsJson != null) {
            try {
                val arr = JSONArray(nsJson)
                for (i in 0 until arr.length()) {
                    namedSubnets.add(NamedSubnet.fromJsonObject(arr.getJSONObject(i)))
                }
            } catch (ignored: Exception) {}
        }
        if (namedSubnets.isEmpty()) {
            namedSubnets.addAll(NamedSubnet.defaultList())
        }

        activeSubnetsCidr.clear()
        val activeSubsStr = prefs.getString("activeSubnets", null)
        if (activeSubsStr != null) {
            activeSubnetsCidr.addAll(activeSubsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (activeSubnetsCidr.isEmpty()) {
            activeSubnetsCidr.addAll(namedSubnets.map { it.cidr })
        }

        wifiRules.clear()
        val wrJson = prefs.getString("wifiRulesJson", null)
        if (wrJson != null) {
            try {
                val arr = JSONArray(wrJson)
                for (i in 0 until arr.length()) {
                    wifiRules.add(WifiRule.fromJsonObject(arr.getJSONObject(i)))
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun buildCyberModernUI() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0E17"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ================= MAIN SCREEN CONTAINER =================
        val mainScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. App Bar Header (Hamburger ☰ + Brand + Sync ↻)
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0A0E17"))
        }

        // Hamburger Menu Button (3 Horizontal Lines)
        val btnHamburger = FrameLayout(this).apply {
            val d = dp(38)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = roundedRect(Color.parseColor("#111827"), dp(10), Color.parseColor("#1F2937"))
            isClickable = true
            isFocusable = true
            setOnClickListener { openDrawer() }
        }
        val linesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        for (i in 0..2) {
            val line = View(this).apply {
                background = roundedRect(Color.parseColor("#38BDF8"), dp(2), Color.TRANSPARENT)
                val lp = LinearLayout.LayoutParams(dp(18), dp(2))
                if (i > 0) lp.topMargin = dp(4)
                layoutParams = lp
            }
            linesContainer.addView(line)
        }
        btnHamburger.addView(linesContainer)
        appBar.addView(btnHamburger)

        // Title Center
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val brandTitle = TextView(this).apply {
            text = "STABIRUSH"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        val brandSub = TextView(this).apply {
            text = "CYBER-MINIMALIST GUARD"
            setTextColor(Color.parseColor("#0EA5E9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
        }
        titleBox.addView(brandTitle)
        titleBox.addView(brandSub)
        appBar.addView(titleBox)

        // Sync Refresh Button (Right)
        val btnSync = FrameLayout(this).apply {
            val d = dp(38)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = roundedRect(Color.parseColor("#111827"), dp(10), Color.parseColor("#1F2937"))
            isClickable = true
            isFocusable = true
            setOnClickListener { syncFromServer(showToastMsg = true) }
        }
        val syncIcon = TextView(this).apply {
            text = "↻"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        btnSync.addView(syncIcon)
        appBar.addView(btnSync)

        mainScreen.addView(appBar)

        // 2. Scrollable Content Body
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }

        // ================= TOP: COMPACT SEGMENTED ROUTING MODE =================
        // (Swapped to top as requested, without verbose description!)
        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        }

        btnModeSplit = Button(this).apply {
            text = "🌐 Split Tunnel"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener { setRoutingMode("split", restartIfConnected = true) }
        }

        btnModeSecure = Button(this).apply {
            text = "🔒 Secure 0.0.0.0/0"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { setRoutingMode("secure", restartIfConnected = true) }
        }

        modeContainer.addView(btnModeSplit)
        modeContainer.addView(btnModeSecure)
        content.addView(modeContainer)

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        // ================= CENTER: HERO POWER BUTTON =================
        heroPowerRing = FrameLayout(this).apply {
            val d = dp(144)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = roundedRect(Color.parseColor("#0C1527"), dp(72), Color.parseColor("#1E293B"))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVpnConnection() }
        }

        heroPowerCircle = FrameLayout(this).apply {
            val d = dp(112)
            layoutParams = FrameLayout.LayoutParams(d, d, Gravity.CENTER)
            background = roundedRect(Color.parseColor("#1E293B"), dp(56), Color.parseColor("#334155"))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVpnConnection() }
        }

        heroPowerIcon = TextView(this).apply {
            text = "⏻"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVpnConnection() }
        }
        heroPowerCircle.addView(heroPowerIcon)
        heroPowerRing.addView(heroPowerCircle)
        content.addView(heroPowerRing)

        // Status Title & Subtitle
        heroStatusTitle = TextView(this).apply {
            text = "DISCONNECTED"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(heroStatusTitle)

        heroStatusSubtitle = TextView(this).apply {
            text = "Ketuk untuk mengamankan koneksi Anda"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        content.addView(heroStatusSubtitle)

        // ================= BENTO GRID LIVE METRIC CARDS (2x2) =================
        val bentoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Row 1: Traffic + Latency
        val bentoRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Card 1: Traffic (Tx/Rx)
        val cardTraffic = createBentoCard("⬇ RX / ⬆ TX (LIVE)", "0 B / 0 B", "0 paket").also {
            bentoRow1.addView(it.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            tvBentoTrafficVal = it.second
            tvBentoTrafficSub = it.third
        }

        // Card 2: Latency
        val cardLatency = createBentoCardWithDot("⚡ LATENSI PING", "-- ms", "Gateway: 10.10.0.1").also {
            bentoRow1.addView(it.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tvBentoLatencyVal = it.second
            tvBentoLatencyDot = it.third
        }
        bentoCol.addView(bentoRow1)

        bentoCol.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        // Row 2: Protocol + Virtual IP
        val bentoRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Card 3: Transport Protocol
        val cardProtocol = createBentoCard("🛡️ PROTOKOL", "Auto", "Noise_IK / QUIC").also {
            bentoRow2.addView(it.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            tvBentoProtocolVal = it.second
            tvBentoProtocolSub = it.third
        }

        // Card 4: Virtual IP
        val cardIp = createBentoCard("🆔 VIRTUAL IP", assignedIp, "Split Active").also {
            bentoRow2.addView(it.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tvBentoIpVal = it.second
            tvBentoIpSub = it.third
        }
        bentoCol.addView(bentoRow2)

        content.addView(bentoCol)

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

        // ================= BOTTOM: TRANSPORT PROTOCOL BAR =================
        val transHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val transLabel = TextView(this).apply {
            text = "MODA TRANSPORTASI"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        transHeaderRow.addView(transLabel)
        content.addView(transHeaderRow)

        val transGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }

        btnTransUdp = Button(this).apply {
            text = "⚡ WG"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("udp", restartIfConnected = true) }
        }
        btnTransQuic = Button(this).apply {
            text = "🚀 QUIC"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("quic", restartIfConnected = true) }
        }
        btnTransTcp = Button(this).apply {
            text = "🛡️ TCP"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("wstunnel", restartIfConnected = true) }
        }
        btnTransAuto = Button(this).apply {
            text = "Auto"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.22f)
            setOnClickListener { setTransportMode("auto", restartIfConnected = true) }
        }

        transGrid.addView(btnTransUdp)
        transGrid.addView(btnTransQuic)
        transGrid.addView(btnTransTcp)
        transGrid.addView(btnTransAuto)
        content.addView(transGrid)

        scrollView.addView(content)
        mainScreen.addView(scrollView)
        rootFrame.addView(mainScreen)

        // ================= SIDEBAR SCRIM OVERLAY =================
        sidebarOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(sidebarOverlay)

        // ================= SIDEBAR DRAWER (CYBER GLASS) =================
        sidebarDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1322"))
            val lp = FrameLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
            layoutParams = lp
            visibility = View.GONE
            elevation = dp(16).toFloat()
            isClickable = true
            isFocusable = true
        }

        // Drawer Header Card
        val dHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            background = roundedRect(Color.parseColor("#111A2E"), 0, Color.TRANSPARENT)
        }
        val dBadge = TextView(this).apply {
            text = "STABIRUSH SECURE"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        drawerPeerText = TextView(this).apply {
            text = selectedPeerName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        drawerIpText = TextView(this).apply {
            text = "IP: $assignedIp"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        dHeader.addView(dBadge)
        dHeader.addView(drawerPeerText)
        dHeader.addView(drawerIpText)
        sidebarDrawer.addView(dHeader)

        // Drawer Menu Items List
        val dMenuScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val dMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        addDrawerItem(dMenu, "📶", "Aturan Wi-Fi Otomatis", "Auto-switch profil Wi-Fi") {
            closeDrawer()
            startActivity(Intent(this, WifiRulesActivity::class.java))
        }
        addDrawerItem(dMenu, "🌐", "Kelola Subnet Private", "Daftar CIDR split tunnel") {
            closeDrawer()
            startActivity(Intent(this, SubnetManagerActivity::class.java))
        }
        addDrawerItem(dMenu, "📹", "Diagnostik & Tes CCTV", "Uji RTSP & throughput") {
            closeDrawer()
            startActivity(Intent(this, CctvTesterActivity::class.java))
        }
        addDrawerItem(dMenu, "🛡️", "Server & Akun", "Status server & token") {
            closeDrawer()
            startActivity(Intent(this, AccountActivity::class.java))
        }

        dMenuScroll.addView(dMenu)
        sidebarDrawer.addView(dMenuScroll)

        // Drawer Footer (Logout)
        val dFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            background = roundedRect(Color.parseColor("#0B101C"), 0, Color.TRANSPARENT)
        }
        val btnLogoutDrawer = Button(this).apply {
            text = "🚪  Keluar (Logout)"
            setAllCaps(false)
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedRect(Color.parseColor("#271216"), dp(8), Color.parseColor("#EF4444"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            setOnClickListener {
                closeDrawer()
                confirmLogout()
            }
        }
        dFooter.addView(btnLogoutDrawer)
        sidebarDrawer.addView(dFooter)

        rootFrame.addView(sidebarDrawer)

        setContentView(rootFrame)
    }

    private fun openDrawer() {
        sidebarOverlay.visibility = View.VISIBLE
        sidebarDrawer.visibility = View.VISIBLE
        sidebarDrawer.translationX = -dp(290).toFloat()
        sidebarDrawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        sidebarDrawer.animate().translationX(-dp(290).toFloat()).setDuration(180).withEndAction {
            sidebarDrawer.visibility = View.GONE
            sidebarOverlay.visibility = View.GONE
        }.start()
    }

    private fun addDrawerItem(parent: LinearLayout, icon: String, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#111A2E"), dp(10), Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val ic = TextView(this).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, dp(12), 0)
        }
        row.addView(ic)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sTv = TextView(this).apply {
            text = sub
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }
        col.addView(tTv)
        col.addView(sTv)
        row.addView(col)

        val arrow = TextView(this).apply {
            text = "›"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        row.addView(arrow)

        parent.addView(row)
    }

    private fun createBentoCard(header: String, defVal: String, defSub: String): Triple<LinearLayout, TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
        }
        val hTv = TextView(this).apply {
            text = header
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        val vTv = TextView(this).apply {
            text = defVal
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        val sTv = TextView(this).apply {
            text = defSub
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(hTv)
        card.addView(vTv)
        card.addView(sTv)
        return Triple(card, vTv, sTv)
    }

    private fun createBentoCardWithDot(header: String, defVal: String, defSub: String): Triple<LinearLayout, TextView, View> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hTv = TextView(this).apply {
            text = header
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(this).apply {
            val d = dp(8)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = roundedRect(Color.parseColor("#64748B"), dp(4), Color.TRANSPARENT)
        }
        topRow.addView(hTv)
        topRow.addView(dot)
        card.addView(topRow)

        val vTv = TextView(this).apply {
            text = defVal
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        val sTv = TextView(this).apply {
            text = defSub
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(vTv)
        card.addView(sTv)
        return Triple(card, vTv, dot)
    }

    private fun setRoutingMode(mode: String, restartIfConnected: Boolean) {
        if (routingMode == mode) return
        routingMode = mode
        prefs.edit().putString("routingMode", mode).apply()
        refreshUiState()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = if (mode.equals("secure", ignoreCase = true)) "🔒 Secure Full Tunnel (0.0.0.0/0)" else "Split Tunnel"
            Toast.makeText(this, "Mode diubah ke $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransportMode(mode: String, restartIfConnected: Boolean) {
        transportMode = mode
        prefs.edit().putString("transportMode", mode).apply()
        refreshUiState()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = when (mode) {
                "quic" -> "🚀 QUIC (UDP 8443)"
                "wstunnel", "tcp" -> "🛡️ TCP:443 (TLS)"
                "udp", "wireguard" -> "⚡ WireGuard (UDP 51820)"
                else -> "Auto (Fallback)"
            }
            Toast.makeText(this, "Transport disetel ke $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleTransportButton(btn: Button, active: Boolean) {
        if (active) {
            btn.setTextColor(Color.WHITE)
            btn.background = roundedRect(Color.parseColor("#0284C7"), dp(6), Color.parseColor("#38BDF8"))
        } else {
            btn.setTextColor(Color.parseColor("#94A3B8"))
            btn.background = roundedRect(Color.parseColor("#111827"), dp(6), Color.TRANSPARENT)
        }
    }

    private fun toggleVpnConnection() {
        if (StabirushVpnService.isRunning) {
            val stopIntent = Intent(this, StabirushVpnService::class.java).apply {
                action = StabirushVpnService.ACTION_DISCONNECT
            }
            startService(stopIntent)
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                startActivityForResult(prep, REQ_VPN_PERMISSION)
            } else {
                onActivityResult(REQ_VPN_PERMISSION, RESULT_OK, null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val srvIntent = Intent(this, StabirushVpnService::class.java).apply {
            action = StabirushVpnService.ACTION_CONNECT
            putExtra("routingMode", routingMode)
            putExtra("transportMode", transportMode)
            putExtra("assignedIp", assignedIp)
            putExtra("serverHost", serverHost)
            putExtra("selectedPubKey", selectedPubKey)
            putExtra("activeSubnets", activeSubnetsCidr.joinToString(","))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srvIntent)
        } else {
            startService(srvIntent)
        }
    }

    private fun getSelectedSubnetNamesSummary(): String {
        val selNames = mutableListOf<String>()
        namedSubnets.forEach { ns ->
            if (activeSubnetsCidr.contains(ns.cidr)) {
                selNames.add(ns.name)
            }
        }
        return if (selNames.isEmpty()) "Hanya IP Sendiri" else selNames.joinToString(", ")
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private fun refreshUiState() {
        val running = StabirushVpnService.isRunning
        val isConnected = running && (StabirushVpnService.isVerified || StabirushVpnService.rxPackets.get() > 0)
        val isFallback = StabirushVpnService.isFallbackActive
        val activeTrans = StabirushVpnService.activeTransport
        val txBytes = StabirushVpnService.txBytes.get()
        val rxBytes = StabirushVpnService.rxBytes.get()
        val txPackets = StabirushVpnService.txPackets.get()
        val rxPackets = StabirushVpnService.rxPackets.get()

        val isSecure = routingMode.equals("secure", ignoreCase = true)

        // 1. Top Segmented Mode Buttons
        if (isSecure) {
            btnModeSecure.setTextColor(Color.WHITE)
            btnModeSecure.background = roundedRect(Color.parseColor("#10B981"), dp(10), Color.parseColor("#34D399"))
            btnModeSplit.setTextColor(Color.parseColor("#94A3B8"))
            btnModeSplit.background = roundedRect(Color.parseColor("#111827"), dp(10), Color.TRANSPARENT)
        } else {
            btnModeSplit.setTextColor(Color.WHITE)
            btnModeSplit.background = roundedRect(Color.parseColor("#0284C7"), dp(10), Color.parseColor("#38BDF8"))
            btnModeSecure.setTextColor(Color.parseColor("#94A3B8"))
            btnModeSecure.background = roundedRect(Color.parseColor("#111827"), dp(10), Color.TRANSPARENT)
        }

        // 2. Central Hero Power Button
        if (isConnected) {
            // Emerald Connected Glow
            heroPowerRing.background = roundedRect(Color.parseColor("#062B28"), dp(72), Color.parseColor("#10B981"))
            heroPowerCircle.background = roundedRect(Color.parseColor("#10B981"), dp(56), Color.parseColor("#34D399"))
            heroPowerIcon.text = "✓"
            heroPowerIcon.setTextColor(Color.WHITE)

            heroStatusTitle.text = if (isSecure) "SECURE TUNNEL AKTIF" else "SPLIT TUNNEL AKTIF"
            heroStatusTitle.setTextColor(Color.parseColor("#10B981"))
            heroStatusSubtitle.text = "Koneksi terenkripsi 2-Arah · Gateway 10.10.0.1"

            // Bento Cards Live Info
            tvBentoTrafficVal.text = "⬇ ${formatBytes(rxBytes)} / ⬆ ${formatBytes(txBytes)}"
            tvBentoTrafficSub.text = "$rxPackets rx / $txPackets tx pkts"

            val p = StabirushVpnService.lastPingMs
            tvBentoLatencyVal.text = if (p >= 0) "$p ms" else "<1 ms"
            (tvBentoLatencyDot.background as? GradientDrawable)?.setColor(
                if (p in 0..60) Color.parseColor("#10B981")
                else if (p in 61..160) Color.parseColor("#F59E0B")
                else Color.parseColor("#10B981")
            )

            tvBentoProtocolVal.text = when {
                activeTrans == "quic" -> "🚀 QUIC"
                isFallback || activeTrans.contains("tcp") -> "🛡️ TCP:443"
                else -> "⚡ WireGuard"
            }
            tvBentoProtocolSub.text = if (activeTrans == "quic") "UDP 51821" else if (activeTrans.contains("tcp")) "TLS Tunnel" else "UDP 51820"

            tvBentoIpVal.text = assignedIp
            tvBentoIpSub.text = if (isSecure) "Full Tunnel 0.0.0.0/0" else getSelectedSubnetNamesSummary()

        } else if (running) {
            // Amber Connecting State
            heroPowerRing.background = roundedRect(Color.parseColor("#1C1917"), dp(72), Color.parseColor("#F59E0B"))
            heroPowerCircle.background = roundedRect(Color.parseColor("#D97706"), dp(56), Color.parseColor("#FBBF24"))
            heroPowerIcon.text = "⏳"
            heroPowerIcon.setTextColor(Color.WHITE)

            heroStatusTitle.text = "MENGHUBUNGKAN..."
            heroStatusTitle.setTextColor(Color.parseColor("#F59E0B"))
            heroStatusSubtitle.text = when {
                activeTrans == "quic" -> "Menghubungkan via QUIC Fast-UDP..."
                isFallback -> "Mengalihkan ke TCP/TLS 443..."
                else -> "Menguji handshake WireGuard..."
            }

            tvBentoTrafficVal.text = "Mengirim probe..."
            tvBentoTrafficSub.text = "Menunggu verifikasi 2-arah"
            tvBentoLatencyVal.text = "Menguji..."
            (tvBentoLatencyDot.background as? GradientDrawable)?.setColor(Color.parseColor("#F59E0B"))
            tvBentoProtocolVal.text = activeTrans.uppercase()
            tvBentoProtocolSub.text = "Handshake..."
            tvBentoIpVal.text = assignedIp
            tvBentoIpSub.text = "Menghubungkan..."

        } else {
            // Disconnected Idle State (Cyber Slate / Cyan Accent)
            heroPowerRing.background = roundedRect(Color.parseColor("#0C1527"), dp(72), Color.parseColor("#1E293B"))
            heroPowerCircle.background = roundedRect(Color.parseColor("#1E293B"), dp(56), Color.parseColor("#334155"))
            heroPowerIcon.text = "⏻"
            heroPowerIcon.setTextColor(Color.parseColor("#94A3B8"))

            heroStatusTitle.text = "DISCONNECTED"
            heroStatusTitle.setTextColor(Color.WHITE)
            heroStatusSubtitle.text = "Ketuk tombol untuk mengamankan koneksi"

            tvBentoTrafficVal.text = "0 B / 0 B"
            tvBentoTrafficSub.text = "Trafik belum berjalan"
            tvBentoLatencyVal.text = "-- ms"
            (tvBentoLatencyDot.background as? GradientDrawable)?.setColor(Color.parseColor("#64748B"))
            tvBentoProtocolVal.text = "Auto"
            tvBentoProtocolSub.text = "Standby"
            tvBentoIpVal.text = assignedIp
            tvBentoIpSub.text = if (isSecure) "Secure (Full)" else "Split (${namedSubnets.size} subnets)"
        }

        // 3. Transport Buttons Styling
        val curTrans = prefs.getString("transportMode", "auto") ?: "auto"
        styleTransportButton(btnTransAuto, curTrans == "auto")
        styleTransportButton(btnTransUdp, curTrans == "udp" || curTrans == "wireguard")
        styleTransportButton(btnTransQuic, curTrans == "quic")
        styleTransportButton(btnTransTcp, curTrans == "wstunnel" || curTrans == "tcp")

        // 4. Update Drawer Info
        drawerPeerText.text = selectedPeerName
        drawerIpText.text = "IP: $assignedIp"
    }

    private fun requestLocationForSsidAndEvaluate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC_PERMISSION)
                return
            }
        }
        evaluateWifiSsidRules(applySwitch = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC_PERMISSION) {
            evaluateWifiSsidRules(applySwitch = false)
        }
    }

    private fun getCurrentWifiSsid(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            if (info != null && info.ssid != null) {
                var raw = info.ssid
                if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                    raw = raw.substring(1, raw.length - 1)
                }
                if (!raw.equals("<unknown ssid>", ignoreCase = true)) {
                    return raw
                }
            }
        } catch (ignored: Exception) {}
        return ""
    }

    private fun evaluateWifiSsidRules(applySwitch: Boolean) {
        val ssid = getCurrentWifiSsid()
        val autoSwitchEnabled = prefs.getBoolean("autoWifiSwitchEnabled", false)
        if (!autoSwitchEnabled || !applySwitch) return

        var matched: WifiRule? = null
        for (r in wifiRules) {
            if (ssid.isNotEmpty() && matchesAnyPattern(ssid, r.ssid)) {
                matched = r
                break
            }
        }

        if (matched != null) {
            val targetMode = matched.profileMode.lowercase()
            if (targetMode == "disconnect") {
                if (StabirushVpnService.isRunning) {
                    toggleVpnConnection()
                    Toast.makeText(this, "Wi-Fi '$ssid': VPN Dinonaktifkan otomatis", Toast.LENGTH_SHORT).show()
                }
            } else if (targetMode == "secure" || targetMode == "split") {
                if (routingMode != targetMode) {
                    setRoutingMode(targetMode, restartIfConnected = true)
                    Toast.makeText(this, "Wi-Fi '$ssid': Beralih ke mode $targetMode", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun matchesAnyPattern(ssid: String, rawList: String): Boolean {
        val parts = rawList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            val regexStr = "^" + java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$"
            if (ssid.matches(Regex(regexStr, RegexOption.IGNORE_CASE))) return true
        }
        return false
    }

    private fun syncFromServer(showToastMsg: Boolean) {
        val token = prefs.getString("authToken", "") ?: ""
        if (token.isEmpty()) return

        Thread {
            try {
                val url = URL("https://$serverHost/api/android/sync")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Authorization", "Bearer $token")

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val devObj = json.optJSONObject("device")
                        if (devObj != null) {
                            val ip = devObj.optString("allocatedIP", assignedIp)
                            val name = devObj.optString("name", selectedPeerName)
                            prefs.edit().putString("assignedIp", ip).putString("selectedPeerName", name).apply()
                            mainHandler.post {
                                assignedIp = ip
                                selectedPeerName = name
                                refreshUiState()
                                if (showToastMsg) Toast.makeText(this@MainActivity, "✓ Berhasil disinkronkan dengan server", Toast.LENGTH_SHORT).show()
                            }
                            return@Thread
                        }
                    }
                }
                if (showToastMsg) {
                    mainHandler.post { Toast.makeText(this@MainActivity, "Gagal sinkronisasi (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                if (showToastMsg) {
                    mainHandler.post { Toast.makeText(this@MainActivity, "Sync offline: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }.start()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Logout")
            .setMessage("Apakah Anda yakin ingin keluar dari akun Stabirush?")
            .setPositiveButton("Logout") { _, _ ->
                if (StabirushVpnService.isRunning) {
                    val stopIntent = Intent(this, StabirushVpnService::class.java).apply {
                        action = StabirushVpnService.ACTION_DISCONNECT
                    }
                    startService(stopIntent)
                }
                prefs.edit().remove("authToken").apply()
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radiusPx.toFloat()
        if (strokeColor != Color.TRANSPARENT) {
            setStroke(dp(1), strokeColor)
        }
    }
}
