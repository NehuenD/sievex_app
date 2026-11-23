package com.sievex.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android Quick Settings tile for zero-friction voice capture from anywhere on the phone.
 *
 * When idle, collapses the shade into [MainActivity] with [MainActivity.EXTRA_START_BURST] so
 * Android 14/15 grants while-in-use microphone foreground service permission reliably; when
 * already recording, stops the burst directly in place.
 */
class QuickCaptureTileService : TileService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        if (CaptureService.isRecording) {
            CaptureService.trigger(false, "qs tile stop")
            syncTile(recordingOverride = false)
            return
        }
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_BURST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                101,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }

    private fun syncTile(recordingOverride: Boolean? = null) {
        val tile = qsTile ?: return
        val rec = recordingOverride ?: CaptureService.isRecording
        tile.state = if (rec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.sys_tile_label)
        tile.subtitle = getString(if (rec) R.string.sys_tile_recording else R.string.sys_tile_idle)
        tile.updateTile()
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, QuickCaptureTileService::class.java)
                )
            }
        }
    }
}
