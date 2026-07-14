package com.barndoor.app.rotation

import android.content.Context

enum class RotationMode { RANDOM_ANY, RANDOM_COUNTRY }
enum class VpnProvider { MULLVAD, WARP }

/**
 * Stored in this app's private SharedPreferences (standard Android app-sandboxed
 * storage — not additionally encrypted at rest). If you plan to publish Barndoor,
 * consider migrating this to androidx.security.crypto's EncryptedSharedPreferences.
 *
 * Mullvad and WARP are independent identities (different keys, different tunnel
 * addresses) — switching [provider] doesn't affect the other one's registration.
 */
class RotationPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var provider: VpnProvider
        get() = VpnProvider.valueOf(prefs.getString(KEY_PROVIDER, VpnProvider.MULLVAD.name)!!)
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    // --- Mullvad ---

    var accountNumber: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var mullvadPrivateKeyBase64: String?
        get() = prefs.getString(KEY_MULLVAD_PRIVATE_KEY, null)
        set(value) = prefs.edit().putString(KEY_MULLVAD_PRIVATE_KEY, value).apply()

    var mullvadPublicKeyBase64: String?
        get() = prefs.getString(KEY_MULLVAD_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_MULLVAD_PUBLIC_KEY, value).apply()

    var mullvadDeviceId: String?
        get() = prefs.getString(KEY_MULLVAD_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_MULLVAD_DEVICE_ID, value).apply()

    var mullvadTunnelIpv4: String?
        get() = prefs.getString(KEY_MULLVAD_TUNNEL_IPV4, null)
        set(value) = prefs.edit().putString(KEY_MULLVAD_TUNNEL_IPV4, value).apply()

    var mullvadTunnelIpv6: String?
        get() = prefs.getString(KEY_MULLVAD_TUNNEL_IPV6, null)
        set(value) = prefs.edit().putString(KEY_MULLVAD_TUNNEL_IPV6, value).apply()

    fun isMullvadRegistered(): Boolean =
        !mullvadPrivateKeyBase64.isNullOrBlank() && !mullvadTunnelIpv4.isNullOrBlank()

    var mode: RotationMode
        get() = RotationMode.valueOf(prefs.getString(KEY_MODE, RotationMode.RANDOM_ANY.name)!!)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var selectedCountryCode: String?
        get() = prefs.getString(KEY_COUNTRY, null)
        set(value) = prefs.edit().putString(KEY_COUNTRY, value).apply()

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 120)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(10, 1800)).apply()

    // --- Cloudflare WARP ---

    var warpPrivateKeyBase64: String?
        get() = prefs.getString(KEY_WARP_PRIVATE_KEY, null)
        set(value) = prefs.edit().putString(KEY_WARP_PRIVATE_KEY, value).apply()

    var warpPublicKeyBase64: String?
        get() = prefs.getString(KEY_WARP_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_WARP_PUBLIC_KEY, value).apply()

    var warpDeviceId: String?
        get() = prefs.getString(KEY_WARP_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_WARP_DEVICE_ID, value).apply()

    var warpTunnelIpv4: String?
        get() = prefs.getString(KEY_WARP_TUNNEL_IPV4, null)
        set(value) = prefs.edit().putString(KEY_WARP_TUNNEL_IPV4, value).apply()

    var warpTunnelIpv6: String?
        get() = prefs.getString(KEY_WARP_TUNNEL_IPV6, null)
        set(value) = prefs.edit().putString(KEY_WARP_TUNNEL_IPV6, value).apply()

    var warpPeerPublicKey: String?
        get() = prefs.getString(KEY_WARP_PEER_KEY, null)
        set(value) = prefs.edit().putString(KEY_WARP_PEER_KEY, value).apply()

    var warpPeerEndpoint: String?
        get() = prefs.getString(KEY_WARP_PEER_ENDPOINT, null)
        set(value) = prefs.edit().putString(KEY_WARP_PEER_ENDPOINT, value).apply()

    fun isWarpRegistered(): Boolean =
        !warpPrivateKeyBase64.isNullOrBlank() && !warpTunnelIpv4.isNullOrBlank()

    // --- Shared ---

    var running: Boolean
        get() = prefs.getBoolean(KEY_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_RUNNING, value).apply()

    var currentRelayLabel: String?
        get() = prefs.getString(KEY_CURRENT_RELAY, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_RELAY, value).apply()

    /** Whether the *currently selected* provider is ready to connect. */
    fun isReadyToConnect(): Boolean = when (provider) {
        VpnProvider.MULLVAD -> isMullvadRegistered()
        VpnProvider.WARP -> isWarpRegistered()
    }

    companion object {
        private const val PREFS_NAME = "barndoor_rotation_prefs"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ACCOUNT = "account_number"
        private const val KEY_MULLVAD_PRIVATE_KEY = "mullvad_private_key"
        private const val KEY_MULLVAD_PUBLIC_KEY = "mullvad_public_key"
        private const val KEY_MULLVAD_DEVICE_ID = "mullvad_device_id"
        private const val KEY_MULLVAD_TUNNEL_IPV4 = "mullvad_tunnel_ipv4"
        private const val KEY_MULLVAD_TUNNEL_IPV6 = "mullvad_tunnel_ipv6"
        private const val KEY_MODE = "mode"
        private const val KEY_COUNTRY = "country"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_WARP_PRIVATE_KEY = "warp_private_key"
        private const val KEY_WARP_PUBLIC_KEY = "warp_public_key"
        private const val KEY_WARP_DEVICE_ID = "warp_device_id"
        private const val KEY_WARP_TUNNEL_IPV4 = "warp_tunnel_ipv4"
        private const val KEY_WARP_TUNNEL_IPV6 = "warp_tunnel_ipv6"
        private const val KEY_WARP_PEER_KEY = "warp_peer_key"
        private const val KEY_WARP_PEER_ENDPOINT = "warp_peer_endpoint"
        private const val KEY_RUNNING = "running"
        private const val KEY_CURRENT_RELAY = "current_relay"
    }
}
