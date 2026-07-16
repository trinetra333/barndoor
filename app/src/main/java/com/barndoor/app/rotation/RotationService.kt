package com.barndoor.app.rotation

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.barndoor.app.BarndoorApp
import com.barndoor.app.MainActivity
import com.barndoor.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RotationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rotationJob: Job? = null
    private lateinit var prefs: RotationPrefs
    private lateinit var wgManager: WireGuardManager
    private var relayCache: List<Relay> = emptyList()

    override fun onCreate() {
        super.onCreate()
        prefs = RotationPrefs(this)
        wgManager = WireGuardManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRotation()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                startRotation()
            }
        }
        return START_STICKY
    }

    private fun startRotation() {
        if (!prefs.isReadyToConnect()) {
            Log.e(TAG, "${prefs.provider} not registered — stopping")
            stopRotation()
            return
        }
        rotationJob?.cancel()
        prefs.running = true
        rotationJob = scope.launch {
            when (prefs.provider) {
                VpnProvider.MULLVAD -> mullvadRotationLoop()
                VpnProvider.WARP -> warpConnectionLoop()
            }
        }
    }

    /** Reconnects to a new random Mullvad relay every [RotationPrefs.intervalSeconds]. */
    private suspend fun mullvadRotationLoop() {
        while (true) {
            rotateMullvadOnce()
            val intervalMs = prefs.intervalSeconds.coerceIn(10, 1800) * 1000L
            delay(intervalMs)
        }
    }

    /**
     * WARP has exactly one relay pool (nearest Cloudflare edge) — there's no country or
     * server list to rotate through the way Mullvad has. What this loop *can* do is
     * reconnect on the same timer as Mullvad: each fresh connection re-enters
     * Cloudflare's anycast routing, which can (not guaranteed) land on a different edge
     * IP than before. It's a much weaker form of "rotation" than picking a country, and
     * is honestly presented as that in the UI — but it's the only lever WARP has.
     */
    private suspend fun warpConnectionLoop() {
        while (true) {
            connectWarp()
            val intervalMs = prefs.intervalSeconds.coerceIn(10, 1800) * 1000L
            delay(intervalMs)
        }
    }

    private suspend fun connectWarp() {
        val privateKey = prefs.warpPrivateKeyBase64
        val address = prefs.warpTunnelIpv4
        val peerKey = prefs.warpPeerPublicKey
        val peerEndpoint = prefs.warpPeerEndpoint
        if (privateKey == null || address == null || peerKey == null || peerEndpoint == null) {
            Log.e(TAG, "WARP registration incomplete — stopping")
            stopRotation()
            return
        }
        val config = WgPeerConfig(
            privateKey = privateKey,
            address = address,
            dns = "1.1.1.1",
            peerPublicKey = peerKey,
            peerEndpoint = peerEndpoint
        )
        wgManager.connect(config)
            .onSuccess {
                prefs.currentRelayLabel = "Cloudflare WARP"
                updateNotification("Cloudflare WARP \u2022 connected")
            }
            .onFailure { e -> Log.e(TAG, "WARP connect failed", e) }
    }

    private suspend fun rotateMullvadOnce() {
        try {
            if (relayCache.isEmpty()) {
                relayCache = MullvadApi.fetchRelays()
            }
            val active = relayCache.filter { it.active }
            val candidates = when (prefs.mode) {
                RotationMode.RANDOM_ANY -> active
                RotationMode.RANDOM_COUNTRY -> active.filter {
                    it.countryCode.equals(prefs.selectedCountryCode, ignoreCase = true)
                }.ifEmpty { active }
            }
            if (candidates.isEmpty()) {
                Log.w(TAG, "No relays available to rotate to")
                return
            }
            val chosen = candidates.random()
            val config = WgPeerConfig(
                privateKey = prefs.mullvadPrivateKeyBase64 ?: return,
                address = prefs.mullvadTunnelIpv4 ?: return,
                dns = "10.64.0.1", // Mullvad's in-tunnel resolver
                peerPublicKey = chosen.publicKey,
                peerEndpoint = "${chosen.ipv4AddrIn}:${MullvadApi.WIREGUARD_PORT}"
            )
            wgManager.connect(config)
                .onSuccess {
                    prefs.currentRelayLabel = "${chosen.cityName}, ${chosen.countryName}"
                    updateNotification("${chosen.cityName}, ${chosen.countryName}")
                }
                .onFailure { e -> Log.e(TAG, "Failed to connect to ${chosen.hostname}", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Rotation cycle failed", e)
            // Refresh relay list next time in case it's stale/broken.
            relayCache = emptyList()
        }
    }

    private fun stopRotation() {
        rotationJob?.cancel()
        rotationJob = null
        prefs.running = false
        scope.launch { wgManager.disconnect() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        rotationJob?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, BarndoorApp.CHANNEL_ROTATION)
            .setContentTitle(getString(R.string.rotation_notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_tile_rotation)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val TAG = "BarndoorRotation"
        private const val NOTIFICATION_ID = 43

        const val ACTION_START = "com.barndoor.app.action.START_ROTATION"
        const val ACTION_STOP = "com.barndoor.app.action.STOP_ROTATION"

        fun start(context: Context) {
            val intent = Intent(context, RotationService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RotationService::class.java).setAction(ACTION_STOP)
            // See DnsVpnService.stop() for why this must be startService, not
            // startForegroundService.
            context.startService(intent)
        }
    }
}
