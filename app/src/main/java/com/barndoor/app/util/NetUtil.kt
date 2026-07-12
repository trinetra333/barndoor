package com.barndoor.app.util

/**
 * Small helpers for hand-building IPv4/UDP packets, used by the local DNS proxy
 * (DnsVpnService) to forward only DNS (port 53) traffic through a chosen resolver
 * without needing a full userspace TCP/IP stack.
 */
object NetUtil {

    /** RFC 1071 Internet checksum over [length] bytes of [data] starting at [offset]. */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            // odd trailing byte, pad with zero
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum, which is computed over a pseudo IPv4 header + the UDP segment. */
    fun udpChecksum(
        srcIp: ByteArray, dstIp: ByteArray,
        udpSegment: ByteArray, udpOffset: Int, udpLength: Int
    ): Int {
        val result = pseudoHeaderChecksum(srcIp, dstIp, 17, udpSegment, udpOffset, udpLength, checksumFieldOffset = 6)
        // UDP checksum of 0x0000 is transmitted as 0xFFFF (0 means "no checksum")
        return if (result == 0) 0xFFFF else result
    }

    /** TCP checksum, computed the same way as UDP's but over the whole TCP segment. */
    fun tcpChecksum(
        srcIp: ByteArray, dstIp: ByteArray,
        tcpSegment: ByteArray, tcpOffset: Int, tcpLength: Int
    ): Int = pseudoHeaderChecksum(srcIp, dstIp, 6, tcpSegment, tcpOffset, tcpLength, checksumFieldOffset = 16)

    /**
     * Shared RFC 793/768 pseudo-header checksum: src(4) + dst(4) + zero(1) + protocol(1) +
     * segmentLength(2), followed by the segment itself with its own checksum field zeroed.
     */
    private fun pseudoHeaderChecksum(
        srcIp: ByteArray, dstIp: ByteArray, protocol: Int,
        segment: ByteArray, segOffset: Int, segLength: Int, checksumFieldOffset: Int
    ): Int {
        val pseudoAndSegment = ByteArray(12 + segLength)
        System.arraycopy(srcIp, 0, pseudoAndSegment, 0, 4)
        System.arraycopy(dstIp, 0, pseudoAndSegment, 4, 4)
        pseudoAndSegment[8] = 0
        pseudoAndSegment[9] = protocol.toByte()
        pseudoAndSegment[10] = ((segLength shr 8) and 0xFF).toByte()
        pseudoAndSegment[11] = (segLength and 0xFF).toByte()
        System.arraycopy(segment, segOffset, pseudoAndSegment, 12, segLength)
        pseudoAndSegment[12 + checksumFieldOffset] = 0
        pseudoAndSegment[12 + checksumFieldOffset + 1] = 0
        return checksum(pseudoAndSegment, 0, pseudoAndSegment.size)
    }

    fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun readShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    fun ipToBytes(ip: String): ByteArray =
        ip.split(".").map { it.toInt().toByte() }.toByteArray()

    fun bytesToIp(bytes: ByteArray, offset: Int = 0): String =
        "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
}
