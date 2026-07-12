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
        // Pseudo header: src(4) + dst(4) + zero(1) + proto(1) + udpLength(2) = 12 bytes
        val pseudoAndUdp = ByteArray(12 + udpLength)
        System.arraycopy(srcIp, 0, pseudoAndUdp, 0, 4)
        System.arraycopy(dstIp, 0, pseudoAndUdp, 4, 4)
        pseudoAndUdp[8] = 0
        pseudoAndUdp[9] = 17 // UDP protocol number
        pseudoAndUdp[10] = ((udpLength shr 8) and 0xFF).toByte()
        pseudoAndUdp[11] = (udpLength and 0xFF).toByte()
        System.arraycopy(udpSegment, udpOffset, pseudoAndUdp, 12, udpLength)
        // zero out the checksum field (bytes 6-7 of the UDP header) before computing
        pseudoAndUdp[12 + 6] = 0
        pseudoAndUdp[12 + 7] = 0
        val result = checksum(pseudoAndUdp, 0, pseudoAndUdp.size)
        // UDP checksum of 0x0000 is transmitted as 0xFFFF (0 means "no checksum")
        return if (result == 0) 0xFFFF else result
    }

    fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun readShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun ipToBytes(ip: String): ByteArray =
        ip.split(".").map { it.toInt().toByte() }.toByteArray()

    fun bytesToIp(bytes: ByteArray, offset: Int = 0): String =
        "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
}
