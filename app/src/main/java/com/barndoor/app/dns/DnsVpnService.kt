package com.barndoor.app.dns

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.barndoor.app.BarndoorApp
import com.barndoor.app.MainActivity
import com.barndoor.app.R
import com.barndoor.app.util.NetUtil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local, no-root VPN that intercepts *only* DNS (UDP port 53) packets bound for the
 * chosen resolver(s) and relays them over a protected socket. Every other destination
 * is left completely alone (never routed into the tunnel), so this only changes DNS,
 * not general traffic.
 *
 * Limitation: UDP DNS only. Apps that hardcode DNS-over-HTTPS/TLS to a different
 * provider, or that fall back to TCP DNS for large responses, will bypass this proxy.
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val forwardPool = Executors.newCachedThreadPool()
    private var readerThread: Thread? = null
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startProxy()
            }
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (running.get()) {
            // Already running — restart with the (possibly new) selected server.
            stopProxyInternal()
        }
        val repo = DnsRepository(this)
        val server = repo.getSelectedServer() ?: DnsServer.PRESETS.first()

        val builder = Builder()
            .setSession("Barndoor DNS (${server.name})")
            .addAddress(TUNNEL_ADDRESS, 32)
            .addDnsServer(server.primary)
            .addRoute(server.primary, 32)
            .setMtu(MTU)

        server.secondary?.let {
            builder.addDnsServer(it)
            builder.addRoute(it, 32)
        }

        val selectedApps = repo.getSelectedApps()
        try {
            if (selectedApps.isEmpty()) {
                builder.addDisallowedApplication(packageName)
            } else {
                selectedApps.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unknown package $pkg", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Per-app filtering setup failed, defaulting to system-wide", e)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        running.set(true)
        repo.setProxyRunning(true)

        readerThread = Thread({ readLoop() }, "BarndoorDnsReader").apply { start() }
    }

    private fun readLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun read error", e)
                break
            }
            if (length <= 0) continue

            val packet = buffer.copyOf(length)
            forwardPool.execute {
                try {
                    handlePacket(packet, output)
                } catch (e: Exception) {
                    Log.w(TAG, "drop malformed/unforwardable packet", e)
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 20) return
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return // IPv6 not supported by this proxy

        val ihl = packet[0].toInt() and 0x0F
        val headerLen = ihl * 4
        if (headerLen < 20 || packet.size < headerLen + 8) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        val udpOffset = headerLen
        val srcPort = NetUtil.readShort(packet, udpOffset)
        val dstPort = NetUtil.readShort(packet, udpOffset + 2)
        if (dstPort != 53) return

        val udpLength = NetUtil.readShort(packet, udpOffset + 4)
        val dnsOffset = udpOffset + 8
        val dnsLength = udpLength - 8
        if (dnsLength <= 0 || dnsOffset + dnsLength > packet.size) return

        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val destAddress = NetUtil.bytesToIp(dstIp)

        val socket = DatagramSocket()
        try {
            protect(socket)
            socket.soTimeout = QUERY_TIMEOUT_MS
            socket.connect(InetSocketAddress(destAddress, 53))
            socket.send(DatagramPacket(query, query.size))

            val responseBuf = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(responsePacket)

            val response = responseBuf.copyOf(responsePacket.length)
            val reply = buildReplyPacket(
                fromIp = dstIp, toIp = srcIp,
                fromPort = 53, toPort = srcPort,
                dnsResponse = response
            )
            synchronized(writeLock) {
                output.write(reply)
            }
        } catch (e: Exception) {
            // Timeout or unreachable resolver — silently drop, client will retry.
        } finally {
            socket.close()
        }
    }

    private fun buildReplyPacket(
        fromIp: ByteArray, toIp: ByteArray,
        fromPort: Int, toPort: Int,
        dnsResponse: ByteArray
    ): ByteArray {
        val udpLength = 8 + dnsResponse.size
        val totalLength = 20 + udpLength
        val out = ByteArray(totalLength)

        // --- IPv4 header ---
        out[0] = 0x45 // version 4, IHL 5 (20 bytes, no options)
        out[1] = 0
        NetUtil.writeShort(out, 2, totalLength)
        NetUtil.writeShort(out, 4, idCounter.getAndIncrement() and 0xFFFF) // identification
        out[6] = 0x40 // flags: don't fragment
        out[7] = 0
        out[8] = 64 // TTL
        out[9] = 17 // protocol: UDP
        NetUtil.writeShort(out, 10, 0) // checksum placeholder
        System.arraycopy(fromIp, 0, out, 12, 4)
        System.arraycopy(toIp, 0, out, 16, 4)
        val ipChecksum = NetUtil.checksum(out, 0, 20)
        NetUtil.writeShort(out, 10, ipChecksum)

        // --- UDP header ---
        val udpOffset = 20
        NetUtil.writeShort(out, udpOffset, fromPort)
        NetUtil.writeShort(out, udpOffset + 2, toPort)
        NetUtil.writeShort(out, udpOffset + 4, udpLength)
        NetUtil.writeShort(out, udpOffset + 6, 0) // checksum placeholder
        System.arraycopy(dnsResponse, 0, out, udpOffset + 8, dnsResponse.size)

        val udpChecksum = NetUtil.udpChecksum(fromIp, toIp, out, udpOffset, udpLength)
        NetUtil.writeShort(out, udpOffset + 6, udpChecksum)

        return out
    }

    private fun stopProxy() {
        stopProxyInternal()
        DnsRepository(this).setProxyRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProxyInternal() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing tun", e)
        }
        vpnInterface = null
    }

    override fun onRevoke() {
        stopProxy()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopProxyInternal()
        forwardPool.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val server = DnsRepository(this).getSelectedServer()
        return NotificationCompat.Builder(this, BarndoorApp.CHANNEL_DNS)
            .setContentTitle(getString(R.string.dns_notification_title))
            .setContentText(server?.let { "${it.name} (${it.primary})" } ?: "")
            .setSmallIcon(R.drawable.ic_tile_dns)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val TAG = "BarndoorDnsVpn"
        private const val NOTIFICATION_ID = 42
        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val MTU = 1500
        private const val QUERY_TIMEOUT_MS = 5000
        private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

        const val ACTION_START = "com.barndoor.app.action.START_DNS"
        const val ACTION_STOP = "com.barndoor.app.action.STOP_DNS"

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
