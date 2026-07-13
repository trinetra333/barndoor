package com.barndoor.app.rotation

import android.content.Context

enum class RotationMode { RANDOM_ANY, RANDOM_COUNTRY }

/**
 * Stored in this app's private SharedPreferences (standard Android app-sandboxed
 * storage — not additionally encrypted at rest). If you plan to publish Barndoor,
 * consider migrating this to androidx.security.crypto's EncryptedSharedPreferences.
 */
class RotationPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var accountNumber: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var privateKeyBase64: String?
        get() = prefs.getString(KEY_PRIVATE_KEY, null)
        set(value) = prefs.edit().putString(KEY_PRIVATE_KEY, value).apply()

    var publicKeyBase64: String?
        get() = prefs.getString(KEY_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_PUBLIC_KEY, value).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var tunnelIpv4: String?
        get() = prefs.getString(KEY_TUNNEL_IPV4, null)
        set(value) = prefs.edit().putString(KEY_TUNNEL_IPV4, value).apply()

    var tunnelIpv6: String?
        get() = prefs.getString(KEY_TUNNEL_IPV6, null)
        set(value) = prefs.edit().putString(KEY_TUNNEL_IPV6, value).apply()

    var mode: RotationMode
        get() = RotationMode.valueOf(prefs.getString(KEY_MODE, RotationMode.RANDOM_ANY.name)!!)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var selectedCountryCode: String?
        get() = prefs.getString(KEY_COUNTRY, null)
        set(value) = prefs.edit().putString(KEY_COUNTRY, value).apply()

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 120)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(10, 1800)).apply()

    var running: Boolean
        get() = prefs.getBoolean(KEY_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_RUNNING, value).apply()

    var currentRelayLabel: String?
        get() = prefs.getString(KEY_CURRENT_RELAY, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_RELAY, value).apply()

    fun isDeviceRegistered(): Boolean =
        !privateKeyBase64.isNullOrBlank() && !tunnelIpv4.isNullOrBlank()

    fun clearDeviceRegistration() {
        prefs.edit()
            .remove(KEY_PRIVATE_KEY).remove(KEY_PUBLIC_KEY).remove(KEY_DEVICE_ID)
            .remove(KEY_TUNNEL_IPV4).remove(KEY_TUNNEL_IPV6)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "barndoor_rotation_prefs"
        private const val KEY_ACCOUNT = "account_number"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TUNNEL_IPV4 = "tunnel_ipv4"
        private const val KEY_TUNNEL_IPV6 = "tunnel_ipv6"
        private const val KEY_MODE = "mode"
        private const val KEY_COUNTRY = "country"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_RUNNING = "running"
        private const val KEY_CURRENT_RELAY = "current_relay"
    }
}
