package network.stabirush.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverHost: String = "168.110.193.226"
    private var assignedIp: String = "10.10.0.3"
    private var selectedPubKey: String = ""
    private var selectedPeerName: String = "Android Device"
    private var transportMode: String = "auto"
    private var routingMode: String = "split"

    private val namedSubnets = mutableListOf<NamedSubnet>()
    private val activeSubnetsCidr = mutableListOf<String>()
    private val wifiRules = mutableListOf<WifiRule>()

    private lateinit var tvPeerSummary: TextView
    private lateinit var subnetsContainer: LinearLayout
    private lateinit var wifiRulesContainer: LinearLayout
    private lateinit var tvSyncStatus: TextView

    private lateinit var btnTransAuto: Button
    private lateinit var btnTransUdp: Button
    private lateinit var btnTransWstunnel: Button
    private lateinit var btnTransUdp2raw: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)
        loadData()
        buildUI()
    }

    private fun loadData() {
        serverHost = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        assignedIp = prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        selectedPubKey = prefs.getString("selectedPubKey", "") ?: ""
        selectedPeerName = prefs.getString("selectedPeerName", "Android Device") ?: "Android Device"
        transportMode = prefs.getString("transportMode", "auto") ?: "auto"
        routingMode = prefs.getString("routingMode", "split") ?: "split"

        // Named Subnets
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

    private fun saveData() {
        val ed = prefs.edit()
        ed.putString("serverHost", serverHost)
        ed.putString("assignedIp", assignedIp)
        ed.putString("selectedPubKey", selectedPubKey)
        ed.putString("selectedPeerName", selectedPeerName)
        ed.putString("transportMode", transportMode)
        ed.putString("routingMode", routingMode)
        ed.putString("activeSubnets", activeSubnetsCidr.joinToString(","))

        val nsArr = JSONArray()
        namedSubnets.forEach { nsArr.put(it.toJsonObject()) }
        ed.putString("namedSubnetsJson", nsArr.toString())

        val wrArr = JSONArray()
        wifiRules.forEach { wrArr.put(it.toJsonObject()) }
        ed.putString("wifiRulesJson", wrArr.toString())

        ed.apply()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080D1A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#0B132B"))
        }

        val btnBack = Button(this).apply {
            text = "← Kembali"
            setAllCaps(false)
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundedRect(Color.parseColor("#1C2541"), dp(8), Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(36))
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        val titleTv = TextView(this).apply {
            text = "PENGATURAN"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleTv)

        // Spacer to balance back button
        val dummy = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(36))
        }
        header.addView(dummy)

        root.addView(header)

        // Scrollable content
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Section 1: Profil Perangkat & Server
        content.addView(createSectionHeader("1. AKUN PENGGUNA & PERANGKAT"))
        val cardPeer = createCard()
        tvPeerSummary = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        updatePeerSummaryText()
        cardPeer.addView(tvPeerSummary)

        val btnRowPeer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnSyncUser = createSecondaryButton("🔄 Sinkron Profil") {
            pullSyncFromServer()
        }
        val btnLogout = Button(this).apply {
            text = "🚪 Keluar (Logout)"
            setAllCaps(false)
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(Color.parseColor("#1C1917"), dp(8), Color.parseColor("#7F1D1D"))
            setOnClickListener { performLogout() }
        }
        val halfLp1 = LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) }
        val halfLp2 = LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(4) }
        btnRowPeer.addView(btnSyncUser, halfLp1)
        btnRowPeer.addView(btnLogout, halfLp2)
        cardPeer.addView(btnRowPeer)
        content.addView(cardPeer)

        // Section 2: Split Tunnel Subnets
        content.addView(createSectionHeader("2. SUBNET YANG DITUNNEL (SPLIT MODE)"))
        content.addView(createSubHint("Pilih subnet yang akan diarahkan ke VPN Stabirush saat Split Tunnel aktif:"))
        val cardSubnets = createCard()
        subnetsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        cardSubnets.addView(subnetsContainer)

        val btnAddSubnet = createSecondaryButton("+ Tambah Nama Subnet Baru") {
            showAddSubnetDialog()
        }
        val lpAddSub = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(8)
        }
        cardSubnets.addView(btnAddSubnet, lpAddSub)
        content.addView(cardSubnets)
        renderSubnetsUI()

        // Section 3: Wi-Fi SSID Auto-Switch Rules
        content.addView(createSectionHeader("3. ATURAN AUTO-SWITCH WI-FI SSID"))
        content.addView(createSubHint("Otomatis ubah profil/mode saat tersambung ke Wi-Fi tertentu:"))
        val cardWifi = createCard()

        val autoSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val cbAutoSwitch = CheckBox(this).apply {
            text = "Aktifkan Auto-Switch Wi-Fi SSID"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = prefs.getBoolean("autoWifiSwitchEnabled", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("autoWifiSwitchEnabled", isChecked).apply()
                Toast.makeText(this@SettingsActivity, if (isChecked) "Auto-Switch Wi-Fi diaktifkan" else "Auto-Switch Wi-Fi dimatikan", Toast.LENGTH_SHORT).show()
            }
        }
        autoSwitchRow.addView(cbAutoSwitch)
        cardWifi.addView(autoSwitchRow)

        wifiRulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        cardWifi.addView(wifiRulesContainer)

        val btnAddRule = createSecondaryButton("+ Tambah Aturan SSID Baru") {
            showAddWifiRuleDialog()
        }
        val lpAddRule = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(8)
        }
        cardWifi.addView(btnAddRule, lpAddRule)
        content.addView(cardWifi)
        renderWifiRulesUI()

        // Section 4: Protokol Transport
        content.addView(createSectionHeader("4. PROTOKOL & AUTO-FALLBACK"))
        content.addView(createSubHint("Pilih mekanisme transport koneksi tunnel:"))
        val cardTrans = createCard()
        val transGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        btnTransAuto = createSegmentButton("Auto (Fallback)", 0.36f) { setTransport("auto") }
        btnTransUdp = createSegmentButton("Fast UDP", 0.32f) { setTransport("udp") }
        btnTransWstunnel = createSegmentButton("TCP / TLS 443", 0.32f) { setTransport("wstunnel") }
        btnTransUdp2raw = Button(this).apply { visibility = View.GONE }
        transGrid.addView(btnTransAuto)
        transGrid.addView(btnTransUdp)
        transGrid.addView(btnTransWstunnel)
        cardTrans.addView(transGrid)

        val transNote = TextView(this).apply {
            text = "ℹ️ Mode Auto akan mencoba UDP (51821) terlebih dahulu. Jika paket receive bernilai ZERO / timeout (diblokir ISP), aplikasi langsung otomatis beralih ke TCP/TLS Port 443."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), dp(10), dp(4), dp(2))
        }
        cardTrans.addView(transNote)
        content.addView(cardTrans)
        updateTransportButtons()

        // Section 5: Sinkronisasi Cloud
        content.addView(createSectionHeader("5. SINKRONISASI CLOUD DUA ARAH"))
        val cardSync = createCard()
        tvSyncStatus = TextView(this).apply {
            val ver = prefs.getLong("configVersion", 0L)
            text = "Status: Versi Konfigurasi Server v$ver"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        cardSync.addView(tvSyncStatus)

        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnPull = createSecondaryButton("☁️ Tarik dari Web UI") {
            pullSyncFromServer()
        }
        val btnPush = createSecondaryButton("⬆️ Simpan ke Web UI") {
            pushSyncToServer()
        }
        syncRow.addView(btnPull, halfLp1)
        syncRow.addView(btnPush, halfLp2)
        cardSync.addView(syncRow)
        content.addView(cardSync)

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun updatePeerSummaryText() {
        val uName = prefs.getString("username", "beni") ?: "beni"
        val tok = prefs.getString("authToken", "") ?: ""
        val tokShort = if (tok.length > 16) tok.substring(0, 14) + "..." else "Sesi Aktif"
        tvPeerSummary.text = "Akun User : $uName\n" +
                "Perangkat : $selectedPeerName\n" +
                "IP Tunnel : $assignedIp\n" +
                "Server    : $serverHost\n" +
                "Sesi Token: $tokShort"
    }

    private fun performLogout() {
        val uName = prefs.getString("username", "beni") ?: "beni"
        AlertDialog.Builder(this)
            .setTitle("Keluar Akun")
            .setMessage("Apakah Anda yakin ingin keluar dari akun $uName? Koneksi VPN akan diputus.")
            .setPositiveButton("Ya, Keluar") { _, _ ->
                val stopIntent = Intent(this, StabirushVpnService::class.java).apply {
                    action = StabirushVpnService.ACTION_DISCONNECT
                }
                startService(stopIntent)

                val tok = prefs.getString("authToken", "") ?: ""
                Thread {
                    try {
                        val conn = URL("http://$serverHost/api/auth/logout").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("X-WG-Token", tok)
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.responseCode
                    } catch (ignored: Exception) {}
                }.start()

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

    private fun renderSubnetsUI() {
        subnetsContainer.removeAllViews()
        for (ns in namedSubnets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundedRect(Color.parseColor("#0B0F19"), dp(8), Color.parseColor("#1E293B"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }

            val cb = CheckBox(this).apply {
                isChecked = activeSubnetsCidr.contains(ns.cidr)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!activeSubnetsCidr.contains(ns.cidr)) activeSubnetsCidr.add(ns.cidr)
                    } else {
                        activeSubnetsCidr.remove(ns.cidr)
                    }
                    saveData()
                }
            }
            row.addView(cb)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply {
                text = ns.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this).apply {
                text = "${ns.cidr} · ${ns.description}"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
            }
            infoCol.addView(title)
            infoCol.addView(sub)
            row.addView(infoCol)

            subnetsContainer.addView(row)
        }
    }

    private fun renderWifiRulesUI() {
        wifiRulesContainer.removeAllViews()
        if (wifiRules.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Belum ada aturan SSID khusus (Mode default akan digunakan)"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(4))
            }
            wifiRulesContainer.addView(emptyTv)
            return
        }

        for (i in 0 until wifiRules.size) {
            val rule = wifiRules[i]
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedRect(Color.parseColor("#0B0F19"), dp(8), Color.parseColor("#1E293B"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val ssidTv = TextView(this).apply {
                text = "📶 SSID: ${rule.ssid}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(ssidTv)

            val btnDel = Button(this).apply {
                text = "✕"
                setAllCaps(false)
                setTextColor(Color.parseColor("#EF4444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.parseColor("#EF4444"))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(28))
                setOnClickListener {
                    wifiRules.removeAt(i)
                    saveData()
                    renderWifiRulesUI()
                }
            }
            topRow.addView(btnDel)
            box.addView(topRow)

            val modeTv = TextView(this).apply {
                text = "Mode: ${formatModeLabel(rule.profileMode)}"
                setTextColor(Color.parseColor("#10B981"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, dp(2), 0, 0)
            }
            box.addView(modeTv)

            wifiRulesContainer.addView(box)
        }
    }

    private fun formatModeLabel(mode: String): String = when (mode.lowercase()) {
        "secure" -> "🔒 Secure Full Tunnel (0.0.0.0/0)"
        "disconnect" -> "○ Disconnect (Direct LAN)"
        "bypass_wstunnel" -> "⚡ TCP Bypass wstunnel (:8443)"
        "bypass_udp2raw" -> "⚡ TCP Bypass udp2raw (:4096)"
        else -> "Split Tunnel"
    }

    private fun showAddSubnetDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val etName = EditText(this).apply {
            hint = "Nama Subnet (contoh: LAN Kantor Baru)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val etCidr = EditText(this).apply {
            hint = "CIDR (contoh: 192.168.102.0/24)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        dialogView.addView(etName)
        dialogView.addView(etCidr)

        AlertDialog.Builder(this)
            .setTitle("Tambah Named Subnet")
            .setView(dialogView)
            .setPositiveButton("Simpan") { _, _ ->
                val name = etName.text.toString().trim()
                val cidr = etCidr.text.toString().trim()
                if (name.isNotEmpty() && cidr.isNotEmpty()) {
                    val ns = NamedSubnet("sub-${System.currentTimeMillis()}", name, cidr)
                    namedSubnets.add(ns)
                    activeSubnetsCidr.add(cidr)
                    saveData()
                    renderSubnetsUI()
                    pushSyncToServer()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddWifiRuleDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val etSsid = EditText(this).apply {
            hint = "Nama Wi-Fi SSID (contoh: WiFi_Kantor)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        dialogView.addView(etSsid)

        val modes = arrayOf("Split Tunnel", "🔒 Secure Full Tunnel", "○ Disconnect (Direct LAN)", "⚡ wstunnel (:8443)")
        val modeValues = arrayOf("split", "secure", "disconnect", "bypass_wstunnel")
        var selectedIdx = 0

        AlertDialog.Builder(this)
            .setTitle("Tambah Aturan Wi-Fi SSID")
            .setView(dialogView)
            .setSingleChoiceItems(modes, selectedIdx) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton("Simpan") { _, _ ->
                val ssid = etSsid.text.toString().trim()
                if (ssid.isNotEmpty()) {
                    val rule = WifiRule(ssid, modeValues[selectedIdx])
                    wifiRules.add(rule)
                    saveData()
                    renderWifiRulesUI()
                    pushSyncToServer()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showEditServerDialog() {
        val et = EditText(this).apply {
            setText(serverHost)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Ubah Server Host")
            .setMessage("Masukkan IP publik / domain server Stabirush:")
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val input = et.text.toString().trim()
                if (input.isNotEmpty()) {
                    serverHost = input
                    saveData()
                    updatePeerSummaryText()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setTransport(t: String) {
        transportMode = t
        saveData()
        updateTransportButtons()
        Toast.makeText(this, "Transport disetel ke ${t.uppercase()}", Toast.LENGTH_SHORT).show()
    }

    private fun updateTransportButtons() {
        styleSegmentButton(btnTransAuto, transportMode == "auto")
        styleSegmentButton(btnTransUdp, transportMode == "udp")
        styleSegmentButton(btnTransWstunnel, transportMode == "wstunnel")
        styleSegmentButton(btnTransUdp2raw, transportMode == "udp2raw")
    }

    private fun styleSegmentButton(btn: Button, active: Boolean) {
        if (active) {
            btn.setTextColor(Color.WHITE)
            btn.background = roundedRect(Color.parseColor("#0284C7"), dp(6), Color.parseColor("#38BDF8"))
        } else {
            btn.setTextColor(Color.parseColor("#94A3B8"))
            btn.background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.TRANSPARENT)
        }
    }

    private fun fetchPeersFromServer() {
        Toast.makeText(this, "Mengambil daftar peer dari $serverHost...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val tok = prefs.getString("authToken", "") ?: ""
                val url = URL("http://$serverHost/api/android/sync")
                val conn = url.openConnection() as HttpURLConnection
                if (tok.isNotEmpty()) {
                    conn.setRequestProperty("X-WG-Token", tok)
                }
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line)
                br.close()

                val root = JSONObject(sb.toString())
                val arr = root.optJSONArray("devices") ?: JSONArray()
                val titles = mutableListOf<String>()
                val ips = mutableListOf<String>()
                val keys = mutableListOf<String>()
                val names = mutableListOf<String>()

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optString("name", "Peer")
                    val ip = o.optString("allocatedIP", "10.10.0.3")
                    val pk = o.optString("publicKey", "")
                    titles.add("$n ($ip)")
                    ips.add(ip)
                    keys.add(pk)
                    names.add(n)
                }

                mainHandler.post {
                    if (titles.isEmpty()) {
                        Toast.makeText(this, "Tidak ada peer ditemukan", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Pilih Profil Peer Android")
                        .setItems(titles.toTypedArray()) { _, which ->
                            selectedPeerName = names[which]
                            assignedIp = ips[which]
                            selectedPubKey = keys[which]
                            saveData()
                            updatePeerSummaryText()
                            Toast.makeText(this, "Profil disetel ke $selectedPeerName", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Gagal mengambil peer: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun pullSyncFromServer() {
        Toast.makeText(this, "Menghubungi server $serverHost...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val tok = prefs.getString("authToken", "") ?: ""
                val cleanH = serverHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val proto = if (cleanH.matches(Regex("^[0-9.]+$"))) "http" else "https"
                val qUrl = "$proto://$cleanH/api/android/sync?key=" +
                        URLEncoder.encode(selectedPubKey, "UTF-8") +
                        "&ip=" + URLEncoder.encode(assignedIp, "UTF-8")
                val conn = URL(qUrl).openConnection() as HttpURLConnection
                if (tok.isNotEmpty()) {
                    conn.setRequestProperty("X-WG-Token", tok)
                }
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line)
                br.close()

                val root = JSONObject(sb.toString())
                val ver = root.optLong("configVersion", 0L)
                prefs.edit().putLong("configVersion", ver).apply()

                val nsArr = root.optJSONArray("namedSubnets")
                if (nsArr != null) {
                    namedSubnets.clear()
                    for (i in 0 until nsArr.length()) {
                        namedSubnets.add(NamedSubnet.fromJsonObject(nsArr.getJSONObject(i)))
                    }
                }

                val activeDev = root.optJSONObject("activeDevice")
                if (activeDev != null) {
                    selectedPeerName = activeDev.optString("name", selectedPeerName)
                    assignedIp = activeDev.optString("allocatedIP", assignedIp)
                    val wrArr = activeDev.optJSONArray("wifiRules")
                    if (wrArr != null) {
                        wifiRules.clear()
                        for (i in 0 until wrArr.length()) {
                            wifiRules.add(WifiRule.fromJsonObject(wrArr.getJSONObject(i)))
                        }
                    }
                }
                saveData()

                mainHandler.post {
                    tvSyncStatus.text = "Status: Berhasil Synced v$ver"
                    updatePeerSummaryText()
                    renderSubnetsUI()
                    renderWifiRulesUI()
                    Toast.makeText(this, "☁️ Berhasil sinkronisasi dari Web Server!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Gagal sync: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun pushSyncToServer() {
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("publicKey", selectedPubKey)
                    put("routingMode", routingMode)
                    put("transportMode", transportMode)
                    val nsArr = JSONArray()
                    namedSubnets.forEach { nsArr.put(it.toJsonObject()) }
                    put("namedSubnets", nsArr)
                    val wrArr = JSONArray()
                    wifiRules.forEach { wrArr.put(it.toJsonObject()) }
                    put("wifiRules", wrArr)
                }

                val tok = prefs.getString("authToken", "") ?: ""
                val cleanH = serverHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val proto = if (cleanH.matches(Regex("^[0-9.]+$"))) "http" else "https"
                val conn = URL("$proto://$cleanH/api/android/sync").openConnection() as HttpURLConnection
                if (tok.isNotEmpty()) {
                    conn.setRequestProperty("X-WG-Token", tok)
                }
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val os = conn.outputStream
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.close()

                if (conn.responseCode == 200) {
                    val br = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                    br.close()
                    val resp = JSONObject(sb.toString())
                    val ver = resp.optLong("configVersion", 0L)
                    prefs.edit().putLong("configVersion", ver).apply()

                    mainHandler.post {
                        tvSyncStatus.text = "Status: Tersimpan di Server v$ver"
                        Toast.makeText(this, "⬆️ Tersimpan & Synced ke Web UI!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Gagal push sync: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedRect(Color.parseColor("#0F172A"), dp(14), Color.parseColor("#1E293B"))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        layoutParams = lp
    }

    private fun createSectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#64748B"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }

    private fun createSubHint(hint: String): TextView = TextView(this).apply {
        text = hint
        setTextColor(Color.parseColor("#475569"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(4), 0, dp(4), dp(6))
    }

    private fun createSecondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setTextColor(Color.parseColor("#38BDF8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        background = roundedRect(Color.parseColor("#1E293B"), dp(8), Color.parseColor("#38BDF8"))
        setOnClickListener { onClick() }
    }

    private fun createSegmentButton(label: String, weight: Float, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        val lp = LinearLayout.LayoutParams(0, dp(34), weight).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(bgColor)
        if (strokeColor != Color.TRANSPARENT) {
            setStroke(dp(1), strokeColor)
        }
    }
}
