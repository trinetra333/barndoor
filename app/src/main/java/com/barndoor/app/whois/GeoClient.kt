package com.barndoor.app.whois

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeoInfo(
    val ip: String,
    val country: String?,
    val region: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isp: String?,
    val org: String?,
    val asn: String?
)

/** Free, keyless IP geolocation via https://ipwho.is (no account, no API key, HTTPS). */
object GeoClient {

    suspend fun lookup(ip: String): GeoInfo? = withContext(Dispatchers.IO) {
        val connection = URL("https://ipwho.is/$ip").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("success", true)) return@withContext null

            val connectionInfo = json.optJSONObject("connection")
            GeoInfo(
                ip = json.optString("ip", ip),
                country = json.optString("country").ifBlank { null },
                region = json.optString("region").ifBlank { null },
                city = json.optString("city").ifBlank { null },
                latitude = if (json.has("latitude")) json.optDouble("latitude") else null,
                longitude = if (json.has("longitude")) json.optDouble("longitude") else null,
                isp = connectionInfo?.optString("isp")?.ifBlank { null },
                org = connectionInfo?.optString("org")?.ifBlank { null },
                asn = connectionInfo?.optInt("asn")?.toString()
            )
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
