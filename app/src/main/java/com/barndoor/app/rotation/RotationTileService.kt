package com.barndoor.app.rotation

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.barndoor.app.R

class RotationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = RotationPrefs(this)

        if (!prefs.isDeviceRegistered()) {
            // Can't start from the tile alone — registering needs the account number
            // and a one-time VPN consent dialog, both handled in the app.
            refreshTile()
            return
        }

        if (prefs.running) {
            RotationService.stop(this)
        } else {
            RotationService.start(this)
        }
        qsTile?.let { it.state = Tile.STATE_UNAVAILABLE; it.updateTile() }
        refreshTile(delayed = true)
    }

    private fun refreshTile(delayed: Boolean = false) {
        val tile = qsTile ?: return
        val apply = {
            val prefs = RotationPrefs(this)
            tile.state = if (prefs.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_rotation_label)
            tile.subtitle = when {
                !prefs.isDeviceRegistered() -> getString(R.string.tile_setup_needed)
                prefs.running -> prefs.currentRelayLabel ?: getString(R.string.tile_rotating)
                else -> getString(R.string.tile_off)
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
