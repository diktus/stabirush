package network.stabirush.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

class WifiRulesActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val wifiRules = mutableListOf<WifiRule>()
    private lateinit var rulesContainer: LinearLayout
    private lateinit var cbAutoSwitch: CheckBox
    private lateinit var tvCurrentSsid: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)
        loadData()
        buildUI()
    }

    private fun loadData() {
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
        val arr = JSONArray()
        wifiRules.forEach { arr.put(it.toJsonObject()) }
        prefs.edit().putString("wifiRulesJson", arr.toString()).apply()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val btnBack = TextView(this).apply {
            text = "←"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(4), dp(4), dp(16), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        val headerTitle = TextView(this).apply {
            text = "Wi-Fi Smart Rules"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(headerTitle)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }

        // Auto Switch Toggle Card
        val autoSwitchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cbAutoSwitch = CheckBox(this).apply {
            text = "Enable Auto-Switch Based on Wi-Fi SSID"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = prefs.getBoolean("autoWifiSwitchEnabled", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("autoWifiSwitchEnabled", checked).apply()
                Toast.makeText(this@WifiRulesActivity, if (checked) "Auto Wi-Fi Switching Enabled" else "Auto Wi-Fi Switching Disabled", Toast.LENGTH_SHORT).show()
            }
        }
        autoSwitchCard.addView(cbAutoSwitch)

        tvCurrentSsid = TextView(this).apply {
            text = "📶 Active SSID: " + getDetectedSsid()
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(32), dp(4), 0, 0)
        }
        autoSwitchCard.addView(tvCurrentSsid)
        content.addView(autoSwitchCard)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        // Rules Section Header
        val rulesHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val labelRules = TextView(this).apply {
            text = "SSID PROFILE RULES"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAdd = Button(this).apply {
            text = "+ Add Rule"
            setAllCaps(false)
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            background = roundedRect(Color.parseColor("#062B28"), dp(8), Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(34))
            setOnClickListener { showAddDialog() }
        }
        rulesHeaderRow.addView(labelRules)
        rulesHeaderRow.addView(btnAdd)
        content.addView(rulesHeaderRow)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(rulesContainer)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        renderRules()
    }

    private fun getDetectedSsid(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            if (info != null && info.ssid != null) {
                var s = info.ssid
                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) s = s.substring(1, s.length - 1)
                if (!s.equals("<unknown ssid>", ignoreCase = true)) return s
            }
        } catch (ignored: Exception) {}
        return "Cellular / Disconnected"
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        if (wifiRules.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No SSID rules defined yet.\nAdd trusted home or office SSIDs to automatically switch VPN mode when connected."
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(20), dp(12), dp(20))
                gravity = Gravity.CENTER
            }
            rulesContainer.addView(emptyTv)
            return
        }

        wifiRules.forEachIndexed { idx, rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedRect(Color.parseColor("#111827"), dp(12), Color.parseColor("#1F2937"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(this).apply {
                text = "📶 ${rule.ssid}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val modeTv = TextView(this).apply {
                text = "Action: Mode ${rule.profileMode.uppercase()} · ${rule.subnetNames}"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(titleTv)
            infoCol.addView(modeTv)
            card.addView(infoCol)

            val btnDel = Button(this).apply {
                text = "Delete"
                setAllCaps(false)
                setTextColor(Color.parseColor("#EF4444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = roundedRect(Color.parseColor("#271216"), dp(6), Color.parseColor("#EF4444"))
                layoutParams = LinearLayout.LayoutParams(dp(70), dp(30))
                setOnClickListener {
                    wifiRules.removeAt(idx)
                    saveData()
                    renderRules()
                }
            }
            card.addView(btnDel)
            rulesContainer.addView(card)
        }
    }

    private fun showAddDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        val label = TextView(this).apply {
            text = "SSID Pattern (supports * wildcard):"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(label)

        val etSsid = EditText(this).apply {
            hint = "e.g. Home*, Office*, Cafe*"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(etSsid)

        val labelMode = TextView(this).apply {
            text = "Target VPN Routing Profile:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(labelMode)

        var selectedMode = "split"
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val btnSplit = Button(this).apply {
            text = "Split Tunnel"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
        }
        val btnSecure = Button(this).apply {
            text = "Secure Full"
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }

        fun updateModeBtns() {
            if (selectedMode == "split") {
                btnSplit.setTextColor(Color.WHITE)
                btnSplit.background = roundedRect(Color.parseColor("#0284C7"), dp(6), Color.parseColor("#38BDF8"))
                btnSecure.setTextColor(Color.parseColor("#94A3B8"))
                btnSecure.background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.TRANSPARENT)
            } else {
                btnSecure.setTextColor(Color.WHITE)
                btnSecure.background = roundedRect(Color.parseColor("#10B981"), dp(6), Color.parseColor("#34D399"))
                btnSplit.setTextColor(Color.parseColor("#94A3B8"))
                btnSplit.background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.TRANSPARENT)
            }
        }
        btnSplit.setOnClickListener { selectedMode = "split"; updateModeBtns() }
        btnSecure.setOnClickListener { selectedMode = "secure"; updateModeBtns() }
        updateModeBtns()

        modeRow.addView(btnSplit)
        modeRow.addView(btnSecure)
        root.addView(modeRow)

        AlertDialog.Builder(this)
            .setTitle("Add Wi-Fi Rule")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val s = etSsid.text.toString().trim()
                if (s.isNotEmpty()) {
                    wifiRules.add(WifiRule(ssid = s, profileMode = selectedMode, subnetNames = "All Active Subnets"))
                    saveData()
                    renderRules()
                    Toast.makeText(this, "Rule for $s saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radiusPx.toFloat()
        setStroke(dp(1), strokeColor)
    }
}
