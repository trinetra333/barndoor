package com.barndoor.app.dns

import android.util.Log
import com.barndoor.app.util.NetUtil
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * A deliberately small TCP state machine — just enough to proxy DNS-over-TCP. It
 * terminates the client's TCP connection at the tun interface (posing as the fake DNS
 * address), then either splices it to a real plain-DNS TCP connection, or — for
 * resolvers that use DNS-over-TLS — reframes each individual DNS message through a
 * pooled [DotClient]. DNS-over-TCP and DNS-over-TLS share the same 2-byte
 * length-prefixed message framing (RFC 1035 / RFC 7858), so translating between them
 * is just a matter of where each message gets sent, not how it's framed.
 *
 * Multi-query pipelining, out-of-order segments, and retransmission aren't
 * implemented — DNS-over-TCP in practice is one query/response per connection, so
 * this covers the real-world case without needing a full RFC 793 stack.
 */
class TcpDnsRelay(
    private val output: FileOutputStream,
    private val writeLock: Any,
    private val protectSocket: (Socket) -> Boolean,
    private val resolveTarget: (localPort: Int) -> ResolvedTarget,
    private val appLabelFor: (String?) -> String,
    private val loggingEnabled: () -> Boolean,
    private val dotPool: DotClientPool
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val pool = Executors.newCachedThreadPool()
    private val ipIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private class Connection(
        val clientIp: ByteArray,
        val tunnelIp: ByteArray,
        val clientPort: Int,
        val server: DnsServer,
        val packageName: String?,
        var clientNext: Int, // next expected sequence number from the client (our ACK value)
        var serverSeq: Int,  // our next sequence number to send
        var socket: Socket? = null
    ) {
        val pending = ByteArrayOutputStream()
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var closing: Boolean = false
    }

    /** [ipHeaderLen] is the already-parsed IPv4 header length of [packet]. */
    fun handle(packet: ByteArray, ipHeaderLen: Int) {
        if (packet.size < ipHeaderLen + 20) return
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val tcpOffset = ipHeaderLen

        val srcPort = NetUtil.readShort(packet, tcpOffset)
        val dstPort = NetUtil.readShort(packet, tcpOffset + 2)
        val seq = NetUtil.readInt(packet, tcpOffset + 4)
        val dataOffsetWords = (packet[tcpOffset + 12].toInt() and 0xF0) ushr 4
        val dataOffset = dataOffsetWords * 4
        if (dataOffset < 20 || tcpOffset + dataOffset > packet.size) return
        val flags = packet[tcpOffset + 13].toInt() and 0xFF

        val payloadStart = tcpOffset + dataOffset
        val payloadLen = (packet.size - payloadStart).coerceAtLeast(0)

        if (dstPort != 53) {
            if (flags and SYN != 0 && flags and ACK == 0) {
                sendRst(fromIp = dstIp, fromPort = dstPort, toIp = srcIp, toPort = srcPort, ackFor = seq + 1)
            }
            return
        }

        val key = "${NetUtil.bytesToIp(dstIp)}:$srcPort"

        if (flags and RST != 0) {
            connections.remove(key)?.let { closeQuietly(it) }
            return
        }

        if (flags and SYN != 0 && flags and ACK == 0) {
            val target = resolveTarget(srcPort)
            val conn = Connection(
                clientIp = srcIp,
                tunnelIp = dstIp,
                clientPort = srcPort,
                server = target.server,
                packageName = target.packageName,
                clientNext = seq + 1,
                serverSeq = Random.nextInt()
            )
            connections[key] = conn
            sendSegment(conn, flags = SYN or ACK, seq = conn.serverSeq, ack = conn.clientNext)
            conn.serverSeq += 1
            if (conn.server.dotHostname == null) {
                pool.execute { openRealConnection(conn) }
            }
            return
        }

        val conn = connections[key]
        if (conn == null) {
            if (flags and FIN == 0) {
                sendRst(fromIp = dstIp, fromPort = dstPort, toIp = srcIp, toPort = srcPort, ackFor = seq)
            }
            return
        }
        conn.lastActivity = System.currentTimeMillis()

        if (payloadLen > 0) {
            conn.clientNext = seq + payloadLen
            if (conn.server.dotHostname != null) {
                synchronized(conn.pending) {
                    conn.pending.write(packet, payloadStart, payloadLen)
                }
                pool.execute { processPendingDot(conn) }
            } else {
                try {
                    conn.socket?.getOutputStream()?.write(packet, payloadStart, payloadLen)
                } catch (e: Exception) {
                    Log.w(TAG, "write to real DNS server failed", e)
                }
            }
            sendSegment(conn, flags = ACK, seq = conn.serverSeq, ack = conn.clientNext)
        }

        if (flags and FIN != 0) {
            conn.clientNext += 1
            sendSegment(conn, flags = ACK, seq = conn.serverSeq, ack = conn.clientNext)
            try {
                conn.socket?.shutdownOutput()
            } catch (e: Exception) {
                // socket may already be closed/never opened — fine, we're tearing down anyway
            }
            finishClosing(key, conn)
        }
    }

    /** Extracts any complete [len][DNS message] frames the client has sent so far and resolves each via DoT. */
    private fun processPendingDot(conn: Connection) {
        while (true) {
            val message = synchronized(conn.pending) {
                val bytes = conn.pending.toByteArray()
                if (bytes.size < 2) return@synchronized null
                val msgLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                if (bytes.size < 2 + msgLen) return@synchronized null
                val msg = bytes.copyOfRange(2, 2 + msgLen)
                val remaining = bytes.copyOfRange(2 + msgLen, bytes.size)
                conn.pending.reset()
                conn.pending.write(remaining)
                msg
            } ?: return

            try {
                val hostname = conn.server.dotHostname ?: return
                val startTime = System.currentTimeMillis()
                val response = dotPool.get(hostname).query(message)
                if (loggingEnabled()) {
                    QueryLogStore.add(
                        QueryLog(
                            timestamp = startTime,
                            domain = DnsMessageUtil.readQuestionName(message) ?: "(unparsed)",
                            resolverName = conn.server.name,
                            appLabel = appLabelFor(conn.packageName),
                            protocol = "DoT (TCP)",
                            durationMs = System.currentTimeMillis() - startTime,
                            success = true
                        )
                    )
                }
                val framed = ByteArray(2 + response.size)
                framed[0] = ((response.size shr 8) and 0xFF).toByte()
                framed[1] = (response.size and 0xFF).toByte()
                System.arraycopy(response, 0, framed, 2, response.size)
                synchronized(conn) {
                    sendSegment(conn, flags = PSH or ACK, seq = conn.serverSeq, ack = conn.clientNext, payload = framed, payloadLen = framed.size)
                    conn.serverSeq += framed.size
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoT query failed for ${conn.server.name}", e)
            }
        }
    }

    private fun openRealConnection(conn: Connection) {
        val hostOrIp = conn.server.primary
        if (hostOrIp == null) {
            sendSegment(conn, flags = FIN or ACK, seq = conn.serverSeq, ack = conn.clientNext)
            conn.serverSeq += 1
            return
        }
        val socket = Socket()
        try {
            protectSocket(socket)
            socket.connect(InetSocketAddress(hostOrIp, 53), CONNECT_TIMEOUT_MS)
            conn.socket = socket

            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                sendSegment(conn, flags = PSH or ACK, seq = conn.serverSeq, ack = conn.clientNext, payload = buffer, payloadLen = n)
                conn.serverSeq += n
            }
        } catch (e: Exception) {
            Log.w(TAG, "real DNS TCP connection failed", e)
        } finally {
            sendSegment(conn, flags = FIN or ACK, seq = conn.serverSeq, ack = conn.clientNext)
            conn.serverSeq += 1
        }
    }

    private fun finishClosing(key: String, conn: Connection) {
        if (conn.closing) return
        conn.closing = true
        pool.execute {
            closeQuietly(conn)
            connections.remove(key)
        }
    }

    private fun closeQuietly(conn: Connection) {
        try { conn.socket?.close() } catch (e: Exception) { /* already closed */ }
    }

    /** Drops connections that have seen no traffic in a while, to avoid leaking sockets/threads. */
    fun sweepStale() {
        val cutoff = System.currentTimeMillis() - STALE_TIMEOUT_MS
        val stale = connections.filterValues { it.lastActivity < cutoff }
        stale.forEach { (key, conn) ->
            closeQuietly(conn)
            connections.remove(key)
        }
    }

    fun shutdown() {
        connections.values.forEach { closeQuietly(it) }
        connections.clear()
        pool.shutdownNow()
    }

    private fun sendSegment(
        conn: Connection, flags: Int, seq: Int, ack: Int,
        payload: ByteArray = ByteArray(0), payloadLen: Int = payload.size
    ) {
        sendRaw(
            fromIp = conn.tunnelIp, fromPort = 53,
            toIp = conn.clientIp, toPort = conn.clientPort,
            flags = flags, seq = seq, ack = ack,
            payload = payload, payloadLen = payloadLen
        )
    }

    private fun sendRst(fromIp: ByteArray, fromPort: Int, toIp: ByteArray, toPort: Int, ackFor: Int) {
        sendRaw(
            fromIp = fromIp, fromPort = fromPort, toIp = toIp, toPort = toPort,
            flags = RST or ACK, seq = 0, ack = ackFor,
            payload = ByteArray(0), payloadLen = 0
        )
    }

    private fun sendRaw(
        fromIp: ByteArray, fromPort: Int, toIp: ByteArray, toPort: Int,
        flags: Int, seq: Int, ack: Int, payload: ByteArray, payloadLen: Int
    ) {
        val tcpHeaderLen = 20
        val totalLength = 20 + tcpHeaderLen + payloadLen
        val out = ByteArray(totalLength)

        // --- IPv4 header ---
        out[0] = 0x45
        NetUtil.writeShort(out, 2, totalLength)
        NetUtil.writeShort(out, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
        out[6] = 0x40
        out[8] = 64
        out[9] = 6 // TCP
        System.arraycopy(fromIp, 0, out, 12, 4)
        System.arraycopy(toIp, 0, out, 16, 4)
        val ipChecksum = NetUtil.checksum(out, 0, 20)
        NetUtil.writeShort(out, 10, ipChecksum)

        // --- TCP header ---
        val t = 20
        NetUtil.writeShort(out, t, fromPort)
        NetUtil.writeShort(out, t + 2, toPort)
        NetUtil.writeInt(out, t + 4, seq)
        NetUtil.writeInt(out, t + 8, ack)
        out[t + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        out[t + 13] = flags.toByte()
        NetUtil.writeShort(out, t + 14, 65535) // window size
        NetUtil.writeShort(out, t + 16, 0) // checksum placeholder
        NetUtil.writeShort(out, t + 18, 0) // urgent pointer

        if (payloadLen > 0) {
            System.arraycopy(payload, 0, out, t + tcpHeaderLen, payloadLen)
        }

        val tcpChecksum = NetUtil.tcpChecksum(fromIp, toIp, out, t, tcpHeaderLen + payloadLen)
        NetUtil.writeShort(out, t + 16, tcpChecksum)

        try {
            synchronized(writeLock) {
                output.write(out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tun write failed", e)
        }
    }

    companion object {
        private const val TAG = "BarndoorTcpRelay"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val STALE_TIMEOUT_MS = 60_000L

        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
    }
}
