package network.stabirush.vpn

import android.Manifest
import android.app.Activity
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

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

    // UI Elements (Cloudflare Zero Trust / NetBird Style)
    private lateinit var statusBadgeText: TextView
    private lateinit var statusBadgeBg: GradientDrawable

    private lateinit var toggleCard: LinearLayout
    private lateinit var toggleCardBg: GradientDrawable
    private lateinit var toggleCircle: FrameLayout
    private lateinit var toggleCircleBg: GradientDrawable
    private lateinit var toggleIcon: TextView
    private lateinit var toggleTitle: TextView
    private lateinit var toggleSubtitle: TextView
    private lateinit var toggleIpBadge: TextView
    private lateinit var toggleTrafficStats: TextView
    private lateinit var toggleTransportBadge: TextView

    private lateinit var cardSplitMode: LinearLayout
    private lateinit var cardSecureMode: LinearLayout
    private lateinit var radioSplit: TextView
    private lateinit var radioSecure: TextView

    private lateinit var btnTransUdp: Button
    private lateinit var btnTransTcp: Button
    private lateinit var btnTransAuto: Button

    private lateinit var wifiInfoText: TextView

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
        buildCloudflareStyleUI()

        // Sync from server on start
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
        } else if (action == "benchmark") {
            runCctvBenchmark { res ->
                try {
                    val f = java.io.File(filesDir, "bench_result.txt")
                    f.writeText(res)
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (ignored: Exception) {}
    }

    private fun loadModelsFromPrefs() {
        routingMode = prefs.getString("routingMode", "split") ?: "split"
        transportMode = prefs.getString("transportMode", "auto") ?: "auto"
        assignedIp = prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        serverHost = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        selectedPubKey = prefs.getString("selectedPubKey", "") ?: ""
        selectedPeerName = prefs.getString("selectedPeerName", "Android Device") ?: "Android Device"

        // Load Named Subnets
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

        // Active split subnets
        activeSubnetsCidr.clear()
        val activeSubsStr = prefs.getString("activeSubnets", null)
        if (activeSubsStr != null) {
            activeSubnetsCidr.addAll(activeSubsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (activeSubnetsCidr.isEmpty()) {
            activeSubnetsCidr.addAll(namedSubnets.map { it.cidr })
        }

        // Wi-Fi Rules
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

    private fun buildCloudflareStyleUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080D1A")) // Sleek deep dark navy background
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. Sleek App Bar (Brand + Zero Trust Badge + ⚙️ Settings Button)
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(Color.parseColor("#0B132B"))
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brandTitle = TextView(this).apply {
            text = "STABIRUSH"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        brandRow.addView(brandTitle)

        // Status Badge (like Cloudflare Zero Trust / NetBird)
        val badgeFrame = FrameLayout(this).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(10)
            layoutParams = lp
        }
        statusBadgeBg = roundedRect(Color.parseColor("#1E293B"), dp(12), Color.parseColor("#334155"))
        badgeFrame.background = statusBadgeBg

        statusBadgeText = TextView(this).apply {
            text = "ZERO TRUST"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
        badgeFrame.addView(statusBadgeText)
        brandRow.addView(badgeFrame)

        brandCol.addView(brandRow)

        val brandSubtitle = TextView(this).apply {
            val curUser = prefs.getString("username", "beni") ?: "beni"
            text = "👤 $curUser · Mesh: $assignedIp"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        }
        brandCol.addView(brandSubtitle)
        appBar.addView(brandCol)

        // ⚙️ Settings Icon Button
        val btnSettings = FrameLayout(this).apply {
            val size = dp(42)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = roundedRect(Color.parseColor("#1C2541"), dp(21), Color.parseColor("#334155"))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val gearIcon = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        btnSettings.addView(gearIcon)
        appBar.addView(btnSettings)

        root.addView(appBar)

        // 2. Scrollable Body
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        // 3. Central Hero Connect Card (Cloudflare Zero Trust / NetBird Big Toggle)
        toggleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(28))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleVpnConnection()
            }
        }
        toggleCardBg = roundedRect(Color.parseColor("#0F172A"), dp(24), Color.parseColor("#1E293B"))
        toggleCard.background = toggleCardBg

        // Big Circular Power Button in Center
        toggleCircle = FrameLayout(this).apply {
            val d = dp(116)
            layoutParams = LinearLayout.LayoutParams(d, d)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleVpnConnection()
            }
        }
        toggleCircleBg = roundedRect(Color.parseColor("#1E293B"), dp(58), Color.parseColor("#334155"))
        toggleCircle.background = toggleCircleBg

        toggleIcon = TextView(this).apply {
            text = "⏻"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleVpnConnection()
            }
        }
        toggleCircle.addView(toggleIcon)
        toggleCard.addView(toggleCircle)

        // Status Title
        toggleTitle = TextView(this).apply {
            text = "DISCONNECTED"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, dp(18), 0, 0)
        }
        toggleCard.addView(toggleTitle)

        toggleSubtitle = TextView(this).apply {
            text = "Ketuk untuk terhubung ke jaringan Stabirush"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        toggleCard.addView(toggleSubtitle)

        // Assigned IP / Latency Pill
        toggleIpBadge = TextView(this).apply {
            text = "IP: $assignedIp · Server: $serverHost"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedRect(Color.parseColor("#132A4A"), dp(12), Color.TRANSPARENT)
        }
        toggleCard.addView(toggleIpBadge)

        // Real-time Traffic Stats Pill (Tx / Rx)
        toggleTrafficStats = TextView(this).apply {
            text = "↑ 0 B  ·  ↓ 0 B"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        toggleCard.addView(toggleTrafficStats)

        // Transport Mode Indicator Pill
        toggleTransportBadge = TextView(this).apply {
            text = "⚡ Transport: Auto"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
            visibility = View.GONE
        }
        toggleCard.addView(toggleTransportBadge)

        content.addView(toggleCard)

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        // 4. Mode Selection Header
        val modeHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val modeLabel = TextView(this).apply {
            text = "PILIHAN MODE ROUTING"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val modeHint = TextView(this).apply {
            text = "Pilih salah satu"
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        modeHeaderRow.addView(modeLabel)
        modeHeaderRow.addView(modeHint)
        content.addView(modeHeaderRow)

        // Card Mode A: Split Tunnel
        cardSplitMode = createModeCard(
            title = "Split Tunnel (Rekomendasi)",
            description = "Hanya rute subnet kantor & private yang melewati VPN. Trafik internet umum langsung keluar via Wi-Fi/Seluler tanpa hambatan.",
            badge = "Subnet Saja"
        ) {
            setRoutingMode("split", restartIfConnected = true)
        }
        radioSplit = cardSplitMode.findViewWithTag("radio_indicator")
        content.addView(cardSplitMode)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        // Card Mode B: Secure Full Tunnel
        cardSecureMode = createModeCard(
            title = "🔒 Secure Mode (Full Tunnel)",
            description = "Semua trafik internet (0.0.0.0/0) dan query DNS dienkripsi penuh melalui gateway Stabirush WireGuard.",
            badge = "0.0.0.0/0"
        ) {
            setRoutingMode("secure", restartIfConnected = true)
        }
        radioSecure = cardSecureMode.findViewWithTag("radio_indicator")
        content.addView(cardSecureMode)

        // 4.5 Transport Protocol Header & Buttons
        val transHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val transLabel = TextView(this).apply {
            text = "PROTOKOL TRANSPORT"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        transHeaderRow.addView(transLabel)
        content.addView(transHeaderRow)

        val transGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        btnTransUdp = Button(this).apply {
            text = "⚡ WireGuard"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("udp", restartIfConnected = true) }
        }
        btnTransTcp = Button(this).apply {
            text = "🛡️ TCP:443"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.30f).apply { marginEnd = dp(4) }
            setOnClickListener { setTransportMode("wstunnel", restartIfConnected = true) }
        }
        btnTransAuto = Button(this).apply {
            text = "Auto"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.32f)
            setOnClickListener { setTransportMode("auto", restartIfConnected = true) }
        }
        transGrid.addView(btnTransUdp)
        transGrid.addView(btnTransTcp)
        transGrid.addView(btnTransAuto)
        content.addView(transGrid)

        // Spacer
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        // 5. Active Wi-Fi & Quick Info Box (Bottom)
        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedRect(Color.parseColor("#0F172A"), dp(12), Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        wifiInfoText = TextView(this).apply {
            text = "📶 Wi-Fi: Mendeteksi..."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnBench = Button(this).apply {
            text = "⚡ Tes CCTV"
            setAllCaps(false)
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = roundedRect(Color.parseColor("#0B2925"), dp(6), Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(dp(86), dp(32)).apply { marginEnd = dp(6) }
            setOnClickListener {
                runCctvBenchmark { res ->
                    try {
                        val f = java.io.File(filesDir, "bench_result.txt")
                        f.writeText(res)
                    } catch (ignored: Exception) {}
                }
            }
        }
        val btnOpenSettings = Button(this).apply {
            text = "Aturan ⚙"
            setAllCaps(false)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(32))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        infoBox.addView(wifiInfoText)
        infoBox.addView(btnBench)
        infoBox.addView(btnOpenSettings)
        content.addView(infoBox)

        scrollView.addView(content)
        root.addView(scrollView)

        setContentView(root)
    }

    private fun createModeCard(
        title: String,
        description: String,
        badge: String,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val radio = TextView(this).apply {
            tag = "radio_indicator"
            text = "○"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, dp(10), 0)
        }
        topRow.addView(radio)

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleTv)

        val badgeTv = TextView(this).apply {
            text = badge
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.TRANSPARENT)
        }
        topRow.addView(badgeTv)
        card.addView(topRow)

        val descTv = TextView(this).apply {
            text = description
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(28), dp(4), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        card.addView(descTv)

        return card
    }

    private fun setRoutingMode(mode: String, restartIfConnected: Boolean) {
        routingMode = mode
        prefs.edit().putString("routingMode", mode).apply()
        refreshUiState()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = if (mode == "secure") "🔒 Secure Full Tunnel (0.0.0.0/0)" else "Split Tunnel"
            Toast.makeText(this, "Berpindah ke $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransportMode(mode: String, restartIfConnected: Boolean) {
        transportMode = mode
        prefs.edit().putString("transportMode", mode).apply()
        refreshUiState()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = when (mode) {
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
            btn.background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.TRANSPARENT)
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
        val srv = serverHost.ifBlank { "168.110.193.226" }
        val ip = assignedIp.ifBlank { "10.10.0.3" }
        val subnetsCsv = if (activeSubnetsCidr.isEmpty()) "10.10.0.0/24,192.168.101.0/24,10.0.0.0/24" else activeSubnetsCidr.joinToString(",")
        val subnetNames = getSelectedSubnetNamesSummary()

        val startIntent = Intent(this, StabirushVpnService::class.java).apply {
            action = StabirushVpnService.ACTION_CONNECT
            putExtra("serverHost", srv)
            putExtra("assignedIp", ip)
            putExtra("routingMode", routingMode)
            putExtra("transportMode", transportMode)
            putExtra("activeSubnets", subnetsCsv)
            putExtra("activeSubnetNames", subnetNames)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }

        mainHandler.postDelayed({ refreshUiState() }, 400)
    }

    private fun getSelectedSubnetNamesSummary(): String {
        if (activeSubnetsCidr.isEmpty()) return "Semua Subnet Private"
        val matchedNames = mutableListOf<String>()
        for (c in activeSubnetsCidr) {
            val found = namedSubnets.find { it.cidr.equals(c, ignoreCase = true) }
            matchedNames.add(found?.name ?: c)
        }
        return matchedNames.joinToString(", ")
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.2f MB", mb)
    }

    private fun refreshUiState() {
        val running = StabirushVpnService.isRunning
        val isSecure = routingMode.equals("secure", ignoreCase = true)
        val isVerified = StabirushVpnService.isVerified
        val rxBytes = StabirushVpnService.rxBytes.get()
        val txBytes = StabirushVpnService.txBytes.get()
        val rxPackets = StabirushVpnService.rxPackets.get()
        val txPackets = StabirushVpnService.txPackets.get()
        val isFallback = StabirushVpnService.isFallbackActive
        val activeTrans = StabirushVpnService.activeTransport

        // ONLY considered connected if both running AND two-way data succeeded (RX > 0)!
        val isConnected = running && isVerified && rxBytes > 0

        // 1. Status Badge
        if (isConnected) {
            statusBadgeText.text = if (isSecure) "SECURE MESH" else "SPLIT ACTIVE"
            statusBadgeText.setTextColor(Color.parseColor("#10B981"))
            statusBadgeBg.setColor(Color.parseColor("#064E3B"))
            statusBadgeBg.setStroke(dp(1), Color.parseColor("#10B981"))
        } else if (running) {
            statusBadgeText.text = if (isFallback) "FALLBACK TCP" else "VERIFIKASI TRAFIK"
            statusBadgeText.setTextColor(Color.parseColor("#F59E0B"))
            statusBadgeBg.setColor(Color.parseColor("#451A03"))
            statusBadgeBg.setStroke(dp(1), Color.parseColor("#F59E0B"))
        } else {
            statusBadgeText.text = "ZERO TRUST"
            statusBadgeText.setTextColor(Color.parseColor("#94A3B8"))
            statusBadgeBg.setColor(Color.parseColor("#1E293B"))
            statusBadgeBg.setStroke(dp(1), Color.parseColor("#334155"))
        }

        // 2. Central Hero Toggle Card
        if (isConnected) {
            toggleCardBg.setColor(Color.parseColor("#062B28"))
            toggleCardBg.setStroke(dp(2), Color.parseColor("#10B981"))

            toggleCircleBg.setColor(Color.parseColor("#10B981"))
            toggleCircleBg.setStroke(dp(3), Color.parseColor("#34D399"))
            toggleIcon.text = "✓"
            toggleIcon.setTextColor(Color.WHITE)

            toggleTitle.text = if (isSecure) "SECURE TUNNEL AKTIF" else "SPLIT TUNNEL AKTIF"
            toggleTitle.setTextColor(Color.parseColor("#10B981"))

            val pingStr = if (StabirushVpnService.lastPingMs >= 0) "${StabirushVpnService.lastPingMs} ms" else "<1 ms"
            val modeStr = if (isSecure) "Full Tunnel 0.0.0.0/0" else getSelectedSubnetNamesSummary()
            toggleSubtitle.text = "Koneksi terenkripsi 2-Arah · $modeStr"

            toggleIpBadge.text = "⚡ $assignedIp · gateway: 10.10.0.1 · $pingStr"
            toggleIpBadge.setTextColor(Color.parseColor("#10B981"))
            (toggleIpBadge.background as? GradientDrawable)?.setColor(Color.parseColor("#0F3D37"))

            toggleTrafficStats.visibility = View.VISIBLE
            toggleTrafficStats.text = "↑ ${formatBytes(txBytes)} ($txPackets pkts)  ·  ↓ ${formatBytes(rxBytes)} ($rxPackets pkts)"

            toggleTransportBadge.visibility = View.VISIBLE
            if (isFallback || activeTrans.contains("tcp")) {
                toggleTransportBadge.text = "🛡️ Protocol: TCP/TLS 443 (Fallback Aktif)"
                toggleTransportBadge.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                toggleTransportBadge.text = "⚡ Protocol: WireGuard (UDP:51820 Noise_IK)"
                toggleTransportBadge.setTextColor(Color.parseColor("#10B981"))
            }
        } else if (running) {
            toggleCardBg.setColor(Color.parseColor("#1C1917"))
            toggleCardBg.setStroke(dp(2), Color.parseColor("#F59E0B"))

            toggleCircleBg.setColor(Color.parseColor("#D97706"))
            toggleCircleBg.setStroke(dp(3), Color.parseColor("#FBBF24"))
            toggleIcon.text = "⏳"
            toggleIcon.setTextColor(Color.WHITE)

            toggleTitle.text = "MEMVERIFIKASI TRAFIK..."
            toggleTitle.setTextColor(Color.parseColor("#F59E0B"))

            if (isFallback) {
                toggleSubtitle.text = "⚠️ UDP RX nol (terblokir ISP). Beralih ke TCP/TLS 443..."
            } else {
                toggleSubtitle.text = "Menguji kirim & terima data (Send & Receive)..."
            }

            toggleIpBadge.text = "⚡ $assignedIp · Menghubungkan ke $serverHost"
            toggleIpBadge.setTextColor(Color.parseColor("#F59E0B"))
            (toggleIpBadge.background as? GradientDrawable)?.setColor(Color.parseColor("#292524"))

            toggleTrafficStats.visibility = View.VISIBLE
            toggleTrafficStats.text = "↑ ${formatBytes(txBytes)} ($txPackets pkts)  ·  ↓ ${formatBytes(rxBytes)} (Menunggu respon...)"

            toggleTransportBadge.visibility = View.VISIBLE
            toggleTransportBadge.text = if (isFallback) "Mengalihkan ke TCP 443..." else "Menguji port UDP..."
            toggleTransportBadge.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            toggleCardBg.setColor(Color.parseColor("#0F172A"))
            toggleCardBg.setStroke(dp(1), Color.parseColor("#1E293B"))

            toggleCircleBg.setColor(Color.parseColor("#1E293B"))
            toggleCircleBg.setStroke(dp(2), Color.parseColor("#334155"))
            toggleIcon.text = "⏻"
            toggleIcon.setTextColor(Color.parseColor("#94A3B8"))

            toggleTitle.text = "DISCONNECTED"
            toggleTitle.setTextColor(Color.WHITE)
            toggleSubtitle.text = "Ketuk untuk terhubung ke jaringan Stabirush WireGuard"

            toggleIpBadge.text = "Perangkat: $selectedPeerName ($assignedIp)"
            toggleIpBadge.setTextColor(Color.parseColor("#38BDF8"))
            (toggleIpBadge.background as? GradientDrawable)?.setColor(Color.parseColor("#132A4A"))

            toggleTrafficStats.visibility = View.GONE
            toggleTransportBadge.visibility = View.GONE
        }

        // 3. Mode Cards Radio Styling
        if (isSecure) {
            radioSecure.text = "●"
            radioSecure.setTextColor(Color.parseColor("#38BDF8"))
            cardSecureMode.background = roundedRect(Color.parseColor("#0C2340"), dp(16), Color.parseColor("#38BDF8"))

            radioSplit.text = "○"
            radioSplit.setTextColor(Color.parseColor("#64748B"))
            cardSplitMode.background = roundedRect(Color.parseColor("#0F172A"), dp(16), Color.parseColor("#1E293B"))
        } else {
            radioSplit.text = "●"
            radioSplit.setTextColor(Color.parseColor("#10B981"))
            cardSplitMode.background = roundedRect(Color.parseColor("#0B2925"), dp(16), Color.parseColor("#10B981"))

            radioSecure.text = "○"
            radioSecure.setTextColor(Color.parseColor("#64748B"))
            cardSecureMode.background = roundedRect(Color.parseColor("#0F172A"), dp(16), Color.parseColor("#1E293B"))
        }

        // 4. Transport Buttons Styling
        val curTrans = prefs.getString("transportMode", "auto") ?: "auto"
        styleTransportButton(btnTransAuto, curTrans == "auto")
        styleTransportButton(btnTransUdp, curTrans == "udp" || curTrans == "wireguard")
        styleTransportButton(btnTransTcp, curTrans == "wstunnel" || curTrans == "tcp")
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
            if (wm != null) {
                val info = wm.connectionInfo
                if (info != null && info.ssid != null) {
                    var raw = info.ssid
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                        raw = raw.substring(1, raw.length - 1)
                    }
                    if (!raw.equals("<unknown ssid>", ignoreCase = true)) {
                        return raw
                    }
                }
            }
        } catch (ignored: Exception) {}
        return ""
    }

    private fun evaluateWifiSsidRules(applySwitch: Boolean) {
        val ssid = getCurrentWifiSsid()
        val displaySsid = if (ssid.isEmpty()) "Seluler / Wi-Fi Lain" else ssid
        wifiInfoText.text = "📶 Wi-Fi: $displaySsid"

        val autoSwitchEnabled = prefs.getBoolean("autoWifiSwitchEnabled", false)
        if (!autoSwitchEnabled || !applySwitch) {
            return
        }

        var matched: WifiRule? = null
        for (r in wifiRules) {
            if (ssid.isNotEmpty() && matchesAnyPattern(ssid, r.ssid)) {
                matched = r
                break
            }
        }
        if (matched == null) {
            for (r in wifiRules) {
                if (r.ssid.trim() == "*") {
                    matched = r
                    break
                }
            }
        }

        if (matched != null) {
            val targetAction = matched.profileMode
            val targetSubnets = matched.subnets
            if (targetAction == "disconnect") {
                if (StabirushVpnService.isRunning) {
                    val stopIntent = Intent(this, StabirushVpnService::class.java).apply {
                        action = StabirushVpnService.ACTION_DISCONNECT
                    }
                    startService(stopIntent)
                }
            } else if (targetAction == "secure") {
                setRoutingMode("secure", restartIfConnected = true)
            } else if (targetAction == "split") {
                if (targetSubnets.isNotEmpty()) {
                    activeSubnetsCidr.clear()
                    activeSubnetsCidr.addAll(targetSubnets)
                    prefs.edit().putString("activeSubnets", activeSubnetsCidr.joinToString(",")).apply()
                }
                setRoutingMode("split", restartIfConnected = true)
            }
        }
    }

    private fun matchesAnyPattern(ssid: String, rawList: String): Boolean {
        if (rawList.isBlank()) return false
        val parts = rawList.split(",")
        for (p in parts) {
            val pat = p.trim()
            if (pat.isEmpty()) continue
            if (pat == "*") return true
            if (pat.endsWith("*")) {
                val prefix = pat.substring(0, pat.length - 1).lowercase()
                if (ssid.lowercase().startsWith(prefix)) return true
            } else if (ssid.equals(pat, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun syncFromServer(showToastMsg: Boolean) {
        val srv = serverHost.ifBlank { "168.110.193.226" }
        val ip = assignedIp.ifBlank { "10.10.0.3" }
        val token = prefs.getString("authToken", "") ?: ""

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val hosts = if (srv == "10.10.0.1") arrayOf("10.10.0.1", "168.110.193.226") else arrayOf(srv, "10.10.0.1")
                var ok = false
                for (h in hosts) {
                    try {
                        val cleanH = h.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        val proto = if (cleanH.matches(Regex("^[0-9.]+$"))) "http" else "https"
                        val path = "$proto://$cleanH/api/android/sync?key=" +
                                URLEncoder.encode(selectedPubKey, "UTF-8") +
                                "&ip=" + URLEncoder.encode(ip, "UTF-8")
                        conn = URL(path).openConnection() as HttpURLConnection
                        if (token.isNotEmpty()) {
                            conn.setRequestProperty("X-WG-Token", token)
                        }
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        if (conn.responseCode == 200) {
                            ok = true
                            break
                        } else if (conn.responseCode == 401) {
                            prefs.edit().remove("authToken").apply()
                            mainHandler.post {
                                Toast.makeText(this, "Sesi login berakhir. Silakan login kembali.", Toast.LENGTH_SHORT).show()
                                val i = Intent(this, LoginActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }
                                startActivity(i)
                                finish()
                            }
                            return@Thread
                        }
                        conn.disconnect()
                    } catch (ignored: Exception) {
                        conn?.disconnect()
                    }
                }
                if (!ok || conn == null) throw Exception("Server tidak dapat dijangkau")

                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line)
                br.close()

                val root = JSONObject(sb.toString())
                val ver = root.optLong("configVersion", 0L)

                val ed = prefs.edit()
                ed.putLong("configVersion", ver)

                val srvPubKey = root.optString("serverPublicKey", "")
                if (srvPubKey.isNotEmpty()) {
                    ed.putString("serverPublicKey", srvPubKey)
                }

                val nsArr = root.optJSONArray("namedSubnets")
                if (nsArr != null && nsArr.length() > 0) {
                    ed.putString("namedSubnetsJson", nsArr.toString())
                }

                val activeDev = root.optJSONObject("activeDevice")
                if (activeDev != null) {
                    selectedPubKey = activeDev.optString("publicKey", selectedPubKey)
                    selectedPeerName = activeDev.optString("name", selectedPeerName)
                    val devIp = activeDev.optString("allocatedIP", ip)
                    ed.putString("selectedPubKey", selectedPubKey)
                    ed.putString("selectedPeerName", selectedPeerName)
                    ed.putString("assignedIp", devIp)

                    val wrArr = activeDev.optJSONArray("wifiRules")
                    if (wrArr != null && wrArr.length() > 0) {
                        ed.putString("wifiRulesJson", wrArr.toString())
                    }
                }
                ed.apply()

                mainHandler.post {
                    loadModelsFromPrefs()
                    evaluateWifiSsidRules(applySwitch = false)
                    refreshUiState()
                    if (showToastMsg) {
                        Toast.makeText(this@MainActivity, "Berhasil sinkronisasi pengaturan dari Web UI!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (showToastMsg) {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Gagal sync dari server: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun runCctvBenchmark(onComplete: (String) -> Unit) {
        val curTrans = transportMode
        val curMode = routingMode
        Toast.makeText(this, "Menguji CCTV 10.0.0.154 ($curTrans)...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val targetHost = "10.0.0.154"
                val targetPort = 8090
                val targetUrl = "http://10.0.0.154:8090/"

                android.util.Log.d("StabirushBench", "START_BENCHMARK: trans=$curTrans mode=$curMode target=$targetHost:$targetPort")

                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val vpnNet = cm?.allNetworks?.firstOrNull { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
                android.util.Log.d("StabirushBench", "Detected active VPN network: $vpnNet")

                // 1. TCP Handshake RTT Test (5 sampel)
                val tcpLatencies = mutableListOf<Long>()
                for (i in 1..5) {
                    val t0 = System.nanoTime()
                    try {
                        val s = Socket()
                        vpnNet?.bindSocket(s)
                        s.connect(InetSocketAddress(targetHost, targetPort), 3000)
                        val t1 = System.nanoTime()
                        s.close()
                        val ms = (t1 - t0) / 1_000_000L
                        tcpLatencies.add(ms)
                    } catch (e: Exception) {
                        android.util.Log.w("StabirushBench", "Handshake #$i error: ${e.message}")
                    }
                    Thread.sleep(80)
                }

                val tcpMin = tcpLatencies.minOrNull() ?: 0L
                val tcpAvg = if (tcpLatencies.isNotEmpty()) tcpLatencies.average() else 0.0
                val tcpMax = tcpLatencies.maxOrNull() ?: 0L

                // 2. HTTP Request & Throughput Download Test (5 iterasi payload ~119KB)
                val ttfbList = mutableListOf<Long>()
                val downloadSpeedsKbps = mutableListOf<Double>()
                var totalBytesReceived = 0L
                val durations = mutableListOf<Long>()

                for (i in 1..5) {
                    val tStart = System.nanoTime()
                    try {
                        val url = URL(targetUrl)
                        val conn = (if (vpnNet != null) vpnNet.openConnection(url) else url.openConnection()) as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("User-Agent", "StabirushBench/1.0")
                        conn.setRequestProperty("Connection", "close")

                        val code = conn.responseCode
                        val tHeaders = System.nanoTime()
                        val ttfb = (tHeaders - tStart) / 1_000_000L
                        ttfbList.add(ttfb)

                        val stream = conn.inputStream
                        val buf = ByteArray(16384)
                        var bytesRead = 0L
                        while (true) {
                            val n = stream.read(buf)
                            if (n == -1) break
                            bytesRead += n
                        }
                        stream.close()
                        conn.disconnect()

                        val tEnd = System.nanoTime()
                        val totalMs = (tEnd - tStart) / 1_000_000L
                        durations.add(totalMs)
                        totalBytesReceived += bytesRead

                        val dlSec = (tEnd - tHeaders) / 1_000_000_000.0
                        val speedKbps = if (dlSec > 0) (bytesRead * 8.0) / (dlSec * 1000.0) else 0.0
                        downloadSpeedsKbps.add(speedKbps)
                        android.util.Log.d("StabirushBench", "HTTP #$i code=$code bytes=$bytesRead ttfb=${ttfb}ms total=${totalMs}ms speed=${speedKbps.toInt()}kbps")
                    } catch (e: Exception) {
                        android.util.Log.w("StabirushBench", "HTTP #$i error: ${e.message}")
                    }
                    Thread.sleep(100)
                }

                val ttfbMin = ttfbList.minOrNull() ?: 0L
                val ttfbAvg = ttfbList.average()
                val ttfbMax = ttfbList.maxOrNull() ?: 0L
                val avgDuration = durations.average()
                val avgSpeedMbps = (downloadSpeedsKbps.average()) / 1000.0
                val payloadKb = (totalBytesReceived / 5.0) / 1024.0

                val summary = String.format(
                    java.util.Locale.US,
                    "RESULT|trans=%s|mode=%s|tcp_min=%d|tcp_avg=%.1f|tcp_max=%d|ttfb_min=%d|ttfb_avg=%.1f|ttfb_max=%d|payload_kb=%.1f|speed_mbps=%.2f|duration_ms=%.1f",
                    curTrans, curMode, tcpMin, tcpAvg, tcpMax, ttfbMin, ttfbAvg, ttfbMax, payloadKb, avgSpeedMbps, avgDuration
                )

                android.util.Log.d("StabirushBench", summary)
                mainHandler.post {
                    Toast.makeText(this, "Benchmark CCTV ($curTrans):\nTCP: ${tcpAvg.toInt()}ms | Speed: ${"%.1f".format(avgSpeedMbps)} Mbps", Toast.LENGTH_LONG).show()
                    onComplete(summary)
                }
            } catch (e: Exception) {
                android.util.Log.e("StabirushBench", "Benchmark error: ${e.message}", e)
                mainHandler.post {
                    Toast.makeText(this, "Tes CCTV Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    }

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(bgColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }
}
