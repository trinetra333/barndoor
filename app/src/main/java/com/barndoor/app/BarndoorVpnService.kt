package com.barndoor.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * A local, on-device DNS filter.
 *
 * Scope and intent, please read before modifying:
 *  - This VpnService only ever filters traffic that originates FROM THIS
 *    DEVICE. Android's VpnService API has no ability to intercept another
 *    phone's traffic just because that phone is using this device's hotspot -
 *    doing that would require a completely different (and unsupported,
 *    unethical) approach such as ARP spoofing on the local network.
 *  - It blocks (or, in allow-list mode, permits) DNS lookups for domains the
 *    device owner explicitly configured in the app UI. Blocked lookups get an
 *    NXDOMAIN-style empty response - they are never redirected to a
 *    substitute/spoofed page. This service can only ever say "no" to a
 *    domain, never "yes, but send you somewhere else instead."
 *  - If you plan to apply filtering to hotspot guests, the honest way to do
 *    that is on a router/AP you administer (e.g. via dnsmasq/pfSense) with a
 *    captive-portal notice, not through interception on someone's own
 *    handset.
 *
 * Two modes are supported:
 *  - BLOCK_LIST (default): everything is allowed except the listed domains.
 *  - ALLOW_LIST: everything is blocked except the listed domains. Useful for
 *    a "focus mode" that restricts this device to only a chosen site (e.g.
 *    YouTube) plus whatever supporting domains it needs to actually load
 *    (for YouTube that's its video-CDN host, googlevideo.com).
 */
class BarndoorVpnService : VpnService() {

    enum class Mode { BLOCK_LIST, ALLOW_LIST }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private var mode: Mode = Mode.BLOCK_LIST
    private val domainList = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringArrayListExtra(EXTRA_DOMAIN_LIST)?.let {
            domainList.clear()
            domainList.addAll(it.map { d -> d.trim().lowercase() })
        }
        intent?.getStringExtra(EXTRA_MODE)?.let {
            mode = Mode.valueOf(it)
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return

        val builder = Builder()
            .setSession("Barndoor Filter")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("0.0.0.0", 0)

        vpnInterface = builder.establish()
        running = true

        Thread {
            try {
                runFilterLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Filter loop stopped: ${e.message}")
            }
        }.start()
    }

    /**
     * Simplified packet loop. A production build would parse real IP/UDP/DNS
     * packets; this scaffold shows where that logic plugs in and keeps the
     * blocking decision (isBlocked) isolated and testable.
     */
    private fun runFilterLoop() {
        val descriptor = vpnInterface ?: return
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val packet = ByteBuffer.allocate(32767)

        while (running) {
            val length = input.read(packet.array())
            if (length <= 0) continue

            // In a full implementation: parse the DNS query name out of the
            // packet here. If isBlocked(domain) is true, write back a
            // synthetic NXDOMAIN response instead of forwarding upstream.
            // Left intentionally minimal for this scaffold.

            packet.clear()
        }

        input.close()
        output.close()
    }

    /**
     * Decides whether a DNS lookup for [domain] should be blocked.
     * In BLOCK_LIST mode: block only domains in the list.
     * In ALLOW_LIST mode: block everything EXCEPT domains in the list
     * (e.g. a YouTube-only focus mode).
     */
    private fun isBlocked(domain: String): Boolean {
        val matchesList = domainList.any { domain.equals(it, ignoreCase = true) || domain.endsWith(".$it") }
        return when (mode) {
            Mode.BLOCK_LIST -> matchesList
            Mode.ALLOW_LIST -> !matchesList
        }
    }

    override fun onDestroy() {
        running = false
        vpnInterface?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BarndoorVpnService"
        const val EXTRA_DOMAIN_LIST = "domain_list"
        const val EXTRA_MODE = "mode"

        /** Domains YouTube playback needs beyond the main site. */
        val YOUTUBE_ONLY_DOMAINS = listOf(
            "youtube.com",
            "youtu.be",
            "googlevideo.com",
            "ytimg.com"
        )
    }
}
