package com.barndoor.app.whois

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal WHOIS (RFC 3912) client. WHOIS has no central authoritative server, so the
 * standard approach is: ask IANA which server is authoritative for this domain/IP, then
 * ask that server directly. This follows exactly one such referral, which covers the
 * large majority of real-world lookups without implementing a fully recursive resolver.
 */
object WhoisClient {

    private const val IANA_WHOIS = "whois.iana.org"
    private const val TIMEOUT_MS = 8000

    suspend fun lookup(query: String): String = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val firstResponse = queryServer(IANA_WHOIS, trimmed)

        val referral = extractReferral(firstResponse)
        if (referral != null && !referral.equals(IANA_WHOIS, ignoreCase = true)) {
            val secondResponse = try {
                queryServer(referral, trimmed)
            } catch (e: Exception) {
                null
            }
            if (!secondResponse.isNullOrBlank()) {
                return@withContext "Referred to $referral:\n\n$secondResponse"
            }
        }
        firstResponse
    }

    private fun queryServer(server: String, query: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, 43), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().write("$query\r\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun extractReferral(response: String): String? {
        val patterns = listOf(Regex("(?im)^refer:\\s*(\\S+)"), Regex("(?im)^whois:\\s*(\\S+)"))
        for (pattern in patterns) {
            pattern.find(response)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}
