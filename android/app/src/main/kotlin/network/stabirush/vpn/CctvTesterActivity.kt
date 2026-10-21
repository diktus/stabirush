package network.stabirush.vpn

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class CctvTesterActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLatencyResult: TextView
    private lateinit var tvSpeedResult: TextView
    private lateinit var tvLog: TextView

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
            text = "Diagnostics & Benchmark"
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

        // Target Info Card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val targetTitle = TextView(this).apply {
            text = "PROBE ENDPOINT TARGET"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        infoCard.addView(targetTitle)

        val targetVal = TextView(this).apply {
            text = "IP: 10.0.0.154  ·  Port: 8090 (RTSP Stream Bridge)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        infoCard.addView(targetVal)
        content.addView(infoCard)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        // Action Button
        btnStart = Button(this).apply {
            text = "▶  Start Diagnostic Benchmark"
            setAllCaps(false)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(Color.parseColor("#0284C7"), dp(12), Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { runBenchmark() }
        }
        content.addView(btnStart)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        // Result Bento Cards (2 cards)
        val resultRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Card Latency
        val cardLatency = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        val tvLatLabel = TextView(this).apply {
            text = "⚡ TCP HANDSHAKE RTT"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tvLatencyResult = TextView(this).apply {
            text = "-- ms"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        cardLatency.addView(tvLatLabel)
        cardLatency.addView(tvLatencyResult)
        resultRow.addView(cardLatency)

        // Card Speed
        val cardSpeed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(Color.parseColor("#111827"), dp(14), Color.parseColor("#1F2937"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSpeedLabel = TextView(this).apply {
            text = "⬇ THROUGHPUT"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tvSpeedResult = TextView(this).apply {
            text = "-- Kbps"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        cardSpeed.addView(tvSpeedLabel)
        cardSpeed.addView(tvSpeedResult)
        resultRow.addView(cardSpeed)

        content.addView(resultRow)

        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        // Log Box
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#090D16"), dp(14), Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tvStatus = TextView(this).apply {
            text = "Status: Idle - ready for test."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        tvLog = TextView(this).apply {
            text = "Detailed probe results will appear here."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        logCard.addView(tvStatus)
        logCard.addView(tvLog)
        content.addView(logCard)

        scroll.addView(content)
        root.addView(scroll)

        setContentView(root)
    }

    private fun runBenchmark() {
        btnStart.isEnabled = false
        btnStart.text = "Testing in progress..."
        tvStatus.text = "Status: Sending probe to 10.0.0.154:8090..."
        tvStatus.setTextColor(Color.parseColor("#F59E0B"))

        Thread {
            try {
                val targetHost = "10.0.0.154"
                val targetPort = 8090
                val targetUrl = "http://10.0.0.154:8090/"

                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val vpnNet = cm?.allNetworks?.firstOrNull { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }

                // 1. TCP Handshake RTT
                val latencies = mutableListOf<Long>()
                for (i in 1..5) {
                    val t0 = System.nanoTime()
                    try {
                        val s = Socket()
                        vpnNet?.bindSocket(s)
                        s.connect(InetSocketAddress(targetHost, targetPort), 3000)
                        val t1 = System.nanoTime()
                        s.close()
                        latencies.add((t1 - t0) / 1_000_000L)
                    } catch (e: Exception) {}
                    Thread.sleep(80)
                }

                val avgLat = if (latencies.isNotEmpty()) latencies.average() else 0.0

                // 2. HTTP Download throughput test
                var totalBytes = 0L
                val dlSpeeds = mutableListOf<Double>()
                for (i in 1..3) {
                    try {
                        val url = URL(targetUrl)
                        val conn = (if (vpnNet != null) vpnNet.openConnection(url) else url.openConnection()) as HttpURLConnection
                        conn.connectTimeout = 3500
                        conn.readTimeout = 3500
                        conn.setRequestProperty("User-Agent", "StabirushBench/1.0")
                        conn.setRequestProperty("Connection", "close")

                        conn.responseCode
                        val tHeaders = System.nanoTime()
                        val stream = conn.inputStream
                        val buf = ByteArray(16384)
                        var bRead = 0L
                        while (true) {
                            val n = stream.read(buf)
                            if (n == -1) break
                            bRead += n
                        }
                        stream.close()
                        conn.disconnect()

                        val tEnd = System.nanoTime()
                        val dlSec = (tEnd - tHeaders) / 1_000_000_000.0
                        if (dlSec > 0) {
                            dlSpeeds.add((bRead * 8.0) / (dlSec * 1000.0))
                        }
                        totalBytes += bRead
                    } catch (e: Exception) {}
                    Thread.sleep(80)
                }

                val avgSpeed = if (dlSpeeds.isNotEmpty()) dlSpeeds.average() else 0.0

                mainHandler.post {
                    btnStart.isEnabled = true
                    btnStart.text = "▶  Re-run Diagnostic Benchmark"
                    tvStatus.text = "Status: Benchmark finished successfully!"
                    tvStatus.setTextColor(Color.parseColor("#10B981"))

                    tvLatencyResult.text = "${avgLat.toInt()} ms"
                    tvSpeedResult.text = if (avgSpeed >= 1000) String.format("%.1f Mbps", avgSpeed / 1000.0) else "${avgSpeed.toInt()} Kbps"

                    tvLog.text = """
                        Probe Target: $targetHost:$targetPort
                        TCP Handshake RTT: min=${latencies.minOrNull() ?: 0}ms, avg=${avgLat.toInt()}ms, max=${latencies.maxOrNull() ?: 0}ms
                        Download Speeds: ${dlSpeeds.map { "${it.toInt()}kbps" }.joinToString(", ")}
                        Total Payload Received: $totalBytes bytes
                    """.trimIndent()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    btnStart.isEnabled = true
                    btnStart.text = "▶  Start Diagnostic Benchmark"
                    tvStatus.text = "Status: Test failed - ${e.message}"
                    tvStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radiusPx.toFloat()
        setStroke(dp(1), strokeColor)
    }
}
