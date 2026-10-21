package network.stabirush.vpn

import org.json.JSONArray
import org.json.JSONObject

data class NamedSubnet(
    val id: String,
    val name: String,
    val cidr: String,
    val description: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("cidr", cidr)
        put("description", description)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): NamedSubnet {
            return NamedSubnet(
                id = obj.optString("id", "sub-${System.currentTimeMillis()}"),
                name = obj.optString("name", "Subnet"),
                cidr = obj.optString("cidr", "10.10.0.0/24"),
                description = obj.optString("description", "")
            )
        }

        fun defaultList(): List<NamedSubnet> = listOf(
            NamedSubnet("sub-stabirush-mesh", "Stabirush Mesh (wg0)", "10.10.0.0/24", "Jaringan VPN WireGuard Stabirush"),
            NamedSubnet("sub-lan-kantor", "Subnet A - LAN Kantor (MikroTik)", "192.168.101.0/24", "Jaringan lokal kantor via router 10.10.0.4"),
            NamedSubnet("sub-core-dns", "Subnet B - Core Server & AdGuard", "10.0.0.0/24", "Server internal & AdGuard Home (10.0.0.154)")
        )
    }
}

data class WifiRule(
    var ssid: String,
    var profileMode: String, // "split", "secure", "bypass_wstunnel", "bypass_udp2raw", "disconnect"
    var subnets: MutableList<String> = mutableListOf(),
    var subnetNames: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("profileMode", profileMode)
        val arr = JSONArray()
        subnets.forEach { arr.put(it) }
        put("subnets", arr)
        put("subnetNames", subnetNames)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): WifiRule {
            val subs = mutableListOf<String>()
            val arr = obj.optJSONArray("subnets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    subs.add(arr.optString(i))
                }
            }
            return WifiRule(
                ssid = obj.optString("ssid", "*"),
                profileMode = obj.optString("profileMode", "split"),
                subnets = subs,
                subnetNames = obj.optString("subnetNames", "")
            )
        }
    }
}

data class PeerDevice(
    val name: String,
    val allocatedIP: String,
    val publicKey: String,
    val routingMode: String = "split",
    val transportMode: String = "udp",
    val wifiRules: List<WifiRule> = emptyList()
)
