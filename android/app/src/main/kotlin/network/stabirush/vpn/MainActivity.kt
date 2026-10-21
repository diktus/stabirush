package network.stabirush.vpn

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 101
        private const val REQ_LOC_PERMISSION = 102
    }

    private lateinit var prefs: SharedPreferences

    // Reactive Compose States
    private val isRunningState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private val isFallbackState = mutableStateOf(false)
    private val activeTransState = mutableStateOf("auto")
    private val txBytesState = mutableStateOf(0L)
    private val rxBytesState = mutableStateOf(0L)
    private val txPacketsState = mutableStateOf(0L)
    private val rxPacketsState = mutableStateOf(0L)
    private val pingMsState = mutableStateOf(-1)

    private val routingModeState = mutableStateOf("split")
    private val transportModeState = mutableStateOf("auto")
    private val assignedIpState = mutableStateOf("10.10.0.3")
    private val serverHostState = mutableStateOf("vpn.jogjacloud.com")
    private val selectedPeerNameState = mutableStateOf("Android Device")
    private val selectedPubKeyState = mutableStateOf("")

    private val namedSubnets = mutableListOf<NamedSubnet>()
    private val wifiRules = mutableListOf<WifiRule>()
    private val activeSubnetsCidr = mutableListOf<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                if (intent.action == StabirushVpnService.ACTION_STATUS_BROADCAST) {
                    refreshStateFromService()
                } else if (intent.action == StabirushVpnService.ACTION_SYNC_BROADCAST) {
                    loadModelsFromPrefs()
                    refreshStateFromService()
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
        refreshStateFromService()

        setContent {
            CyberMinimalistTheme {
                MainCyberComposeScreen()
            }
        }

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
        refreshStateFromService()
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
        routingModeState.value = prefs.getString("routingMode", "split") ?: "split"
        transportModeState.value = prefs.getString("transportMode", "auto") ?: "auto"
        assignedIpState.value = prefs.getString("assignedIp", "10.10.0.3") ?: "10.10.0.3"
        serverHostState.value = prefs.getString("serverHost", "vpn.jogjacloud.com") ?: "vpn.jogjacloud.com"
        selectedPubKeyState.value = prefs.getString("selectedPubKey", "") ?: ""
        selectedPeerNameState.value = prefs.getString("selectedPeerName", "Android Device") ?: "Android Device"

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

    private fun refreshStateFromService() {
        val running = StabirushVpnService.isRunning
        val rx = StabirushVpnService.rxBytes.get()
        val verified = StabirushVpnService.isVerified
        val connected = running && (verified || rx > 0)

        isRunningState.value = running
        isConnectedState.value = connected
        isFallbackState.value = StabirushVpnService.isFallbackActive
        activeTransState.value = StabirushVpnService.activeTransport
        txBytesState.value = StabirushVpnService.txBytes.get()
        rxBytesState.value = rx
        txPacketsState.value = StabirushVpnService.txPackets.get()
        rxPacketsState.value = StabirushVpnService.rxPackets.get()
        pingMsState.value = StabirushVpnService.lastPingMs
    }

    private fun setRoutingMode(mode: String, restartIfConnected: Boolean) {
        if (routingModeState.value == mode) return
        routingModeState.value = mode
        prefs.edit().putString("routingMode", mode).apply()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = if (mode.equals("secure", ignoreCase = true)) "Secure Full Tunnel (0.0.0.0/0)" else "Split Tunnel"
            Toast.makeText(this, "Routing mode set to $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransportMode(mode: String, restartIfConnected: Boolean) {
        transportModeState.value = mode
        prefs.edit().putString("transportMode", mode).apply()

        if (restartIfConnected && StabirushVpnService.isRunning) {
            launchVpnService()
            val label = when (mode) {
                "quic" -> "🚀 QUIC (UDP 8443 / 51821)"
                "wstunnel", "tcp" -> "🛡️ TCP:443 (TLS)"
                "udp", "wireguard" -> "⚡ WireGuard (UDP 51820)"
                else -> "Auto (Adaptive Fallback)"
            }
            Toast.makeText(this, "Transport set to $label", Toast.LENGTH_SHORT).show()
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
            putExtra("routingMode", routingModeState.value)
            putExtra("transportMode", transportModeState.value)
            putExtra("assignedIp", assignedIpState.value)
            putExtra("serverHost", serverHostState.value)
            putExtra("selectedPubKey", selectedPubKeyState.value)
            putExtra("activeSubnets", activeSubnetsCidr.joinToString(","))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srvIntent)
        } else {
            startService(srvIntent)
        }
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
                    Toast.makeText(this, "Wi-Fi '$ssid': VPN Disconnected automatically", Toast.LENGTH_SHORT).show()
                }
            } else if (targetMode == "secure" || targetMode == "split") {
                if (routingModeState.value != targetMode) {
                    setRoutingMode(targetMode, restartIfConnected = true)
                    Toast.makeText(this, "Wi-Fi '$ssid': Switched to $targetMode mode", Toast.LENGTH_SHORT).show()
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://${serverHostState.value}/api/android/sync")
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
                            val ip = devObj.optString("allocatedIP", assignedIpState.value)
                            val name = devObj.optString("name", selectedPeerNameState.value)
                            prefs.edit().putString("assignedIp", ip).putString("selectedPeerName", name).apply()
                            withContext(Dispatchers.Main) {
                                assignedIpState.value = ip
                                selectedPeerNameState.value = name
                                if (showToastMsg) Toast.makeText(this@MainActivity, "✓ Synchronized with server", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                }
                if (showToastMsg) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Sync failed (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (showToastMsg) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Sync offline: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Sign Out")
            .setMessage("Are you sure you want to sign out from your Stabirush account?")
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

    // =========================================================================
    // JETPACK COMPOSE UI (MODERN MATERIAL 3 & PHOSPHOR ICONS)
    // =========================================================================

    @Composable
    private fun CyberMinimalistTheme(content: @Composable () -> Unit) {
        val colorScheme = darkColorScheme(
            background = Color(0xFF0A0E17),
            surface = Color(0xFF111827),
            primary = Color(0xFF0EA5E9),
            secondary = Color(0xFF10B981),
            outline = Color(0xFF1F2937),
            onBackground = Color.White,
            onSurface = Color.White
        )
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainCyberComposeScreen() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val isConnected by isConnectedState
        val isRunning by isRunningState
        val isFallback by isFallbackState
        val activeTrans by activeTransState
        val currentRouting by routingModeState
        val currentTrans by transportModeState
        val assignedIp by assignedIpState
        val peerName by selectedPeerNameState
        val txBytes by txBytesState
        val rxBytes by rxBytesState
        val txPkts by txPacketsState
        val rxPkts by rxPacketsState
        val pingMs by pingMsState

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color(0xFF0D1322),
                    modifier = Modifier.width(300.dp)
                ) {
                    // Drawer Header with Duotone Shield
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111A2E))
                            .padding(horizontal = 18.dp, vertical = 24.dp)
                    ) {
                        SoftTintedBadge(iconRes = R.drawable.ic_shield_check, tint = Color(0xFF10B981), sizeDp = 44)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "STABIRUSH SECURE",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = peerName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "IP: $assignedIp",
                                color = Color(0xFF10B981),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Drawer Items List
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        DrawerMenuItem(
                            iconRes = R.drawable.ic_wifi,
                            tint = Color(0xFF38BDF8),
                            title = "Wi-Fi Smart Rules",
                            sub = "Auto-switch on SSID change"
                        ) {
                            scope.launch { drawerState.close() }
                            startActivity(Intent(this@MainActivity, WifiRulesActivity::class.java))
                        }

                        DrawerMenuItem(
                            iconRes = R.drawable.ic_hub,
                            tint = Color(0xFF0EA5E9),
                            title = "Private Subnets",
                            sub = "Manage split tunnel CIDRs"
                        ) {
                            scope.launch { drawerState.close() }
                            startActivity(Intent(this@MainActivity, SubnetManagerActivity::class.java))
                        }

                        DrawerMenuItem(
                            iconRes = R.drawable.ic_logs,
                            tint = Color(0xFF38BDF8),
                            title = "Connection Logs",
                            sub = "Display live logs & export"
                        ) {
                            scope.launch { drawerState.close() }
                            startActivity(Intent(this@MainActivity, LogsActivity::class.java))
                        }

                        DrawerMenuItem(
                            iconRes = R.drawable.ic_cctv,
                            tint = Color(0xFF10B981),
                            title = "Diagnostics & Benchmark",
                            sub = "Test RTSP latency & speed"
                        ) {
                            scope.launch { drawerState.close() }
                            startActivity(Intent(this@MainActivity, CctvTesterActivity::class.java))
                        }

                        DrawerMenuItem(
                            iconRes = R.drawable.ic_account,
                            tint = Color(0xFF38BDF8),
                            title = "Server & Account",
                            sub = "Host gateway & token info"
                        ) {
                            scope.launch { drawerState.close() }
                            startActivity(Intent(this@MainActivity, AccountActivity::class.java))
                        }
                    }

                    // Drawer Footer: Logout
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B101C))
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch { drawerState.close() }
                                confirmLogout()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF271216)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_logout),
                                contentDescription = "Sign Out",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Color(0xFF0A0E17),
                topBar = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        // Hamburger Menu Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x2638BDF8))
                                .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(12.dp))
                                .clickable { scope.launch { drawerState.open() } }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_menu),
                                contentDescription = "Menu",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Center Brand
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "STABIRUSH",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = "CYBER JETPACK COMPOSE",
                                color = Color(0xFF0EA5E9),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Modern Cloud Sync Pull Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x2638BDF8))
                                .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(12.dp))
                                .clickable { syncFromServer(showToastMsg = true) }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_cloud_sync),
                                contentDescription = "Sync Config",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // ================= TOP: COMPACT SEGMENTED ROUTING MODE =================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF111827))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                            .padding(4.dp)
                    ) {
                        val isSplit = currentRouting.equals("split", ignoreCase = true)
                        // Split Mode Tab
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSplit) Color(0xFF0C2038) else Color.Transparent)
                                .border(1.dp, if (isSplit) Color(0xFF0284C7) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { setRoutingMode("split", restartIfConnected = true) }
                        ) {
                            SoftTintedBadge(iconRes = R.drawable.ic_call_split, tint = Color(0xFF38BDF8), sizeDp = 24)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Split Tunnel",
                                color = if (isSplit) Color.White else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Secure Full Tab
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!isSplit) Color(0xFF062B28) else Color.Transparent)
                                .border(1.dp, if (!isSplit) Color(0xFF10B981) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { setRoutingMode("secure", restartIfConnected = true) }
                        ) {
                            SoftTintedBadge(iconRes = R.drawable.ic_rocket_launch, tint = Color(0xFF10B981), sizeDp = 24)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Secure 0.0.0.0/0",
                                color = if (!isSplit) Color.White else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    // ================= CENTER: HERO POWER BUTTON =================
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isRunning && !isConnected) 1.08f else 1.02f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseScale"
                    )

                    val outerRingColor by animateColorAsState(
                        targetValue = when {
                            isConnected -> Color(0xFF062B28)
                            isRunning -> Color(0xFF1C1917)
                            else -> Color(0xFF0C1527)
                        },
                        label = "ringColor"
                    )
                    val outerStrokeColor by animateColorAsState(
                        targetValue = when {
                            isConnected -> Color(0xFF10B981)
                            isRunning -> Color(0xFFF59E0B)
                            else -> Color(0xFF1E293B)
                        },
                        label = "strokeColor"
                    )

                    val innerCircleColor by animateColorAsState(
                        targetValue = when {
                            isConnected -> Color(0xFF10B981)
                            isRunning -> Color(0xFFD97706)
                            else -> Color(0xFF1E293B)
                        },
                        label = "innerCircle"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(144.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(outerRingColor)
                            .border(1.5.dp, outerStrokeColor, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { toggleVpnConnection() }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(innerCircleColor)
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isConnected) R.drawable.ic_shield_check else R.drawable.ic_power
                                ),
                                contentDescription = "Power",
                                tint = if (isConnected || isRunning) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when {
                            isConnected -> if (currentRouting.equals("secure", ignoreCase = true)) "SECURE TUNNEL ACTIVE" else "SPLIT TUNNEL ACTIVE"
                            isRunning -> "CONNECTING..."
                            else -> "DISCONNECTED"
                        },
                        color = when {
                            isConnected -> Color(0xFF10B981)
                            isRunning -> Color(0xFFF59E0B)
                            else -> Color.White
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    Text(
                        text = when {
                            isConnected -> "2-Way Encrypted Tunnel · Gateway 10.10.0.1"
                            isRunning -> if (activeTrans == "quic") "Establishing QUIC Fast-UDP stream..." else "Verifying handshake response..."
                            else -> "Tap to secure your connection"
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 22.dp)
                    )

                    // ================= BENTO GRID LIVE METRIC CARDS (2x2) =================
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Card 1: Traffic Live
                        ModernBentoCard(
                            modifier = Modifier.weight(1f),
                            header = "THROUGHPUT",
                            iconRes = R.drawable.ic_throughput,
                            tint = Color(0xFF38BDF8),
                            value = if (isConnected) "⬇ ${formatBytes(rxBytes)} / ⬆ ${formatBytes(txBytes)}" else "0 B / 0 B",
                            sub = if (isConnected) "$rxPkts rx / $txPkts tx pkts" else "Idle network"
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Card 2: Modern ECG Pulse Wave Latency
                        ModernBentoCardWithDot(
                            modifier = Modifier.weight(1f),
                            header = "PING LATENCY",
                            iconRes = R.drawable.ic_pulse,
                            tint = Color(0xFF10B981),
                            value = if (isConnected) (if (pingMs >= 0) "$pingMs ms" else "<1 ms") else "-- ms",
                            sub = "Gateway: 10.10.0.1",
                            dotColor = if (isConnected) (if (pingMs in 0..60) Color(0xFF10B981) else Color(0xFFF59E0B)) else Color(0xFF64748B)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Card 3: Transport Protocol
                        val pLabel = when {
                            activeTrans == "quic" -> "🚀 QUIC"
                            isFallback || activeTrans.contains("tcp") -> "🛡️ TCP:443"
                            else -> "⚡ WireGuard"
                        }
                        val pSub = if (activeTrans == "quic") "UDP 51821" else if (activeTrans.contains("tcp")) "TLS Tunnel" else "UDP 51820"

                        ModernBentoCard(
                            modifier = Modifier.weight(1f),
                            header = "PROTOCOL",
                            iconRes = when (activeTrans) {
                                "quic" -> R.drawable.ic_transport_quic
                                "tcp", "wstunnel" -> R.drawable.ic_transport_tcp
                                else -> R.drawable.ic_transport_wireguard
                            },
                            tint = Color(0xFF38BDF8),
                            value = if (isConnected) pLabel else "Auto",
                            sub = if (isConnected) pSub else "Standby"
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Card 4: Virtual IP
                        ModernBentoCard(
                            modifier = Modifier.weight(1f),
                            header = "VIRTUAL IP",
                            iconRes = R.drawable.ic_hub,
                            tint = Color(0xFF0EA5E9),
                            value = assignedIp,
                            sub = if (currentRouting.equals("secure", ignoreCase = true)) "0.0.0.0/0 Full" else "Split Active"
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // ================= BOTTOM: TRANSPORT PROTOCOL BAR =================
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "TRANSPORT PROTOCOL",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        TransportModeButton(
                            modifier = Modifier.weight(0.26f),
                            iconRes = R.drawable.ic_transport_wireguard,
                            label = "WG",
                            selected = currentTrans == "udp" || currentTrans == "wireguard"
                        ) {
                            setTransportMode("udp", restartIfConnected = true)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TransportModeButton(
                            modifier = Modifier.weight(0.26f),
                            iconRes = R.drawable.ic_transport_quic,
                            label = "QUIC",
                            selected = currentTrans == "quic"
                        ) {
                            setTransportMode("quic", restartIfConnected = true)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TransportModeButton(
                            modifier = Modifier.weight(0.26f),
                            iconRes = R.drawable.ic_transport_tcp,
                            label = "TCP",
                            selected = currentTrans == "wstunnel" || currentTrans == "tcp"
                        ) {
                            setTransportMode("wstunnel", restartIfConnected = true)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TransportModeButton(
                            modifier = Modifier.weight(0.22f),
                            iconRes = R.drawable.ic_transport_auto,
                            label = "Auto",
                            selected = currentTrans == "auto"
                        ) {
                            setTransportMode("auto", restartIfConnected = true)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SoftTintedBadge(iconRes: Int, tint: Color, sizeDp: Int = 36) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.15f))
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size((sizeDp * 0.55).dp)
            )
        }
    }

    @Composable
    private fun DrawerMenuItem(iconRes: Int, tint: Color, title: String, sub: String, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF111A2E))
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            SoftTintedBadge(iconRes = iconRes, tint = tint, sizeDp = 34)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(text = sub, color = Color(0xFF94A3B8), fontSize = 10.5.sp)
            }
            Text(text = "›", color = Color(0xFF64748B), fontSize = 18.sp)
        }
    }

    @Composable
    private fun ModernBentoCard(
        modifier: Modifier = Modifier,
        header: String,
        iconRes: Int,
        tint: Color,
        value: String,
        sub: String
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111827))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftTintedBadge(iconRes = iconRes, tint = tint, sizeDp = 28)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = header,
                    color = Color(0xFF64748B),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                color = Color(0xFF94A3B8),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun ModernBentoCardWithDot(
        modifier: Modifier = Modifier,
        header: String,
        iconRes: Int,
        tint: Color,
        value: String,
        sub: String,
        dotColor: Color
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111827))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftTintedBadge(iconRes = iconRes, tint = tint, sizeDp = 28)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = header,
                    color = Color(0xFF64748B),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                color = Color(0xFF94A3B8),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun TransportModeButton(
        modifier: Modifier = Modifier,
        iconRes: Int,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Color(0xFF0284C7) else Color(0xFF111827))
                .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF1F2937), RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .padding(horizontal = 4.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = if (selected) Color.White else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (selected) Color.White else Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
