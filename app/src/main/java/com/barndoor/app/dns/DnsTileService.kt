package com.barndoor.app.dns

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.barndoor.app.R

/**
 * Each tap cycles to the next DNS server in the saved list and (re)starts the proxy
 * with it. Tapping past the last entry turns the proxy off; tapping again from off
 * starts back at the first entry.
 */
class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = DnsRepository(this)
        val servers = repo.getServers()
        if (servers.isEmpty()) {
            refreshTile()
            return
        }

        if (!repo.isProxyRunning()) {
            DnsVpnService.start(this)
        } else {
            val next = repo.getSelectedIndex() + 1
            if (next >= servers.size) {
                DnsVpnService.stop(this)
            } else {
                repo.setSelectedIndex(next)
                DnsVpnService.start(this)
            }
        }
        // Give the service a moment to update shared prefs before we reflect state.
        qsTile?.let { it.state = Tile.STATE_UNAVAILABLE; it.updateTile() }
        refreshTile(delayed = true)
    }

    private fun refreshTile(delayed: Boolean = false) {
        val tile = qsTile ?: return
        val apply = {
            val repo = DnsRepository(this)
            val running = repo.isProxyRunning()
            val server = repo.getSelectedServer()
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_dns_label)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = if (running && server != null) server.name else getString(R.string.tile_off)
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
