package network.stabirush.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

class SubnetManagerActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val namedSubnets = mutableListOf<NamedSubnet>()
    private val activeSubnetsCidr = mutableListOf<String>()
    private lateinit var subnetsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StabirushVpnService.PREFS_NAME, MODE_PRIVATE)
        loadData()
        buildUI()
    }

    private fun loadData() {
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
    }

    private fun saveData() {
        val nsArr = JSONArray()
        namedSubnets.forEach { nsArr.put(it.toJsonObject()) }
        prefs.edit()
            .putString("namedSubnetsJson", nsArr.toString())
            .putString("activeSubnets", activeSubnetsCidr.joinToString(","))
            .apply()
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
            text = "Kelola Subnet Private"
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

        // Header Action Row
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val labelSubnets = TextView(this).apply {
            text = "SUBNET AKTIF (SPLIT TUNNEL)"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAdd = Button(this).apply {
            text = "+ Tambah CIDR"
            setAllCaps(false)
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            background = roundedRect(Color.parseColor("#062B28"), dp(8), Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(dp(125), dp(34))
            setOnClickListener { showAddSubnetDialog() }
        }
        actRow.addView(labelSubnets)
        actRow.addView(btnAdd)
        content.addView(actRow)

        val hintTv = TextView(this).apply {
            text = "Subnet yang dicentang akan diarahkan ke tunnel Stabirush. Trafik internet selain subnet ini tetap berjalan langsung melalui koneksi reguler Anda."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(6), 0, dp(12))
        }
        content.addView(hintTv)

        subnetsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(subnetsContainer)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        renderSubnets()
    }

    private fun renderSubnets() {
        subnetsContainer.removeAllViews()
        namedSubnets.forEach { ns ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedRect(Color.parseColor("#111827"), dp(12), Color.parseColor("#1F2937"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
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
            card.addView(cb)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
            }
            val titleTv = TextView(this).apply {
                text = ns.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val cidrTv = TextView(this).apply {
                text = "${ns.cidr} · ${ns.description}"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(titleTv)
            infoCol.addView(cidrTv)
            card.addView(infoCol)

            subnetsContainer.addView(card)
        }
    }

    private fun showAddSubnetDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        val etName = EditText(this).apply {
            hint = "Nama Subnet (misal: Server Kantor)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val etCidr = EditText(this).apply {
            hint = "CIDR (misal: 192.168.10.0/24)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(etName)
        root.addView(etCidr)

        AlertDialog.Builder(this)
            .setTitle("Tambah Subnet Kustom")
            .setView(root)
            .setPositiveButton("Tambah") { _, _ ->
                val name = etName.text.toString().trim()
                val cidr = etCidr.text.toString().trim()
                if (name.isNotEmpty() && cidr.isNotEmpty()) {
                    val ns = NamedSubnet(id = "custom_" + System.currentTimeMillis(), name = name, cidr = cidr, description = "Subnet kustom")
                    namedSubnets.add(ns)
                    activeSubnetsCidr.add(cidr)
                    saveData()
                    renderSubnets()
                    Toast.makeText(this, "Subnet $name ($cidr) ditambahkan", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedRect(bgColor: Int, radiusPx: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radiusPx.toFloat()
        setStroke(dp(1), strokeColor)
    }
}
