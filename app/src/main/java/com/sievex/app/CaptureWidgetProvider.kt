package com.sievex.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Compact dark home-screen capture widget that starts/stops voice bursts in one tap.
 *
 * Starting a microphone foreground service from a background BroadcastReceiver is restricted on
 * Android 14+ (API 34/35). When idle, the widget launches [MainActivity] with
 * [MainActivity.EXTRA_START_BURST] so the app enters the foreground and starts recording right
 * away; when already recording, tapping the widget stops the burst directly in the background.
 */
class CaptureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val localized = AppLocale.wrap(context)
        val rec = CaptureService.isRecording
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(localized, rec))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_CAPTURE) {
            val localized = AppLocale.wrap(context)
            if (CaptureService.isRecording) {
                CaptureService.trigger(false, "widget stop")
                updateAll(localized, recordingOverride = false)
            } else {
                val open = Intent(localized, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_BURST, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { localized.startActivity(open) }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_CAPTURE = "com.sievex.WIDGET_TOGGLE_CAPTURE"

        fun updateAll(context: Context, recordingOverride: Boolean? = null) {
            val app = AppLocale.wrap(context.applicationContext)
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(app, CaptureWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val rec = recordingOverride ?: CaptureService.isRecording
            val views = buildViews(app, rec)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, recording: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_capture)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            views.setTextViewText(
                R.id.widget_subtitle,
                context.getString(if (recording) R.string.sys_widget_recording else R.string.sys_widget_idle)
            )
            views.setImageViewResource(
                R.id.widget_orb,
                if (recording) R.drawable.widget_orb_recording else R.drawable.widget_orb_idle
            )
            val pi = if (recording) {
                val stop = Intent(context, CaptureWidgetProvider::class.java)
                    .setAction(ACTION_TOGGLE_CAPTURE)
                PendingIntent.getBroadcast(
                    context,
                    202,
                    stop,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val start = Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_BURST, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(
                    context,
                    203,
                    start,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}
