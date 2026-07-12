package com.barndoor.app.dns

import org.json.JSONArray
import org.json.JSONObject

data class DnsServer(
    val name: String,
    val primary: String,
    val secondary: String? = null,
    val custom: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("primary", primary)
        put("secondary", secondary ?: JSONObject.NULL)
        put("custom", custom)
    }

    companion object {
        fun fromJson(obj: JSONObject): DnsServer = DnsServer(
            name = obj.getString("name"),
            primary = obj.getString("primary"),
            secondary = if (obj.isNull("secondary")) null else obj.getString("secondary"),
            custom = obj.optBoolean("custom", false)
        )

        fun listToJson(servers: List<DnsServer>): String {
            val arr = JSONArray()
            servers.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String?): List<DnsServer> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        /** Well-known public resolvers shipped with the app. */
        val PRESETS = listOf(
            DnsServer("Cloudflare", "1.1.1.1", "1.0.0.1"),
            DnsServer("Google", "8.8.8.8", "8.8.4.4"),
            DnsServer("Quad9", "9.9.9.9", "149.112.112.112"),
            DnsServer("AdGuard", "94.140.14.14", "94.140.15.15"),
            DnsServer("OpenDNS", "208.67.222.222", "208.67.220.220"),
            DnsServer("CleanBrowsing", "185.228.168.9", "185.228.169.9")
        )
    }
}
