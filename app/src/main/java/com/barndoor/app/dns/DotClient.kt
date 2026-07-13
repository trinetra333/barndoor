package com.barndoor.app.dns

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal DNS-over-TLS (RFC 7858) client for one resolver hostname. Queries are
 * serialized (one in flight at a time) over a single reused TLS connection, which is
 * transparently reconnected if it drops. DNS-over-TLS uses the exact same 2-byte
 * length-prefixed message framing as DNS-over-TCP, so a plain query byte array in
 * gets a plain response byte array out — callers don't need to know TLS is involved.
 */
class DotClient(
    private val hostname: String,
    private val protectSocket: (Socket) -> Boolean
) {
    private val lock = Any()
    @Volatile private var socket: SSLSocket? = null

    fun query(request: ByteArray, timeoutMs: Int = 5000): ByteArray {
        synchronized(lock) {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val ssl = ensureConnected(timeoutMs)
                    val out = ssl.outputStream
                    out.write(byteArrayOf(((request.size shr 8) and 0xFF).toByte(), (request.size and 0xFF).toByte()))
                    out.write(request)
                    out.flush()

                    val input = ssl.inputStream
                    val lenBuf = ByteArray(2)
                    readFully(input, lenBuf)
                    val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    val respBuf = ByteArray(respLen)
                    readFully(input, respBuf)
                    return respBuf
                } catch (e: Exception) {
                    lastError = e
                    closeQuietly()
                    if (attempt == 0) Log.w(TAG, "DoT query to $hostname failed, retrying once", e)
                }
            }
            throw lastError ?: java.io.IOException("DoT query to $hostname failed")
        }
    }

    private fun ensureConnected(timeoutMs: Int): SSLSocket {
        socket?.let { if (!it.isClosed && it.isConnected) return it }

        // Resolved via the real network — Barndoor excludes itself from its own tunnel,
        // so this lookup (and the connection below) never loops back through the proxy.
        val address: InetAddress = InetAddress.getByName(hostname)

        val plain = Socket()
        protectSocket(plain)
        plain.connect(InetSocketAddress(address, DOT_PORT), timeoutMs)

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(plain, hostname, DOT_PORT, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()

        socket = ssl
        return ssl
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw java.io.IOException("DoT connection to $hostname closed early")
            offset += n
        }
    }

    fun closeQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // already closed — fine
        }
        socket = null
    }

    companion object {
        private const val TAG = "BarndoorDoT"
        private const val DOT_PORT = 853
    }
}

/** Caches one [DotClient] per hostname so TLS connections are reused across queries. */
class DotClientPool(private val protectSocket: (Socket) -> Boolean) {
    private val clients = java.util.concurrent.ConcurrentHashMap<String, DotClient>()

    fun get(hostname: String): DotClient =
        clients.getOrPut(hostname) { DotClient(hostname, protectSocket) }

    fun shutdown() {
        clients.values.forEach { it.closeQuietly() }
        clients.clear()
    }
}
