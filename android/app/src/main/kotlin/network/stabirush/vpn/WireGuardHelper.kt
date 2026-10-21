package network.stabirush.vpn

import android.util.Base64

object WireGuardHelper {

    fun base64ToHex(b64: String): String {
        val clean = b64.trim()
        if (clean.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun buildUapiConfig(
        clientPrivateKeyHex: String,
        serverPublicKeyHex: String,
        serverEndpoint: String,
        allowedIps: List<String>,
        keepalive: Int = 25
    ): String {
        val sb = StringBuilder()
        sb.append("private_key=").append(clientPrivateKeyHex).append("\n")
        sb.append("listen_port=0\n")
        sb.append("replace_peers=true\n")
        sb.append("public_key=").append(serverPublicKeyHex).append("\n")
        sb.append("endpoint=").append(serverEndpoint).append("\n")
        sb.append("persistent_keepalive_interval=").append(keepalive).append("\n")
        sb.append("replace_allowed_ips=true\n")
        for (ip in allowedIps) {
            val trimmed = ip.trim()
            if (trimmed.isNotEmpty()) {
                sb.append("allowed_ip=").append(trimmed).append("\n")
            }
        }
        return sb.toString()
    }

    data class WgStats(
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val lastHandshakeSec: Long = 0
    )

    fun parseUapiStats(uapiOutput: String?): WgStats {
        if (uapiOutput.isNullOrEmpty()) return WgStats()
        var rx = 0L
        var tx = 0L
        var handshake = 0L

        val lines = uapiOutput.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("rx_bytes=") -> {
                    rx = trimmed.removePrefix("rx_bytes=").toLongOrNull() ?: rx
                }
                trimmed.startsWith("tx_bytes=") -> {
                    tx = trimmed.removePrefix("tx_bytes=").toLongOrNull() ?: tx
                }
                trimmed.startsWith("last_handshake_time_sec=") -> {
                    handshake = trimmed.removePrefix("last_handshake_time_sec=").toLongOrNull() ?: handshake
                }
            }
        }
        return WgStats(rx, tx, handshake)
    }
}
