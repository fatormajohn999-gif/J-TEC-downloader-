package com.omnidrop.app.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile for toggling the OmniDrop Floating Edge Tab.
 * Matches manifest registration: com.omnidrop.app.plugin.QuickTileService
 */
class QuickTileService : TileService() {

    companion object {
        const val PREFS_NAME = "omnidrop_native_prefs"
        const val PREF_BUBBLE_ENABLED = "floating_bubble_enabled"

        fun updateTile(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    requestListeningState(context, ComponentName(context, QuickTileService::class.java))
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isTabActive = (FloatingBubbleService.instance != null) && prefs.getBoolean(PREF_BUBBLE_ENABLED, false)
        tile.label = "OmniDrop Tab"
        tile.icon = Icon.createWithResource(this, android.R.drawable.stat_sys_download)
        tile.state = if (isTabActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        // 1. Check if overlay permission is granted
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (!hasOverlay) {
            // Open the app or system permission page so the user can grant overlay permission
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (launchIntent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launchIntent)
                }
            }
            return
        }

        // 2. Toggle Tab state via FloatingBubbleService.syncService
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isTabActive = (FloatingBubbleService.instance != null) && prefs.getBoolean(PREF_BUBBLE_ENABLED, false)
        val newState = !isTabActive

        prefs.edit().apply {
            this.putBoolean(PREF_BUBBLE_ENABLED, newState)
            this.apply()
        }

        FloatingBubbleService.syncService(this)

        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
