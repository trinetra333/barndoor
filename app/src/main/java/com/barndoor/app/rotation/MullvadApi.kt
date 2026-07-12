package com.barndoor.app.rotation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal client for the parts of Mullvad's public API this app needs:
 *  - the WireGuard relay list (no auth required)
 *  - registering our WireGuard public key against the user's account (auth required)
 *
 * Reference: https://api.mullvad.net (documented informally by Mullvad's own CLI/app
 * source and community write-ups; endpoints used here are read-only / additive and do
 * not require an existing device or subscription beyond a valid account number).
 */
object MullvadApi {

    private const val RELAY_LIST_URL = "https://api.mullvad.net/public/relays/wireguard/v1/"
    private const val AUTH_TOKEN_URL = "https://api.mullvad.net/auth/v1/token"
    private const val DEVICES_URL = "https://api.mullvad.net/accounts/v1/devices"
    private const val DEFAULT_WG_PORT = 51820

    const val WIREGUARD_PORT = DEFAULT_WG_PORT

    suspend fun fetchRelays(): List<Relay> = withContext(Dispatchers.IO) {
        val (code, body) = request(RELAY_LIST_URL, "GET", null, null)
        if (code !in 200..299) throw IOException("Relay list request failed ($code)")

        val root = JSONObject(body)
        val countries = root.optJSONArray("countries") ?: return@withContext emptyList()
        val result = mutableListOf<Relay>()

        for (ci in 0 until countries.length()) {
            val country = countries.getJSONObject(ci)
            val countryName = country.optString("name", "Unknown")
            val countryCode = country.optString("code", countryName.take(2).lowercase())
            val cities = country.optJSONArray("cities") ?: continue

            for (cj in 0 until cities.length()) {
                val city = cities.getJSONObject(cj)
                val cityName = city.optString("name", "")
                val relays = city.optJSONArray("relays") ?: continue

                for (rk in 0 until relays.length()) {
                    val relay = relays.getJSONObject(rk)
                    val hostname = relay.optString("hostname", "")
                    val publicKey = relay.optString("public_key", "")
                    val ipv4 = relay.optString("ipv4_addr_in", "")
                    val active = relay.optBoolean("active", true)
                    if (hostname.isBlank() || publicKey.isBlank() || ipv4.isBlank()) continue

                    result.add(
                        Relay(
                            countryName = countryName,
                            countryCode = countryCode,
                            cityName = cityName,
                            hostname = hostname,
                            publicKey = publicKey,
                            ipv4AddrIn = ipv4,
                            active = active
                        )
                    )
                }
            }
        }
        result
    }

    /**
     * Registers [pubkeyBase64] as a new WireGuard device on the given Mullvad
     * [accountNumber], returning the tunnel address Mullvad assigned us. This only
     * needs to be called once per key; the same registration is then reused while
     * we rotate which *relay* (exit server) we peer with.
     */
    suspend fun registerDevice(accountNumber: String, pubkeyBase64: String): DeviceRegistration =
        withContext(Dispatchers.IO) {
            val tokenBody = JSONObject().put("account_number", accountNumber).toString()
            val (tokenCode, tokenResp) = request(
                AUTH_TOKEN_URL, "POST",
                mapOf("Content-Type" to "application/json"), tokenBody
            )
            if (tokenCode !in 200..299) {
                throw IOException(describeError("Account authentication failed", tokenCode, tokenResp))
            }
            val accessToken = JSONObject(tokenResp).optString("access_token")
            if (accessToken.isBlank()) throw IOException("Mullvad did not return an access token")

            val deviceBody = JSONObject()
                .put("pubkey", pubkeyBase64)
                .put("hijack_dns", false)
                .toString()
            val (deviceCode, deviceResp) = request(
                DEVICES_URL, "POST",
                mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $accessToken"
                ),
                deviceBody
            )
            if (deviceCode !in 200..299) {
                throw IOException(describeError("Device registration failed", deviceCode, deviceResp))
            }
            val json = JSONObject(deviceResp)
            DeviceRegistration(
                deviceId = json.optString("id"),
                ipv4Address = json.optString("ipv4_address"),
                ipv6Address = json.optString("ipv6_address").ifBlank { null }
            )
        }

    private fun describeError(prefix: String, code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("error", body) }.getOrDefault(body)
        return "$prefix ($code): $detail"
    }

    private fun request(
        urlStr: String,
        method: String,
        headers: Map<String, String>?,
        body: String?
    ): Pair<Int, String> {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            headers?.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.setRequestProperty("Content-Length", bytes.size.toString())
                val os: OutputStream = connection.outputStream
                os.write(bytes)
                os.flush()
                os.close()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: ""
            code to text
        } finally {
            connection.disconnect()
        }
    }
}
