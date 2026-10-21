package network.stabirush.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
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
            text = "Settings"
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

        val labelGeneral = TextView(this).apply {
            text = "CONFIGURATION CATEGORIES"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(4), 0, 0, dp(10))
        }
        content.addView(labelGeneral)

        // Menu Group Card
        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.parseColor("#111827"), dp(16), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        addModernMenuItem(menuCard, R.drawable.ic_wifi, Color.parseColor("#38BDF8"), "Wi-Fi Smart Rules", "Automatic mode switching by Wi-Fi SSID") {
            startActivity(Intent(this, WifiRulesActivity::class.java))
        }
        addDivider(menuCard)

        addModernMenuItem(menuCard, R.drawable.ic_hub, Color.parseColor("#0EA5E9"), "Private Subnets", "CIDR prefixes routed through the split tunnel") {
            startActivity(Intent(this, SubnetManagerActivity::class.java))
        }
        addDivider(menuCard)

        addModernMenuItem(menuCard, R.drawable.ic_logs, Color.parseColor("#38BDF8"), "Connection Logs", "Live tunnel event feed and diagnostic export") {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        addDivider(menuCard)

        addModernMenuItem(menuCard, R.drawable.ic_cctv, Color.parseColor("#10B981"), "Diagnostics & Benchmark", "Test RTSP latency and stream throughput") {
            startActivity(Intent(this, CctvTesterActivity::class.java))
        }
        addDivider(menuCard)

        addModernMenuItem(menuCard, R.drawable.ic_account, Color.parseColor("#38BDF8"), "Server & Account", "Gateway host, session tokens, and peer identity") {
            startActivity(Intent(this, AccountActivity::class.java))
        }

        content.addView(menuCard)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun createSoftTintedBadge(iconResId: Int, tintColor: Int, sizeDp: Int = 36, cornerDp: Int = 10): FrameLayout {
        val frame = FrameLayout(this).apply {
            val d = dp(sizeDp)
            layoutParams = FrameLayout.LayoutParams(d, d)
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

    private fun addModernMenuItem(parent: LinearLayout, iconRes: Int, iconTint: Int, title: String, subtitle: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val badge = createSoftTintedBadge(iconRes, iconTint, sizeDp = 36, cornerDp = 10)
        row.addView(badge)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        val tTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sTv = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(tTv)
        col.addView(sTv)
        row.addView(col)

        val arrowTv = TextView(this).apply {
            text = "›"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(arrowTv)

        parent.addView(row)
    }

    private fun addDivider(parent: LinearLayout) {
        val div = View(this).apply {
            setBackgroundColor(Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(62)
            }
        }
        parent.addView(div)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radiusPx.toFloat()
        setStroke(dp(1), strokeColor)
    }
}
