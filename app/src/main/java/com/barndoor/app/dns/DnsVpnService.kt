package com.barndoor.app.dns

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
import java.util.concurrent.atomic.AtomicInteger

/** Result of resolving which app owns a query and which DNS server it should use. */
data class ResolvedTarget(val server: DnsServer, val packageName: String?)

/**
 * A local, no-root VPN that intercepts *only* DNS traffic (UDP and TCP, port 53) and
 * relays it to whichever resolver applies. Every app in the tunnel sends its DNS
 * queries to this service's own fake address ([TUNNEL_DNS_ADDRESS]); for each query we
 * look up which app sent it (by UID) and forward it to that app's assigned resolver if
 * it has one, or the device-wide default otherwise — so different apps can genuinely
 * use different DNS servers at the same time. Non-DNS traffic is never routed here at
 * all (only the fake DNS address is routed into the tunnel), and anything that reaches
 * this address on a port other than 53 gets an immediate RST/drop instead of hanging.
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val forwardPool = Executors.newCachedThreadPool()
    private var readerThread: Thread? = null
    private var staleSweepThread: Thread? = null
    private var tcpRelay: TcpDnsRelay? = null
    private var dotPool: DotClientPool? = null
    private val writeLock = Any()
    private val ipIdCounter = AtomicInteger(0)

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
            stopProxyInternal()
        }

        val builder = Builder()
            .setSession("Barndoor DNS")
            .addAddress(TUNNEL_CLIENT_ADDRESS, 24)
            .addDnsServer(TUNNEL_DNS_ADDRESS)
            .addRoute(TUNNEL_DNS_ADDRESS, 32)
            .setMtu(MTU)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "could not exclude self from tunnel", e)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        running.set(true)
        DnsRepository(this).setProxyRunning(true)

        readerThread = Thread({ readLoop() }, "BarndoorDnsReader").apply { start() }
        staleSweepThread = Thread({ staleSweepLoop() }, "BarndoorTcpSweep").apply { start() }
    }

    private fun staleSweepLoop() {
        while (running.get()) {
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                break
            }
            tcpRelay?.sweepStale()
        }
    }

    private fun readLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val pool = DotClientPool { socket -> protect(socket) }
        dotPool = pool
        tcpRelay = TcpDnsRelay(
            output = output,
            writeLock = writeLock,
            protectSocket = { socket -> protect(socket) },
            resolveTarget = { srcPort -> targetForQuery(6, srcPort) },
            appLabelFor = { pkg -> appLabelFor(pkg) },
            loggingEnabled = { LogPrefs(this).enabled },
            dotPool = pool
        )
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
        when (protocol) {
            6 -> tcpRelay?.handle(packet, headerLen)
            17 -> handleUdpPacket(packet, headerLen, output)
            else -> return // nothing else was ever routed into this tunnel anyway
        }
    }

    /**
     * Looks up which app owns the connection this query arrived on, and returns that
     * app's assigned resolver — or the device-wide default if it has none / the lookup
     * fails for any reason. [protocol] is 6 (TCP) or 17 (UDP).
     */
    private fun targetForQuery(protocol: Int, srcPort: Int): ResolvedTarget {
        val repo = DnsRepository(this)
        val default = repo.getSelectedServer() ?: DnsServer.PRESETS.first()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm?.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(TUNNEL_CLIENT_ADDRESS, srcPort),
                InetSocketAddress(TUNNEL_DNS_ADDRESS, 53)
            ) ?: -1
            if (uid <= 0) return ResolvedTarget(default, null)
            val packages = packageManager.getPackagesForUid(uid) ?: return ResolvedTarget(default, null)
            val assignments = repo.getAppAssignments()
            for (pkg in packages) {
                val assignedId = assignments[pkg] ?: continue
                repo.getServerById(assignedId)?.let { return ResolvedTarget(it, packages.firstOrNull()) }
            }
            return ResolvedTarget(default, packages.firstOrNull())
        } catch (e: Exception) {
            Log.w(TAG, "per-app DNS lookup failed, using device-wide default", e)
            return ResolvedTarget(default, null)
        }
    }

    private fun appLabelFor(packageName: String?): String {
        if (packageName == null) return "Unknown app"
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun handleUdpPacket(packet: ByteArray, headerLen: Int, output: FileOutputStream) {
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
        val target = targetForQuery(17, srcPort)
        val server = target.server
        val logging = LogPrefs(this).enabled
        val startTime = System.currentTimeMillis()

        try {
            val response = if (server.dotHostname != null) {
                dotPool?.get(server.dotHostname)?.query(query, QUERY_TIMEOUT_MS)
            } else {
                queryPlainUdp(server, query)
            }
            if (response == null) {
                if (logging) logQuery(query, server, target.packageName, startTime, false)
                return
            }
            if (logging) logQuery(query, server, target.packageName, startTime, true)

            val reply = buildReplyPacket(
                fromIp = dstIp, toIp = srcIp,
                fromPort = 53, toPort = srcPort,
                dnsResponse = response
            )
            synchronized(writeLock) {
                output.write(reply)
            }
        } catch (e: Exception) {
            if (logging) logQuery(query, server, target.packageName, startTime, false)
            // Timeout or unreachable resolver — silently drop, client will retry.
        }
    }

    private fun logQuery(query: ByteArray, server: DnsServer, packageName: String?, startTime: Long, success: Boolean) {
        val domain = DnsMessageUtil.readQuestionName(query) ?: "(unparsed)"
        QueryLogStore.add(
            QueryLog(
                timestamp = startTime,
                domain = domain,
                resolverName = server.name,
                appLabel = appLabelFor(packageName),
                protocol = if (server.dotHostname != null) "DoT (UDP)" else "UDP",
                durationMs = System.currentTimeMillis() - startTime,
                success = success
            )
        )
    }

    private fun queryPlainUdp(server: DnsServer, query: ByteArray): ByteArray? {
        val destAddress = server.primary ?: return null
        val socket = DatagramSocket()
        try {
            protect(socket)
            socket.soTimeout = QUERY_TIMEOUT_MS
            socket.connect(InetSocketAddress(destAddress, 53))
            socket.send(DatagramPacket(query, query.size))

            val responseBuf = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(responsePacket)
            return responseBuf.copyOf(responsePacket.length)
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
        NetUtil.writeShort(out, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
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
        staleSweepThread?.interrupt()
        staleSweepThread = null
        tcpRelay?.shutdown()
        tcpRelay = null
        dotPool?.shutdown()
        dotPool = null
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
            .setContentText(server?.name ?: "")
            .setSmallIcon(R.drawable.ic_tile_dns)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val TAG = "BarndoorDnsVpn"
        private const val NOTIFICATION_ID = 42

        /** The tunnel's own client-side address; every app in the tunnel shares this as its source IP. */
        private const val TUNNEL_CLIENT_ADDRESS = "10.111.222.1"

        /** Our fake "DNS server" address, advertised to the OS so all DNS queries arrive here. */
        private const val TUNNEL_DNS_ADDRESS = "10.111.222.2"

        private const val MTU = 1500
        private const val QUERY_TIMEOUT_MS = 5000

        const val ACTION_START = "com.barndoor.app.action.START_DNS"
        const val ACTION_STOP = "com.barndoor.app.action.STOP_DNS"

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)
            // Plain startService, not startForegroundService: the service is already
            // running, and startForegroundService() here would require startForeground()
            // to be called again in response, which the ACTION_STOP path never does —
            // that mismatch is what was crashing the app after one stop/cycle.
            context.startService(intent)
        }

        /**
         * Whether *any* VPN is actually active right now, checked directly against
         * ConnectivityManager rather than trusting our own cached "running" flag — a
         * fast, local, synchronous check (no network, no binder round-trip to the
         * service itself). Used to notice if the tunnel died/was revoked outside the
         * app instead of showing a stale "running" status forever.
         *
         * Deliberately checks every network, not just getActiveNetwork(): this
         * tunnel is narrow-scoped (it only routes traffic to its own fake DNS
         * address, never a general 0.0.0.0/0 default route, by design — see
         * startProxy()), so it never becomes the device's "default"/active network
         * even while it's genuinely up and working. Checking only the active network
         * would report "not running" 100% of the time regardless of real state,
         * which is exactly the bug that was making the tile/app flip to "Off" on
         * every refresh outside the startup grace period.
         */
        fun isActuallyRunning(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
                cm.allNetworks.any { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
