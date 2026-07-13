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
                startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
                startRotation()
            }
        }
        return START_STICKY
    }

    private fun startRotation() {
        if (!prefs.isDeviceRegistered()) {
            Log.e(TAG, "No Mullvad device registered — stopping")
            stopRotation()
            return
        }
        rotationJob?.cancel()
        prefs.running = true
        rotationJob = scope.launch {
            while (true) {
                rotateOnce()
                val intervalMs = prefs.intervalSeconds.coerceIn(10, 1800) * 1000L
                delay(intervalMs)
            }
        }
    }

    private suspend fun rotateOnce() {
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
            wgManager.connect(chosen, prefs)
                .onSuccess {
                    updateNotification("${chosen.cityName}, ${chosen.countryName}")
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to connect to ${chosen.hostname}", e)
                }
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
