package com.barndoor.app.dns

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Each tap cycles to the next server in [DnsRepository.getTileServers] (the checked
 * subset, not necessarily the whole list) and activates it — using Android's native
 * Private DNS if it's usable for that server (no VPN icon), or the VPN proxy
 * otherwise. Tapping past the last entry turns DNS off; tapping again from off starts
 * back at the first entry. The tile's label shows the active resolver's name, not a
 * static "Barndoor DNS".
 *
 * Everything here runs synchronously and is wrapped defensively: an uncaught
 * exception here previously could leave the tile showing a stale "unavailable" state
 * until the next onStartListening(), which looked like the tile "stopped responding"
 * after one tap.
 */
class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            handleClick()
        } catch (e: Exception) {
            Log.e(TAG, "tile click failed", e)
        }
        refreshTile()
    }

    private fun handleClick() {
        val repo = DnsRepository(this)
        repo.syncWithReality(this)
        val cycle = repo.getTileServers()
        if (cycle.isEmpty()) return

        if (!repo.isProxyRunning()) {
            activate(repo, cycle.first())
        } else {
            val currentId = repo.getSelectedServer()?.id
            val currentPos = cycle.indexOfFirst { it.id == currentId }
            val nextPos = currentPos + 1
            if (nextPos >= cycle.size) {
                deactivate(repo)
            } else {
                activate(repo, cycle[nextPos])
            }
        }
    }

    private fun activate(repo: DnsRepository, server: DnsServer) {
        repo.setSelectedServerId(server.id)

        if (repo.isProxyRunning()) {
            when (repo.activeMode) {
                DnsMode.SYSTEM -> SystemDnsManager.clear(this)
                DnsMode.VPN -> DnsVpnService.stop(this)
            }
        }

        val useSystem = repo.getAppAssignments().isEmpty() &&
            SystemDnsManager.canUseSystemMode(this, server)

        if (useSystem) {
            val applied = SystemDnsManager.apply(this, server.dotHostname!!)
            repo.activeMode = DnsMode.SYSTEM
            repo.setProxyRunning(applied)
        } else {
            if (SystemDnsManager.hasPermission(this)) SystemDnsManager.clear(this)
            repo.activeMode = DnsMode.VPN
            // Relies on VPN consent already granted once from inside the app; if it
            // never was, the tunnel silently fails to establish and this is a no-op.
            DnsVpnService.start(this)
            repo.setProxyRunning(true)
        }
    }

    private fun deactivate(repo: DnsRepository) {
        when (repo.activeMode) {
            DnsMode.SYSTEM -> SystemDnsManager.clear(this)
            DnsMode.VPN -> DnsVpnService.stop(this)
        }
        repo.setProxyRunning(false)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        try {
            val repo = DnsRepository(this)
            repo.syncWithReality(this)
            val running = repo.isProxyRunning()
            val server = repo.getSelectedServer()
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (running && server != null) server.name else "Barndoor DNS"
            tile.subtitle = when {
                !running -> "Off"
                repo.activeMode == DnsMode.SYSTEM -> "System DNS"
                else -> "VPN proxy"
            }
        } catch (e: Exception) {
            Log.e(TAG, "tile refresh failed", e)
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Barndoor DNS"
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "BarndoorDnsTile"
    }
}
