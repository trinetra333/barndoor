package com.barndoor.app.dns

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Each tap cycles to the next server in [DnsRepository.getTileServers] (the checked
 * subset, not necessarily the whole list) and activates it — using Android's native
 * Private DNS if it's usable for that server (no VPN icon), or the VPN proxy
 * otherwise. Tapping past the last entry turns DNS off; tapping again from off starts
 * back at the first entry. The tile's label shows the active resolver's name, not a
 * static "Barndoor DNS".
 */
class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = DnsRepository(this)
        val cycle = repo.getTileServers()
        if (cycle.isEmpty()) {
            refreshTile()
            return
        }

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

        qsTile?.let { it.state = Tile.STATE_UNAVAILABLE; it.updateTile() }
        refreshTile(delayed = true)
    }

    private fun activate(repo: DnsRepository, server: DnsServer) {
        val fullList = repo.getServers()
        val idx = fullList.indexOfFirst { it.id == server.id }
        if (idx >= 0) repo.setSelectedIndex(idx)

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

    private fun refreshTile(delayed: Boolean = false) {
        val tile = qsTile ?: return
        val apply = {
            val repo = DnsRepository(this)
            val running = repo.isProxyRunning()
            val server = repo.getSelectedServer()
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (running && server != null) server.name else "Barndoor DNS"
            tile.subtitle = when {
                !running -> "Off"
                repo.activeMode == DnsMode.SYSTEM -> "System DNS"
                else -> "VPN proxy"
            }
            tile.updateTile()
        }
        if (delayed) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(apply, 350)
        } else {
            apply()
        }
    }
}
