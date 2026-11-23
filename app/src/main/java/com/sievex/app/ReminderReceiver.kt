package com.sievex.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Fires local reminder notifications and reschedules active reminders after device reboot.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val localized = AppLocale.wrap(context)
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ReminderStore.rescheduleAll(localized)
            }
            ACTION_FIRE -> {
                val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
                NoteStore.init(context.filesDir)
                val reminder = ReminderStore.markFired(id) ?: return
                postNotification(localized, reminder)
            }
        }
    }

    private fun postNotification(context: Context, reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sys_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (reminder.targetType) {
                ReminderTargetType.NOTE -> {
                    reminder.targetId.toLongOrNull()?.let { putExtra(MainActivity.EXTRA_NOTE_ID, it) }
                }
                ReminderTargetType.MEDIA -> {
                    putExtra(MainActivity.EXTRA_MEDIA_ID, reminder.targetId)
                }
            }
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = when (reminder.targetType) {
            ReminderTargetType.NOTE -> context.getString(R.string.sys_reminder_note_title)
            ReminderTargetType.MEDIA -> context.getString(R.string.sys_reminder_media_title)
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(label)
            .setContentText(reminder.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIF_BASE + (reminder.id.hashCode() and 0x0FFF), notif)
    }

    companion object {
        const val ACTION_FIRE = "com.sievex.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val CHANNEL_ID = "reminders"
        private const val NOTIF_BASE = 7000
    }
}
