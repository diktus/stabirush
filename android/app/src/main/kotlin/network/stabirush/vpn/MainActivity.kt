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
import android.widget.ImageView
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

    // Top Segmented Mode Cards
    private lateinit var cardModeSplit: LinearLayout
    private lateinit var cardModeSecure: LinearLayout
    private lateinit var badgeModeSplit: FrameLayout
    private lateinit var badgeModeSecure: FrameLayout
    private lateinit var tvModeSplitTitle: TextView
    private lateinit var tvModeSecureTitle: TextView

    // Center Hero Power Button
    private lateinit var heroPowerRing: FrameLayout
    private lateinit var heroPowerCircle: FrameLayout
    private lateinit var heroPowerIcon: ImageView
    private lateinit var heroStatusTitle: TextView
    private lateinit var heroStatusSubtitle: TextView

    // Bento Grid Cards
    private lateinit var tvBentoTrafficVal: TextView
    private lateinit var tvBentoTrafficSub: TextView
    private lateinit var badgeTraffic: FrameLayout
    private lateinit var tvBentoLatencyVal: TextView
    private lateinit var tvBentoLatencyDot: View
    private lateinit var badgeLatency: FrameLayout
    private lateinit var tvBentoProtocolVal: TextView
    private lateinit var tvBentoProtocolSub: TextView
    private lateinit var badgeProtocol: FrameLayout
    private lateinit var ivBentoProtocol: ImageView
    private lateinit var tvBentoIpVal: TextView
    private lateinit var tvBentoIpSub: TextView
    private lateinit var badgeIp: FrameLayout

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

        // 1. App Bar Header (Hamburger Menu + Brand + Sync)
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0A0E17"))
        }

        // Hamburger Menu Button wrapped in Soft-Tinted Badge
        val btnHamburger = createSoftTintedBadge(R.drawable.ic_menu, Color.parseColor("#38BDF8"), sizeDp = 40, cornerDp = 12).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openDrawer() }
        }
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

        // Sync Refresh Button wrapped in Soft-Tinted Badge
        val btnSync = createSoftTintedBadge(R.drawable.ic_sync, Color.parseColor("#38BDF8"), sizeDp = 40, cornerDp = 12).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { syncFromServer(showToastMsg = true) }
        }
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
            background = roundedRect(Color.parseColor("#111827"), dp(16), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }

        // Split Mode Card
        cardModeSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4) }
            isClickable = true
            isFocusable = true
            setOnClickListener { setRoutingMode("split", restartIfConnected = true) }
        }
        badgeModeSplit = createSoftTintedBadge(R.drawable.ic_call_split, Color.parseColor("#38BDF8"), sizeDp = 26, cornerDp = 8)
        tvModeSplitTitle = TextView(this).apply {
            text = "Split Tunnel"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        cardModeSplit.addView(badgeModeSplit)
        cardModeSplit.addView(tvModeSplitTitle)

        // Secure Full Mode Card
        cardModeSecure = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { setRoutingMode("secure", restartIfConnected = true) }
        }
        badgeModeSecure = createSoftTintedBadge(R.drawable.ic_rocket_launch, Color.parseColor("#10B981"), sizeDp = 26, cornerDp = 8)
        tvModeSecureTitle = TextView(this).apply {
            text = "Secure 0.0.0.0/0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        cardModeSecure.addView(badgeModeSecure)
        cardModeSecure.addView(tvModeSecureTitle)

        modeContainer.addView(cardModeSplit)
        modeContainer.addView(cardModeSecure)
        content.addView(modeContainer)

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

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

        heroPowerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_power)
            setColorFilter(Color.parseColor("#94A3B8"))
            val iconSize = dp(46)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
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
            setPadding(0, dp(4), 0, dp(20))
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

        // Card 1: Traffic (Tx/Rx) with Soft-Tinted Pill Arrow Badge
        val cardTrafficTuple = createModernBentoCard(
            header = "THROUGHPUT",
            iconRes = R.drawable.ic_throughput,
            iconTint = Color.parseColor("#38BDF8"),
            defVal = "0 B / 0 B",
            defSub = "0 rx / 0 tx pkts"
        )
        bentoRow1.addView(cardTrafficTuple.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        tvBentoTrafficVal = cardTrafficTuple.second
        tvBentoTrafficSub = cardTrafficTuple.third
        badgeTraffic = cardTrafficTuple.fourth

        // Card 2: Latency with Soft-Tinted ECG Pulse Wave Badge
        val cardLatencyTuple = createModernBentoCardWithDot(
            header = "LATENSI PING",
            iconRes = R.drawable.ic_pulse,
            iconTint = Color.parseColor("#10B981"),
            defVal = "-- ms",
            defSub = "GW: 10.10.0.1"
        )
        bentoRow1.addView(cardLatencyTuple.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvBentoLatencyVal = cardLatencyTuple.second
        tvBentoLatencyDot = cardLatencyTuple.third
        badgeLatency = cardLatencyTuple.fourth

        bentoCol.addView(bentoRow1)
        bentoCol.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        // Row 2: Protocol + Virtual IP
        val bentoRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Card 3: Transport Protocol with Soft-Tinted Shield Badge
        val cardProtocolTuple = createModernBentoCard(
            header = "PROTOKOL",
            iconRes = R.drawable.ic_shield_check,
            iconTint = Color.parseColor("#38BDF8"),
            defVal = "Auto",
            defSub = "Noise_IK / QUIC"
        )
        bentoRow2.addView(cardProtocolTuple.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        tvBentoProtocolVal = cardProtocolTuple.second
        tvBentoProtocolSub = cardProtocolTuple.third
        badgeProtocol = cardProtocolTuple.fourth
        ivBentoProtocol = badgeProtocol.getChildAt(0) as ImageView

        // Card 4: Virtual IP with Soft-Tinted Mesh Hub Badge
        val cardIpTuple = createModernBentoCard(
            header = "VIRTUAL IP",
            iconRes = R.drawable.ic_hub,
            iconTint = Color.parseColor("#0EA5E9"),
            defVal = assignedIp,
            defSub = "Split Active"
        )
        bentoRow2.addView(cardIpTuple.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvBentoIpVal = cardIpTuple.second
        tvBentoIpSub = cardIpTuple.third
        badgeIp = cardIpTuple.fourth

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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }

        btnTransUdp = Button(this).apply {
            text = "⚡ WG"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("udp", restartIfConnected = true) }
        }
        btnTransQuic = Button(this).apply {
            text = "🚀 QUIC"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("quic", restartIfConnected = true) }
        }
        btnTransTcp = Button(this).apply {
            text = "🛡️ TCP"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.26f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("wstunnel", restartIfConnected = true) }
        }
        btnTransAuto = Button(this).apply {
            text = "Auto"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

        // Drawer Header Card with Duotone Shield
        val dHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(20))
            background = roundedRect(Color.parseColor("#111A2E"), 0, Color.TRANSPARENT)
        }
        val headerShieldBadge = createSoftTintedBadge(R.drawable.ic_shield_check, Color.parseColor("#10B981"), sizeDp = 44, cornerDp = 14)
        dHeader.addView(headerShieldBadge)

        val dHeaderCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        drawerIpText = TextView(this).apply {
            text = "IP: $assignedIp"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(1), 0, 0)
        }
        dHeaderCol.addView(dBadge)
        dHeaderCol.addView(drawerPeerText)
        dHeaderCol.addView(drawerIpText)
        dHeader.addView(dHeaderCol)
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

        addModernDrawerItem(dMenu, R.drawable.ic_wifi, Color.parseColor("#38BDF8"), "Aturan Wi-Fi Otomatis", "Auto-switch profil Wi-Fi") {
            closeDrawer()
            startActivity(Intent(this, WifiRulesActivity::class.java))
        }
        addModernDrawerItem(dMenu, R.drawable.ic_hub, Color.parseColor("#0EA5E9"), "Kelola Subnet Private", "Daftar CIDR split tunnel") {
            closeDrawer()
            startActivity(Intent(this, SubnetManagerActivity::class.java))
        }
        addModernDrawerItem(dMenu, R.drawable.ic_cctv, Color.parseColor("#10B981"), "Diagnostik & Tes CCTV", "Uji RTSP & throughput") {
            closeDrawer()
            startActivity(Intent(this, CctvTesterActivity::class.java))
        }
        addModernDrawerItem(dMenu, R.drawable.ic_account, Color.parseColor("#38BDF8"), "Server & Akun", "Status server & token") {
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

    // Creates a Soft-Tinted Rounded Badge container wrapping a Vector Icon
    private fun createSoftTintedBadge(iconResId: Int, tintColor: Int, sizeDp: Int = 36, cornerDp: Int = 10): FrameLayout {
        val frame = FrameLayout(this).apply {
            val d = dp(sizeDp)
            layoutParams = FrameLayout.LayoutParams(d, d)
            // Soft-tinted semi-transparent background (alpha 15%) + delicate stroke (alpha 35%)
            val bgAlpha = Color.argb(38, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))
            val strokeAlpha = Color.argb(85, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))
            background = roundedRect(bgAlpha, dp(cornerDp), strokeAlpha)
        }
        val iv = ImageView(this).apply {
            setImageResource(iconResId)
            setColorFilter(tintColor)
            val p = dp(sizeDp / 4)
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(iv)
        return frame
    }

    private fun addModernDrawerItem(parent: LinearLayout, iconRes: Int, iconTint: Int, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.parseColor("#111A2E"), dp(12), Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val badge = createSoftTintedBadge(iconRes, iconTint, sizeDp = 34, cornerDp = 10)
        row.addView(badge)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
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

    private fun createModernBentoCard(
        header: String,
        iconRes: Int,
        iconTint: Int,
        defVal: String,
        defSub: String
    ): BentoCardResult {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#111827"), dp(16), Color.parseColor("#1F2937"))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = createSoftTintedBadge(iconRes, iconTint, sizeDp = 28, cornerDp = 8)
        topRow.addView(badge)

        val hTv = TextView(this).apply {
            text = header
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        topRow.addView(hTv)
        card.addView(topRow)

        val vTv = TextView(this).apply {
            text = defVal
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
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
        return BentoCardResult(card, vTv, sTv, badge)
    }

    private fun createModernBentoCardWithDot(
        header: String,
        iconRes: Int,
        iconTint: Int,
        defVal: String,
        defSub: String
    ): BentoCardDotResult {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#111827"), dp(16), Color.parseColor("#1F2937"))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = createSoftTintedBadge(iconRes, iconTint, sizeDp = 28, cornerDp = 8)
        topRow.addView(badge)

        val hTv = TextView(this).apply {
            text = header
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        topRow.addView(hTv)

        val dot = View(this).apply {
            val d = dp(8)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = roundedRect(Color.parseColor("#64748B"), dp(4), Color.TRANSPARENT)
        }
        topRow.addView(dot)
        card.addView(topRow)

        val vTv = TextView(this).apply {
            text = defVal
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
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
        return BentoCardDotResult(card, vTv, dot, badge)
    }

    private data class BentoCardResult(val first: LinearLayout, val second: TextView, val third: TextView, val fourth: FrameLayout)
    private data class BentoCardDotResult(val first: LinearLayout, val second: TextView, val third: View, val fourth: FrameLayout)

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
            btn.background = roundedRect(Color.parseColor("#0284C7"), dp(8), Color.parseColor("#38BDF8"))
        } else {
            btn.setTextColor(Color.parseColor("#94A3B8"))
            btn.background = roundedRect(Color.parseColor("#111827"), dp(8), Color.TRANSPARENT)
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
            cardModeSecure.background = roundedRect(Color.parseColor("#062B28"), dp(12), Color.parseColor("#10B981"))
            tvModeSecureTitle.setTextColor(Color.WHITE)
            cardModeSplit.background = roundedRect(Color.TRANSPARENT, dp(12), Color.TRANSPARENT)
            tvModeSplitTitle.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            cardModeSplit.background = roundedRect(Color.parseColor("#0C2038"), dp(12), Color.parseColor("#0284C7"))
            tvModeSplitTitle.setTextColor(Color.WHITE)
            cardModeSecure.background = roundedRect(Color.TRANSPARENT, dp(12), Color.TRANSPARENT)
            tvModeSecureTitle.setTextColor(Color.parseColor("#94A3B8"))
        }

        // 2. Central Hero Power Button
        if (isConnected) {
            // Emerald Connected Glow
            heroPowerRing.background = roundedRect(Color.parseColor("#062B28"), dp(72), Color.parseColor("#10B981"))
            heroPowerCircle.background = roundedRect(Color.parseColor("#10B981"), dp(56), Color.parseColor("#34D399"))
            heroPowerIcon.setImageResource(R.drawable.ic_shield_check)
            heroPowerIcon.setColorFilter(Color.WHITE)

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
            ivBentoProtocol.setImageResource(if (activeTrans == "quic") R.drawable.ic_rocket_launch else if (activeTrans.contains("tcp")) R.drawable.ic_lock else R.drawable.ic_shield_check)

            tvBentoIpVal.text = assignedIp
            tvBentoIpSub.text = if (isSecure) "Full Tunnel 0.0.0.0/0" else getSelectedSubnetNamesSummary()

        } else if (running) {
            // Amber Connecting State
            heroPowerRing.background = roundedRect(Color.parseColor("#1C1917"), dp(72), Color.parseColor("#F59E0B"))
            heroPowerCircle.background = roundedRect(Color.parseColor("#D97706"), dp(56), Color.parseColor("#FBBF24"))
            heroPowerIcon.setImageResource(R.drawable.ic_power)
            heroPowerIcon.setColorFilter(Color.WHITE)

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
            heroPowerIcon.setImageResource(R.drawable.ic_power)
            heroPowerIcon.setColorFilter(Color.parseColor("#94A3B8"))

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
            ivBentoProtocol.setImageResource(R.drawable.ic_shield_check)
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
