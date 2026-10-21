package network.stabirush.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AccountActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverHost: String = "vpn.jogjacloud.com"
    private var assignedIp: String = "10.10.0.3"
    private var selectedPeerName: String = "Android Device"
    private var selectedPubKey: String = ""

    private lateinit var tvServerVal: TextView
    private lateinit var tvIpVal: TextView
    private lateinit var tvPeerVal: TextView
    private lateinit var tvSyncStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)
        loadData()
        buildUI()
    }

    private fun loadData() {
        serverHost = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        assignedIp = prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        selectedPeerName = prefs.getString("selectedPeerName", "Android Device") ?: "Android Device"
        selectedPubKey = prefs.getString("selectedPubKey", "") ?: ""
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
            text = "Server & Account"
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

        // Info Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        tvPeerVal = addInfoRow(card, "DEVICE NAME", selectedPeerName)
        tvIpVal = addInfoRow(card, "VIRTUAL WIREGUARD IP", assignedIp)
        tvServerVal = addInfoRow(card, "GATEWAY HOST", serverHost)

        val btnEditServer = TextView(this).apply {
            text = "Edit Gateway Host"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedRect(Color.parseColor("#1E293B"), dp(6), Color.parseColor("#38BDF8"))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { showEditServerDialog() }
        }
        card.addView(btnEditServer)

        content.addView(card)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        // Sync Buttons Card
        val syncCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val syncTitle = TextView(this).apply {
            text = "CLOUD SYNCHRONIZATION"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        syncCard.addView(syncTitle)

        val btnSyncPull = Button(this).apply {
            text = "Pull Cloud Configuration"
            setAllCaps(false)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = roundedRect(Color.parseColor("#0284C7"), dp(10), Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) }
            setOnClickListener { pullSync() }
        }
        syncCard.addView(btnSyncPull)

        tvSyncStatus = TextView(this).apply {
            text = "Ready to synchronize"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8), 0, 0)
        }
        syncCard.addView(tvSyncStatus)
        content.addView(syncCard)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        // Logout Button
        val btnLogout = Button(this).apply {
            text = "Sign Out"
            setAllCaps(false)
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(Color.parseColor("#271216"), dp(12), Color.parseColor("#EF4444"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            setOnClickListener { performLogout() }
        }
        content.addView(btnLogout)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun addInfoRow(parent: LinearLayout, title: String, value: String): TextView {
        val tTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(6), 0, 0)
        }
        val vTv = TextView(this).apply {
            text = value
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(4))
        }
        parent.addView(tTv)
        parent.addView(vTv)
        return vTv
    }

    private fun showEditServerDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        val et = EditText(this).apply {
            setText(serverHost)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(et)

        AlertDialog.Builder(this)
            .setTitle("Edit Gateway Host")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val h = et.text.toString().trim()
                if (h.isNotEmpty()) {
                    serverHost = h
                    prefs.edit().putString("serverHost", h).apply()
                    tvServerVal.text = h
                    Toast.makeText(this, "Host set to $h", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pullSync() {
        tvSyncStatus.text = "Connecting to server..."
        tvSyncStatus.setTextColor(Color.parseColor("#F59E0B"))

        Thread {
            try {
                val token = prefs.getString("authToken", "") ?: ""
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
                                tvIpVal.text = ip
                                tvPeerVal.text = name
                                tvSyncStatus.text = "✓ Sync successful!"
                                tvSyncStatus.setTextColor(Color.parseColor("#10B981"))
                            }
                            return@Thread
                        }
                    }
                }
                mainHandler.post {
                    tvSyncStatus.text = "Failed: HTTP ${conn.responseCode}"
                    tvSyncStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvSyncStatus.text = "Error: ${e.message}"
                    tvSyncStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }.start()
    }

    private fun performLogout() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Sign Out")
            .setMessage("Are you sure you want to sign out? Your session token will be removed.")
            .setPositiveButton("Sign Out") { _, _ ->
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
