package com.barndoor.app.dns

import org.json.JSONArray
import org.json.JSONObject

data class DnsServer(
    val name: String,
    val primary: String? = null,
    val secondary: String? = null,
    /** DNS-over-TLS hostname (RFC 7858). When present, this is used instead of [primary]. */
    val dotHostname: String? = null,
    /** Short recommendation badge shown in the list, e.g. "\u26a1 Best for speed". */
    val tagline: String? = null,
    val custom: Boolean = false
) {
    /** Stable key used for per-app assignment and quick-tile cycling. */
    val id: String get() = dotHostname ?: primary ?: name

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("primary", primary ?: JSONObject.NULL)
        put("secondary", secondary ?: JSONObject.NULL)
        put("dotHostname", dotHostname ?: JSONObject.NULL)
        put("tagline", tagline ?: JSONObject.NULL)
        put("custom", custom)
    }

    companion object {
        fun fromJson(obj: JSONObject): DnsServer = DnsServer(
            name = obj.getString("name"),
            primary = if (obj.isNull("primary")) null else obj.optString("primary"),
            secondary = if (obj.isNull("secondary")) null else obj.optString("secondary"),
            dotHostname = if (obj.isNull("dotHostname")) null else obj.optString("dotHostname"),
            tagline = if (obj.isNull("tagline")) null else obj.optString("tagline"),
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

        /**
         * Built-in providers, matched to their DNS-over-TLS hostname so queries are
         * encrypted end-to-end to the resolver. A plain-IP fallback is included where
         * a stable, provider-wide anycast address is known; NextDNS and Control D are
         * personalized services with no generic default, so DoT-only — swap in your
         * own hostname for those (e.g. NextDNS's per-account "abc123.dns.nextdns.io").
         */
        val PRESETS = listOf(
            DnsServer(
                name = "Cloudflare 1.1.1.1",
                primary = "1.1.1.1", secondary = "1.0.0.1",
                dotHostname = "one.one.one.one",
                tagline = "\u26A1 Best for speed"
            ),
            DnsServer(
                name = "Google Public DNS",
                primary = "8.8.8.8", secondary = "8.8.4.4",
                dotHostname = "dns.google",
                tagline = "\uD83C\uDF10 Best all-around reliability"
            ),
            DnsServer(
                name = "Quad9",
                primary = "9.9.9.9", secondary = "149.112.112.112",
                dotHostname = "dns.quad9.net",
                tagline = "\uD83D\uDEE1\uFE0F Best for security"
            ),
            DnsServer(
                name = "AdGuard DNS",
                primary = "94.140.14.14", secondary = "94.140.15.15",
                dotHostname = "dns.adguard.com",
                tagline = "\uD83D\uDEAB Best for ad blocking"
            ),
            DnsServer(
                name = "Mullvad DNS",
                primary = "194.242.2.2", secondary = null,
                dotHostname = "dns.mullvad.net",
                tagline = "\uD83D\uDD12 Best for privacy"
            ),
            DnsServer(
                name = "OpenDNS",
                primary = "208.67.222.222", secondary = "208.67.220.220",
                dotHostname = "dns.opendns.com",
                tagline = "\uD83D\uDEE1\uFE0F Security + optional content filtering"
            ),
            DnsServer(
                name = "CleanBrowsing",
                primary = "185.228.168.9", secondary = "185.228.169.9",
                dotHostname = "security-filter-dns.cleanbrowsing.org",
                tagline = "\uD83D\uDC6A Best for families"
            ),
            DnsServer(
                name = "Comodo Secure DNS",
                primary = "8.26.56.26", secondary = "8.20.247.20",
                dotHostname = "dns.comodo.com",
                tagline = "Malware + phishing protection"
            ),
            DnsServer(
                name = "NextDNS",
                primary = null, secondary = null,
                dotHostname = "dns.nextdns.io",
                tagline = "\u2699\uFE0F Best for customization (edit to your own profile hostname)"
            ),
            DnsServer(
                name = "Control D",
                primary = "76.76.2.0", secondary = "76.76.10.0",
                dotHostname = null,
                tagline = "Custom filtering + geo-unblocking (free unfiltered resolver shown; edit for your own profile)"
            ),
            DnsServer(
                name = "DNS.WATCH",
                primary = "84.200.69.80", secondary = "84.200.70.40",
                dotHostname = null,
                tagline = "No logging, no filtering, DNSSEC validating"
            ),
            DnsServer(
                name = "Yandex DNS (Safe)",
                primary = "77.88.8.8", secondary = "77.88.8.1",
                dotHostname = null,
                tagline = "Blocks malicious sites (RU-based provider)"
            ),
            DnsServer(
                name = "CIRA Canadian Shield",
                primary = "149.112.121.10", secondary = "149.112.122.10",
                dotHostname = "protected.canadianshield.cira.ca",
                tagline = "\uD83C\uDDE8\uD83C\uDDE6 Malware/phishing blocking, Canadian non-profit"
            ),
            DnsServer(
                name = "CenturyLink (Level 3)",
                primary = "205.171.3.66", secondary = "205.171.202.166",
                dotHostname = null,
                tagline = "Legacy carrier-grade resolver"
            )
        )
    }
}
