package com.barndoor.app.dns

import android.util.Log
import com.barndoor.app.util.NetUtil
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * A deliberately small TCP state machine — just enough to proxy DNS-over-TCP.
 * It terminates the client's TCP connection at the tun interface (posing as the
 * resolver IP), opens a real TCP connection out to the actual resolver, and
 * splices bytes between the two. Multi-query pipelining, out-of-order segments,
 * and retransmission are not implemented — DNS-over-TCP in practice is one
 * query/response per connection, so this covers the real-world case without
 * needing a full RFC 793 implementation.
 */
class TcpDnsRelay(
    private val output: FileOutputStream,
    private val writeLock: Any,
    private val protectSocket: (Socket) -> Boolean
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val pool = Executors.newCachedThreadPool()
    private val ipIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private class Connection(
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        var clientNext: Int, // next expected sequence number from the client (our ACK value)
        var serverSeq: Int,  // our next sequence number to send
        var socket: Socket? = null
    ) {
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
            // Not DNS — we only ever routed the resolver IP into this tunnel, so this is
            // an app hitting that same IP for something else. Fail the connection fast
            // instead of leaving it to hang until the OS gives up.
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
            val conn = Connection(
                clientIp = srcIp,
                serverIp = dstIp,
                clientPort = srcPort,
                clientNext = seq + 1,
                serverSeq = Random.nextInt()
            )
            connections[key] = conn
            sendSegment(conn, flags = SYN or ACK, seq = conn.serverSeq, ack = conn.clientNext)
            conn.serverSeq += 1
            pool.execute { openRealConnection(conn) }
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
            try {
                conn.socket?.getOutputStream()?.write(packet, payloadStart, payloadLen)
            } catch (e: Exception) {
                Log.w(TAG, "write to real DNS server failed", e)
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

    private fun openRealConnection(conn: Connection) {
        val socket = Socket()
        try {
            protectSocket(socket)
            socket.connect(InetSocketAddress(NetUtil.bytesToIp(conn.serverIp), 53), CONNECT_TIMEOUT_MS)
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
            fromIp = conn.serverIp, fromPort = 53,
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
