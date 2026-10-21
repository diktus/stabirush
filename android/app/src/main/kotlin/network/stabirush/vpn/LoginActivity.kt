package network.stabirush.vpn

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var etServer: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var btnLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)

        // If already logged in, navigate directly to MainActivity
        val existingToken = prefs.getString("authToken", "") ?: ""
        if (existingToken.isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        buildUI()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080D1A")) // Deep dark navy (Zero Trust theme)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(36))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Logo & Title Header
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(16))
        }

        val ivLogo = ImageView(this).apply {
            val iconId = resources.getIdentifier("ic_launcher", "drawable", packageName)
            if (iconId != 0) setImageResource(iconId)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(12)
            }
        }

        val tvTitle = TextView(this).apply {
            text = "STABIRUSH"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        brandRow.addView(ivLogo)
        brandRow.addView(tvTitle)
        container.addView(brandRow)

        val badgeZT = TextView(this).apply {
            text = "ZERO TRUST ACCESS · USER SIGN IN"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedRect(Color.parseColor("#0C2A4A"), dp(12), Color.parseColor("#0284C7"))
        }
        container.addView(badgeZT)

        val tvDesc = TextView(this).apply {
            text = "Sign in with your registered account to secure connection & synchronize your device subnet profile."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        container.addView(tvDesc)

        // Login Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = roundedRect(Color.parseColor("#0F172A"), dp(16), Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 1. Server Host Field
        card.addView(createFieldLabel("GATEWAY SERVER"))
        etServer = createInputField("vpn.jogjacloud.com", InputType.TYPE_CLASS_TEXT)
        val savedHost = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        etServer.setText(savedHost)
        card.addView(etServer)

        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        // 2. Username Field
        card.addView(createFieldLabel("USERNAME / USER ID"))
        etUsername = createInputField("Username (e.g. beni)", InputType.TYPE_CLASS_TEXT)
        val savedUser = prefs.getString("username", "beni") ?: "beni"
        etUsername.setText(savedUser)
        card.addView(etUsername)

        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        // 3. Password Field
        card.addView(createFieldLabel("PASSWORD"))
        etPassword = createInputField("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        card.addView(etPassword)

        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        // 4. Device Name Field
        card.addView(createFieldLabel("DEVICE NAME"))
        val defaultDevName = "Benidiktuz Phone (" + Build.MODEL + ")"
        val savedDevName = prefs.getString("selectedPeerName", defaultDevName) ?: defaultDevName
        etDeviceName = createInputField("Device Name", InputType.TYPE_CLASS_TEXT)
        etDeviceName.setText(savedDevName)
        card.addView(etDeviceName)

        // Error message text
        tvError = TextView(this).apply {
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(tvError)

        card.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))

        // Login Button
        btnLogin = Button(this).apply {
            text = "SIGN IN TO STABIRUSH"
            setTextColor(Color.parseColor("#080D1A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            background = roundedRect(Color.parseColor("#38BDF8"), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { performLogin() }
        }
        card.addView(btnLogin)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = dp(14)
            }
        }
        card.addView(progressBar)

        container.addView(card)

        // Footer info
        val tvFooter = TextView(this).apply {
            text = "Credentials configured via Stabirush Admin Console.\nDevice protected by end-to-end WireGuard encryption."
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        container.addView(tvFooter)

        scrollView.addView(container)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun createFieldLabel(title: String): TextView =
        TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(4), 0, 0, dp(6))
        }

    private fun createInputField(hintText: String, inputTypeFlags: Int): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = inputTypeFlags
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.parseColor("#1E293B"), dp(8), Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        }

    private fun performLogin() {
        val serverHost = etServer.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val deviceName = etDeviceName.text.toString().trim()

        if (serverHost.isEmpty()) {
            showError("Server address cannot be empty")
            return
        }
        if (username.isEmpty()) {
            showError("Username cannot be empty")
            return
        }
        if (password.isEmpty()) {
            showError("Password cannot be empty")
            return
        }

        tvError.visibility = View.GONE
        btnLogin.isEnabled = false
        btnLogin.text = "VERIFYING..."
        progressBar.visibility = View.VISIBLE

        Thread {
            try {
                val cleanHost = serverHost.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val proto = if (cleanHost.matches(Regex("^[0-9.]+$"))) "http" else "https"
                val url = URL("$proto://$cleanHost/api/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("deviceName", deviceName)
                }

                val os = conn.outputStream
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.close()

                val code = conn.responseCode
                if (code == 200) {
                    val br = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                    br.close()

                    val resp = JSONObject(sb.toString())
                    val token = resp.optString("token", "")
                    val userObj = resp.optJSONObject("user")
                    val devObj = resp.optJSONObject("device")

                    val assignedIp = devObj?.optString("allocatedIP", "10.10.0.3") ?: "10.10.0.3"
                    val pubKey = devObj?.optString("publicKey", "") ?: ""
                    val registeredName = devObj?.optString("name", deviceName) ?: deviceName

                    // Save session into SharedPreferences
                    prefs.edit()
                        .putString("authToken", token)
                        .putString("serverHost", serverHost)
                        .putString("username", username)
                        .putString("assignedIp", assignedIp)
                        .putString("selectedPubKey", pubKey)
                        .putString("selectedPeerName", registeredName)
                        .apply()

                    mainHandler.post {
                        Toast.makeText(this, "Successfully signed in as $username", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                } else {
                    val stream = if (conn.errorStream != null) conn.errorStream else conn.inputStream
                    val br = BufferedReader(InputStreamReader(stream))
                    val sb = StringBuilder()
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                    br.close()

                    var msg = "Authentication failed (HTTP $code)"
                    try {
                        val errObj = JSONObject(sb.toString())
                        val errText = errObj.optString("error", "")
                        if (errText.isNotEmpty()) msg = errText
                    } catch (ignored: Exception) {
                        if (sb.isNotEmpty()) msg = sb.toString()
                    }

                    mainHandler.post {
                        showError(msg)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    showError("Connection failed: ${e.message}")
                }
            } finally {
                mainHandler.post {
                    btnLogin.isEnabled = true
                    btnLogin.text = "SIGN IN TO STABIRUSH"
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun showError(msg: String) {
        tvError.text = "⚠ $msg"
        tvError.visibility = View.VISIBLE
    }
}
