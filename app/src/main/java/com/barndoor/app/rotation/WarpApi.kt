package com.barndoor.app.rotation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class WarpRegistration(
    val deviceId: String,
    val accessToken: String,
    val tunnelIpv4: String,
    val tunnelIpv6: String?,
    val peerPublicKey: String,
    val peerEndpoint: String
)

/**
 * Cloudflare WARP's registration endpoint — the same **unofficial, reverse-engineered**
 * API the open-source `wgcf` tool uses (Cloudflare doesn't publish this for third-party
 * use). It's what lets this app generate a completely anonymous WireGuard identity with
 * zero signup: no email, no account number, nothing typed in.
 *
 * The tradeoff for that: this is meaningfully less stable than Mullvad's API. Cloudflare
 * has changed the required [API_VERSION]/[CLIENT_VERSION] pair multiple times over the
 * years (each change breaks every hardcoded client, including this one, until updated).
 * If registration starts failing, check https://github.com/ViRb3/wgcf for the current
 * values and update the two constants below — that's the entire fix.
 */
object WarpApi {

    // If WARP registration starts failing, this pair is almost certainly why — Cloudflare
    // has bumped these before. Check github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
    // for the current ApiVersion/CF-Client-Version and update both together.
    private const val API_VERSION = "v0a2158"
    private const val CLIENT_VERSION = "a-6.30-2158"

    private const val API_BASE = "https://api.cloudflareclient.com/$API_VERSION"
    private const val DEFAULT_PEER_ENDPOINT = "engage.cloudflareclient.com:2408"
    // Cloudflare's fixed WARP relay public key — stable across years of wgcf usage,
    // but overridden below by whatever the registration response actually returns.
    private const val DEFAULT_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    suspend fun register(publicKeyBase64: String): WarpRegistration = withContext(Dispatchers.IO) {
        val tosTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val body = JSONObject()
            .put("key", publicKeyBase64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tosTimestamp)
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        val (code, response) = request(
            "$API_BASE/reg", "POST",
            mapOf(
                "Content-Type" to "application/json; charset=UTF-8",
                "User-Agent" to "okhttp/3.12.1",
                "CF-Client-Version" to CLIENT_VERSION
            ),
            body
        )

        if (code !in 200..299) {
            throw IOException(
                "WARP registration failed ($code). Cloudflare's unofficial API may have " +
                    "changed its version requirement — see WarpApi.kt for how to update it. " +
                    "Response: ${response.take(300)}"
            )
        }

        val json = JSONObject(response)
        val config = json.optJSONObject("config") ?: throw IOException("Unexpected WARP response: no config block")
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
        val peer = config.optJSONArray("peers")?.optJSONObject(0)
        val endpoint = peer?.optJSONObject("endpoint")

        val tunnelIpv4 = addresses?.optString("v4")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Unexpected WARP response: no IPv4 address assigned")

        WarpRegistration(
            deviceId = json.optString("id"),
            accessToken = json.optString("token"),
            tunnelIpv4 = tunnelIpv4,
            tunnelIpv6 = addresses.optString("v6").takeIf { it.isNotBlank() },
            peerPublicKey = peer.optString("public_key").takeIf { it.isNotBlank() } ?: DEFAULT_PEER_PUBLIC_KEY,
            peerEndpoint = endpoint?.optString("host")?.takeIf { it.isNotBlank() } ?: DEFAULT_PEER_ENDPOINT
        )
    }

    private fun request(urlStr: String, method: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.doOutput = true
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setRequestProperty("Content-Length", bytes.size.toString())
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            } ?: ""
            code to text
        } finally {
            connection.disconnect()
        }
    }
}
