package com.barndoor.app.dns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class SpeedResult(
    val server: DnsServer,
    val millis: Long?, // null = failed/timed out
    val error: String? = null
)

object SpeedTest {

    private const val TEST_DOMAIN = "example.com"
    private const val TIMEOUT_MS = 3000

    /**
     * Tests every server in [servers] in parallel and returns results as each one finishes,
     * via [onResult] (called on the calling coroutine's dispatcher). This runs from a plain
     * Activity/Fragment context — no VpnService.protect() is needed here since Barndoor
     * always excludes itself from its own tunnel, so there's no routing loop to avoid.
     */
    suspend fun runAll(servers: List<DnsServer>, onResult: suspend (SpeedResult) -> Unit) = coroutineScope {
        servers.map { server ->
            async(Dispatchers.IO) {
                val result = testOne(server)
                withContext(Dispatchers.Main) { onResult(result) }
            }
        }.awaitAll()
    }

    private suspend fun testOne(server: DnsServer): SpeedResult {
        val query = DnsMessageUtil.buildQuery(TEST_DOMAIN)
        val start = System.currentTimeMillis()
        return try {
            if (server.dotHostname != null) {
                val client = DotClient(server.dotHostname) { true }
                client.query(query, TIMEOUT_MS)
                client.closeQuietly()
            } else {
                val address = server.primary ?: return SpeedResult(server, null, "No address configured")
                queryPlainUdp(address, query)
            }
            SpeedResult(server, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            SpeedResult(server, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun queryPlainUdp(address: String, query: ByteArray) {
        val socket = DatagramSocket()
        try {
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(address, 53))
            socket.send(DatagramPacket(query, query.size))
            val buf = ByteArray(512)
            socket.receive(DatagramPacket(buf, buf.size))
        } finally {
            socket.close()
        }
    }
}
