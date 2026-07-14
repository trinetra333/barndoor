package com.barndoor.app.rotation

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.barndoor.app.R

class RotationTileService : TileService() {

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
        val prefs = RotationPrefs(this)

        if (!prefs.isReadyToConnect()) {
            // Can't start from the tile alone — registering needs setup (account
            // number for Mullvad, or a tap for WARP) done from inside the app first.
            return
        }

        if (prefs.running) {
            RotationService.stop(this)
        } else {
            RotationService.start(this)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        try {
            val prefs = RotationPrefs(this)
            tile.state = if (prefs.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_rotation_label)
            tile.subtitle = when {
                !prefs.isReadyToConnect() -> getString(R.string.tile_setup_needed)
                prefs.running -> prefs.currentRelayLabel ?: getString(R.string.tile_rotating)
                else -> getString(R.string.tile_off)
            }
        } catch (e: Exception) {
            Log.e(TAG, "tile refresh failed", e)
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_rotation_label)
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "BarndoorRotationTile"
    }
}
