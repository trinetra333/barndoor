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
 *  - It blocks DNS lookups for domains the device owner explicitly listed in
 *    the app UI. Blocked lookups get an NXDOMAIN-style empty response, they
 *    are never redirected to a substitute/spoofed page.
 *  - If you plan to apply filtering to hotspot guests, the honest way to do
 *    that is on a router/AP you administer (e.g. via dnsmasq/pfSense) with a
 *    captive-portal notice, not through interception on someone's own
 *    handset.
 */
class BarndoorVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private val blockedDomains = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringArrayListExtra(EXTRA_BLOCKED_DOMAINS)?.let {
            blockedDomains.clear()
            blockedDomains.addAll(it.map { d -> d.trim().lowercase() })
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

    private fun isBlocked(domain: String): Boolean =
        blockedDomains.any { domain.equals(it, ignoreCase = true) || domain.endsWith(".$it") }

    override fun onDestroy() {
        running = false
        vpnInterface?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BarndoorVpnService"
        const val EXTRA_BLOCKED_DOMAINS = "blocked_domains"
    }
}
