package com.barndoor.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
        // "android.settings.WIFI_TETHER_SETTINGS" has no public SDK constant
        // (Settings.ACTION_WIFI_TETHER_SETTING is a hidden/internal API and
        // won't compile against the public compileSdk), so it's referenced
        // directly by its stable action string instead. This is the standard
        // documented workaround other apps use to deep-link into this screen.
        val tetherIntent = Intent("android.settings.WIFI_TETHER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tetherIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(tetherIntent)
        } else {
            // Fallback for OEMs/Android versions that don't expose that screen.
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Starts a LocalOnlyHotspot.
     *
     * Requires ACCESS_FINE_LOCATION on all supported versions, and
     * additionally NEARBY_WIFI_DEVICES on API 33+. Both permissions are
     * requested and checked by the caller (see
     * MainActivity.requestRuntimePermissions / startHotspotFlow) before this
     * method is ever invoked. The suppression below is safe because lint
     * can't see across that call boundary, not because the check is skipped.
     */
    @SuppressLint("MissingPermission")
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
            onFailed("Missing required permission: ${e.message}")
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

