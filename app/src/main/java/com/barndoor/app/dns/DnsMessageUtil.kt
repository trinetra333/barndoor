package com.barndoor.app.dns

import kotlin.random.Random

/** Small helpers for working with raw DNS wire-format messages (RFC 1035 section 4). */
object DnsMessageUtil {

    /** Builds a minimal standard A-record query for [domain], e.g. "example.com". */
    fun buildQuery(domain: String): ByteArray {
        val id = Random.nextInt(0, 0xFFFF)
        val header = byteArrayOf(
            ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
            0x01, 0x00, // flags: standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00  // ARCOUNT
        )
        val qname = encodeName(domain)
        val qtypeAndClass = byteArrayOf(0x00, 0x01, 0x00, 0x01) // A, IN
        return header + qname + qtypeAndClass
    }

    private fun encodeName(domain: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        domain.trim('.').split(".").filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII).copyOf(label.length.coerceAtMost(63))
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Reads the QNAME of the first question in a DNS message, e.g. "example.com", or null if unparseable. */
    fun readQuestionName(message: ByteArray): String? {
        if (message.size < 12) return null
        val labels = mutableListOf<String>()
        var offset = 12
        var guard = 0
        while (offset < message.size && guard < 128) {
            guard++
            val len = message[offset].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) break // compression pointer — not expected in a question, bail out
            if (offset + 1 + len > message.size) return null
            labels.add(String(message, offset + 1, len, Charsets.US_ASCII))
            offset += 1 + len
        }
        return if (labels.isEmpty()) null else labels.joinToString(".")
    }
}
