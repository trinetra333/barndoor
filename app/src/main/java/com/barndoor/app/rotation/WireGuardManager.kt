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

/** Everything needed to bring up one WireGuard tunnel, regardless of which provider it came from. */
data class WgPeerConfig(
    val privateKey: String,
    val address: String,   // our tunnel-assigned address; "/32" appended automatically if missing
    val dns: String,
    val peerPublicKey: String,
    val peerEndpoint: String // "host:port"
)

/**
 * Thin wrapper around the WireGuard tunnel library (GoBackend). Keeps a single,
 * reused [Tunnel] instance and re-applies a new [Config] to it every time we want to
 * connect somewhere else — the interface identity (private key + assigned address)
 * stays constant per-provider, only the Peer changes. Provider-agnostic: Mullvad
 * rotation and Cloudflare WARP both go through the same [connect].
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

    /** Generates a fresh WireGuard key pair — callers decide where to persist it. */
    fun generateKeyPair(): Pair<String, String> {
        val keyPair = KeyPair()
        return keyPair.privateKey.toBase64() to keyPair.publicKey.toBase64()
    }

    /** Returns a consent Intent if the user hasn't granted VPN permission yet, else null. */
    fun permissionIntent(activity: Activity): Intent? = GoBackend.VpnService.prepare(activity)

    suspend fun connect(config: WgPeerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Validate the key parses correctly before building the config.
            Key.fromBase64(config.privateKey)

            val configText = buildString {
                append("[Interface]\n")
                append("PrivateKey = ${config.privateKey}\n")
                append("Address = ${addressCidr(config.address)}\n")
                append("DNS = ${config.dns}\n")
                append("\n[Peer]\n")
                append("PublicKey = ${config.peerPublicKey}\n")
                append("AllowedIPs = 0.0.0.0/0, ::/0\n")
                append("Endpoint = ${config.peerEndpoint}\n")
                append("PersistentKeepalive = 25\n")
            }

            val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
            backend.setState(tunnel, Tunnel.State.UP, parsed)
            Unit
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            Unit
        }
    }

    fun isRunning(): Boolean = backend.runningTunnelNames.contains(TUNNEL_NAME)

    private fun addressCidr(address: String): String =
        if (address.contains("/")) address else "$address/32"

    companion object {
        private const val TUNNEL_NAME = "barndoor0"
    }
}
