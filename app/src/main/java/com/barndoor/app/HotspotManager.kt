package com.barndoor.app

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Wraps hotspot control.
 *
 * IMPORTANT: Since Android 8 (API 26), third-party apps cannot silently turn
 * on Wi-Fi tethering in the background - Google restricted this for security
 * reasons. Apps are only allowed to:
 *   1) Deep-link the user into the system "Wi-Fi hotspot" settings screen, or
 *   2) Use LocalOnlyHotspot, a temporary hotspot the OS fully controls and
 *      which the requesting app cannot hide from the user (a system
 *      notification is always shown while it's active).
 *
 * This class intentionally only uses those two supported paths. There is no
 * "hidden hotspot" mode - that would require a rooted device and would defeat
 * the purpose of a legitimate, Play-Store-distributable app.
 */
class HotspotManager(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Opens the system hotspot settings screen so the user can toggle it themselves. */
    fun openSystemHotspotSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.ACTION_WIFI_TETHER_SETTING)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Starts a LocalOnlyHotspot. Requires ACCESS_FINE_LOCATION to be granted.
     * The OS will show its own persistent notification while this is active -
     * that notification cannot be suppressed by this app, by design.
     */
    fun startLocalOnlyHotspot(
        onStarted: (ssid: String?, password: String?) -> Unit,
        onFailed: (reason: String) -> Unit
    ) {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    localOnlyHotspotReservation = reservation
                    val config = reservation.wifiConfiguration
                    onStarted(config?.SSID, config?.preSharedKey)
                }

                override fun onStopped() {
                    localOnlyHotspotReservation = null
                }

                override fun onFailed(reason: Int) {
                    onFailed("LocalOnlyHotspot failed with code $reason")
                }
            }, null)
        } catch (e: SecurityException) {
            onFailed("Missing location permission: ${e.message}")
        }
    }

    fun stopLocalOnlyHotspot() {
        localOnlyHotspotReservation?.close()
        localOnlyHotspotReservation = null
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    companion object {
        private const val TAG = "HotspotManager"
    }
}
