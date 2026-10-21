package network.stabirush.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LogsActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollView: ScrollView
    private lateinit var logsContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private var selectedFilter = "ALL" // "ALL", "E", "W", "I"
    private var isAutoScroll = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            mainHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refreshRunnable)
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Bar
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
            text = "Activity & Tunnel Logs"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(headerTitle)

        // Clear Logs Action
        val btnClear = TextView(this).apply {
            text = "Clear"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(4), dp(12), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppLogger.clear()
                renderLogs()
                Toast.makeText(this@LogsActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(btnClear)

        // Export / Share Action
        val btnExport = TextView(this).apply {
            text = "Export"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { exportLogs() }
        }
        header.addView(btnExport)
        root.addView(header)

        // Filter Bar (ALL, INFO, WARN, ERROR)
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#0D1322"))
        }

        val btnFilterAll = createFilterPill("ALL", selectedFilter == "ALL") { selectedFilter = "ALL"; renderLogs() }
        val btnFilterI = createFilterPill("INFO", selectedFilter == "I") { selectedFilter = "I"; renderLogs() }
        val btnFilterW = createFilterPill("WARN", selectedFilter == "W") { selectedFilter = "W"; renderLogs() }
        val btnFilterE = createFilterPill("ERROR", selectedFilter == "E") { selectedFilter = "E"; renderLogs() }

        filterBar.addView(btnFilterAll)
        filterBar.addView(btnFilterI)
        filterBar.addView(btnFilterW)
        filterBar.addView(btnFilterE)
        root.addView(filterBar)

        // Scrollable Log Console View
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.parseColor("#050811"), dp(12), Color.parseColor("#1E293B"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        tvEmpty = TextView(this).apply {
            text = "No logs recorded yet."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
        consoleCard.addView(tvEmpty)

        logsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        consoleCard.addView(logsContainer)

        scrollView.addView(consoleCard)
        root.addView(scrollView)

        // Bottom Action Bar (Copy + Share)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#0B101C"))
        }

        val btnCopy = TextView(this).apply {
            text = "Copy to Clipboard"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = roundedRect(Color.parseColor("#1E293B"), dp(8), Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            isClickable = true
            isFocusable = true
            setOnClickListener { copyLogsToClipboard() }
        }
        val btnShare = TextView(this).apply {
            text = "Share / Export"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = roundedRect(Color.parseColor("#0284C7"), dp(8), Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) }
            isClickable = true
            isFocusable = true
            setOnClickListener { exportLogs() }
        }

        bottomBar.addView(btnCopy)
        bottomBar.addView(btnShare)
        root.addView(bottomBar)

        setContentView(root)
        renderLogs()
    }

    private fun createFilterPill(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            val bg = if (selected) Color.parseColor("#0284C7") else Color.parseColor("#111827")
            val stroke = if (selected) Color.parseColor("#38BDF8") else Color.parseColor("#1F2937")
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
            background = roundedRect(bg, dp(14), stroke)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                buildUI()
            }
        }
    }

    private fun renderLogs() {
        val rawList = AppLogger.getLogs()
        val filtered = if (selectedFilter == "ALL") {
            rawList
        } else {
            rawList.filter { it.contains("[$selectedFilter/") }
        }

        if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            logsContainer.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        logsContainer.visibility = View.VISIBLE
        logsContainer.removeAllViews()

        filtered.takeLast(250).forEach { line ->
            val tv = TextView(this).apply {
                text = line
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, dp(2))
                setTextColor(
                    when {
                        line.contains("[E/") -> Color.parseColor("#EF4444")
                        line.contains("[W/") -> Color.parseColor("#F59E0B")
                        line.contains("[I/") -> Color.parseColor("#10B981")
                        else -> Color.parseColor("#94A3B8")
                    }
                )
            }
            logsContainer.addView(tv)
        }

        if (isAutoScroll) {
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLogsToClipboard() {
        val text = AppLogger.getAllAsText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Stabirush Logs", text)
        cm.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard (${text.length} chars)", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs() {
        val text = AppLogger.getAllAsText()
        if (text.isBlank()) {
            Toast.makeText(this, "No logs available to export", Toast.LENGTH_SHORT).show()
            return
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Stabirush VPN Diagnostics Log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, "Export Stabirush Logs via")
        startActivity(chooser)
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
