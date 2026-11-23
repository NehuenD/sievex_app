package com.sievex.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ReminderTargetType(val id: String) {
    NOTE("note"),
    MEDIA("media");

    companion object {
        fun from(raw: String?): ReminderTargetType =
            entries.firstOrNull { it.id == raw } ?: NOTE
    }
}

data class Reminder(
    val id: String,
    val targetType: ReminderTargetType,
    val targetId: String,
    val title: String,
    val triggerAtMs: Long,
    val fired: Boolean = false
)

/**
 * Local-only reminder persistence and AlarmManager scheduling for Action notes and Screenshots.
 * Uses `setAndAllowWhileIdle` so reminders work on Android 12+ without requiring exact-alarm
 * special app access.
 */
object ReminderStore {

    private val reminders = mutableListOf<Reminder>()
    private var file: File? = null

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile
    private var writesBlocked = false

    @Synchronized
    fun init(root: File) {
        if (file != null) return
        file = File(root, "reminders.json")
        load()
        _version.value = _version.value + 1L
    }

    @Synchronized
    fun all(): List<Reminder> = reminders.toList()

    @Synchronized
    fun active(): List<Reminder> =
        reminders.filter { !it.fired }.sortedBy { it.triggerAtMs }

    @Synchronized
    fun forTarget(type: ReminderTargetType, targetId: String): Reminder? =
        reminders.firstOrNull { !it.fired && it.targetType == type && it.targetId == targetId }

    @Synchronized
    fun byId(id: String): Reminder? = reminders.firstOrNull { it.id == id }

    fun schedule(
        context: Context,
        type: ReminderTargetType,
        targetId: String,
        title: String,
        triggerAtMs: Long
    ): Reminder {
        val app = context.applicationContext
        init(app.filesDir)
        val cleanTitle = title.trim().ifBlank { app.getString(R.string.app_name) }
        val replaced: List<Reminder>
        val created = Reminder(
            id = "rm_" + UUID.randomUUID().toString().replace("-", "").take(12),
            targetType = type,
            targetId = targetId,
            title = cleanTitle,
            triggerAtMs = triggerAtMs,
            fired = false
        )
        synchronized(this) {
            replaced = reminders.filter { it.targetType == type && it.targetId == targetId }
            reminders.removeAll { it.targetType == type && it.targetId == targetId }
            reminders.add(created)
            save()
        }
        replaced.forEach { cancelAlarm(app, it.id) }
        scheduleAlarm(app, created)
        CaptureLog.add("reminders: scheduled ${created.id} for ${type.id}:$targetId")
        return created
    }

    fun cancelForTarget(context: Context?, type: ReminderTargetType, targetId: String) {
        val removed: List<Reminder>
        synchronized(this) {
            removed = reminders.filter { it.targetType == type && it.targetId == targetId }
            if (removed.isEmpty()) return
            reminders.removeAll { it.targetType == type && it.targetId == targetId }
            save()
        }
        val app = context?.applicationContext
        if (app != null) {
            removed.forEach { cancelAlarm(app, it.id) }
        }
    }

    fun cancelById(context: Context, id: String) {
        val removed: Boolean
        synchronized(this) {
            removed = reminders.removeAll { it.id == id }
            if (removed) save()
        }
        cancelAlarm(context.applicationContext, id)
        if (removed) CaptureLog.add("reminders: cancelled $id")
    }

    @Synchronized
    fun markFired(id: String): Reminder? {
        val i = reminders.indexOfFirst { it.id == id }
        if (i < 0) return null
        val updated = reminders[i].copy(fired = true)
        reminders[i] = updated
        save()
        return updated
    }

    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        init(app.filesDir)
        val now = System.currentTimeMillis()
        val pending = active()
        for (r in pending) {
            val whenMs = if (r.triggerAtMs < now) now + 5_000L else r.triggerAtMs
            scheduleAlarm(app, r.copy(triggerAtMs = whenMs))
        }
    }

    private fun scheduleAlarm(context: Context, reminder: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPendingIntent(context, reminder.id)
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pi)
        }.onFailure {
            CaptureLog.add("reminders: alarm failed - ${it.message}")
        }
    }

    private fun cancelAlarm(context: Context, reminderId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(alarmPendingIntent(context, reminderId)) }
    }

    private fun alarmPendingIntent(context: Context, reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            val loaded = mutableListOf<Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val targetId = o.optString("targetId")
                val triggerAtMs = o.optLong("triggerAtMs", 0L)
                if (id.isBlank() || targetId.isBlank() || triggerAtMs <= 0L) continue
                loaded.add(
                    Reminder(
                        id = id,
                        targetType = ReminderTargetType.from(o.optString("targetType")),
                        targetId = targetId,
                        title = o.optString("title"),
                        triggerAtMs = triggerAtMs,
                        fired = o.optBoolean("fired", false)
                    )
                )
            }
            reminders.clear()
            reminders.addAll(loaded)
        }.onFailure { e ->
            CaptureLog.add("reminders: load failed - ${e.message}")
            val target = File(f.parentFile, f.name + ".corrupt-" + System.currentTimeMillis())
            if (!f.renameTo(target)) writesBlocked = true
            reminders.clear()
        }
    }

    private fun save() {
        _version.value = _version.value + 1L
        val f = file ?: return
        if (writesBlocked) return
        val snapshot = reminders.toList()
        StoreIo.executor.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { r ->
                    arr.put(
                        JSONObject().apply {
                            put("id", r.id)
                            put("targetType", r.targetType.id)
                            put("targetId", r.targetId)
                            put("title", r.title)
                            put("triggerAtMs", r.triggerAtMs)
                            put("fired", r.fired)
                        }
                    )
                }
                StoreIo.writeAtomic(f, arr.toString())
            }.onFailure { CaptureLog.add("reminders: save failed - ${it.message}") }
        }
    }
}
