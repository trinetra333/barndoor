package com.barndoor.app.rotation

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper around the WireGuard tunnel library (GoBackend). Keeps a single,
 * reused [Tunnel] instance and re-applies a new [Config] to it every time we want
 * to rotate to a different exit relay — the interface identity (private key +
 * assigned Mullvad tunnel address) stays constant, only the Peer changes.
 */
class WireGuardManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend: GoBackend by lazy { GoBackend(appContext) }
    private val tunnel: Tunnel by lazy {
        object : Tunnel {
            override fun getName(): String = TUNNEL_NAME
            override fun onStateChange(newState: Tunnel.State) { /* no-op, state is polled */ }
        }
    }

    /** Generates and persists a fresh WireGuard key pair if one isn't already stored. */
    fun ensureKeyPair(prefs: RotationPrefs): Pair<String, String> {
        val existingPriv = prefs.privateKeyBase64
        val existingPub = prefs.publicKeyBase64
        if (!existingPriv.isNullOrBlank() && !existingPub.isNullOrBlank()) {
            return existingPriv to existingPub
        }
        val keyPair = KeyPair()
        val priv = keyPair.privateKey.toBase64()
        val pub = keyPair.publicKey.toBase64()
        prefs.privateKeyBase64 = priv
        prefs.publicKeyBase64 = pub
        return priv to pub
    }

    /** Returns a consent Intent if the user hasn't granted VPN permission yet, else null. */
    fun permissionIntent(activity: Activity): Intent? = GoBackend.VpnService.prepare(activity)

    suspend fun connect(relay: Relay, prefs: RotationPrefs): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val privateKey = prefs.privateKeyBase64
                ?: throw IllegalStateException("No WireGuard key pair generated yet")
            val address = prefs.tunnelIpv4
                ?: throw IllegalStateException("No Mullvad device registered yet")

            // Validate the stored key parses correctly before building the config.
            Key.fromBase64(privateKey)

            val configText = buildString {
                append("[Interface]\n")
                append("PrivateKey = $privateKey\n")
                append("Address = ${addressCidr(address)}\n")
                append("DNS = 10.64.0.1\n") // Mullvad's in-tunnel resolver
                append("\n[Peer]\n")
                append("PublicKey = ${relay.publicKey}\n")
                append("AllowedIPs = 0.0.0.0/0, ::/0\n")
                append("Endpoint = ${relay.ipv4AddrIn}:${MullvadApi.WIREGUARD_PORT}\n")
                append("PersistentKeepalive = 25\n")
            }

            val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
            backend.setState(tunnel, Tunnel.State.UP, config)
            prefs.currentRelayLabel = "${relay.cityName}, ${relay.countryName} (${relay.hostname})"
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }
    }

    fun isRunning(): Boolean = backend.runningTunnelNames.contains(TUNNEL_NAME)

    private fun addressCidr(address: String): String =
        if (address.contains("/")) address else "$address/32"

    companion object {
        private const val TUNNEL_NAME = "barndoor0"
    }
}
