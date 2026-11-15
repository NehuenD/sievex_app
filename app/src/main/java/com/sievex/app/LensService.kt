package com.sievex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sievex.app.llm.Lens
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Builds one lens report off the screen, and tells the user when it is done.
 *
 * The model pass takes up to a minute, so it cannot live in the screen's coroutine scope: a
 * report that dies with the composable that asked for it throws away the user's wait, and the
 * user has no way to learn it happened. A foreground service keeps the work alive across
 * navigation, states what it is doing, and posts the result.
 *
 * The artifact is written by [Lens.generate] itself, so the notification is only ever an
 * announcement: a denied POST_NOTIFICATIONS permission or a dismissed notification loses nothing.
 *
 * Run state is process-wide and keyed by (space, lens), not held per service instance: the UI
 * polls [state] without holding a service, and a finished report stays readable after the
 * service has stopped itself.
 */
class LensService : Service() {

    /**
     * One (space, lens) pair's last observed run. [DONE] and [FAILED] persist until that same
     * pair runs again, so a UI that starts polling late still learns the outcome.
     *
     * Declared on the service, not inside the companion: `LensService.RunState` is then the one
     * qualified name every caller needs, and it resolves the same way from inside the companion.
     */
    enum class RunState { IDLE, RUNNING, DONE, FAILED }

    /**
     * One thread, so a second request queues behind the first instead of competing for the
     * model. [com.sievex.app.llm.LlmGate] would serialise the native handle anyway; one thread
     * keeps that queue visible and lets "the service is busy" be a single counter.
     */
    private lateinit var worker: ExecutorService

    /** Lifecycle calls belong on the main thread; the worker only posts to it. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Set in [onDestroy] so a report finishing after teardown cannot touch a dead service. */
    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Both channels exist before the first startForeground: on Android 12+ the notification
        // is what keeps the process alive, and creating its channel afterwards would race it.
        ensureChannels()
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sievex-lens").apply { isDaemon = true }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_RUN) {
            // Nothing was asked of this start, so nothing is worth keeping the service for.
            CaptureLog.add("lens: unknown action, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)?.trim()
        val lensId = intent.getStringExtra(EXTRA_LENS_ID)?.trim()
        if (spaceId.isNullOrEmpty() || lensId.isNullOrEmpty()) {
            CaptureLog.add("lens: run without ids, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // The start arrived through startForegroundService, so the slot has to be taken before
        // anything else can return early. Doing it unconditionally also covers the duplicate
        // path: a repeated start must not leave a foreground obligation hanging.
        val raised = runCatching {
            startForeground(
                FOREGROUND_ID,
                processingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
        if (raised.isFailure) {
            // Android 12+ can refuse the slot when the app is in the background. The pair must
            // not sit on RUNNING waiting for a run that will never happen.
            failQuietly(spaceId, lensId)
            CaptureLog.add("lens: startForeground refused - ${raised.exceptionOrNull()?.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!begin(spaceId, lensId)) {
            // One report per pair: the run already in flight will notify for this request too,
            // and it owns the foreground slot and the counter.
            CaptureLog.add("lens: $lensId on $spaceId already running")
            return START_NOT_STICKY
        }

        val submitted = runCatching { worker.execute { generate(spaceId, lensId) } }
        if (submitted.isFailure) {
            // The executor only refuses when it is shutting down, which means this instance is
            // being destroyed: publish the failure rather than leaving the pair RUNNING.
            CaptureLog.add("lens: worker refused - ${submitted.exceptionOrNull()?.message}")
            finish(spaceId, lensId, RunState.FAILED)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        // shutdown(), not shutdownNow(): a report that is already building is allowed to finish
        // and file its artifact. Only new submissions are refused, and none are coming.
        if (::worker.isInitialized) worker.shutdown()
        // The counter describes live work, and this instance's work is no longer counted. A run
        // still in flight clamps the decrement and skips the stop, because it is not this
        // instance's stop to make any more.
        synchronized(lifeLock) { pending = 0 }
        if (instance === this) instance = null
        CaptureLog.add("lens: service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The whole run, on the worker thread. Every store it reads is initialised here first:
     * CaptureService normally does that, but a report can be the first thing this process does.
     *
     * The finally block is the only place that publishes an outcome, so a thrown generation can
     * never leave a pair stuck at [RunState.RUNNING].
     */
    private fun generate(spaceId: String, lensId: String) {
        var outcome = RunState.FAILED
        // Only known once the stores are loaded. A pair that stayed null never got far enough to
        // name itself, and the request is then failed silently instead of announced.
        var names: Pair<String, String>? = null
        try {
            NoteStore.init(filesDir)
            SpaceStore.init(filesDir)
            LensStore.init(filesDir)

            val space = SpaceStore.all().firstOrNull { it.id == spaceId }
            val spec = LensStore.byId(lensId)
            if (space == null || spec == null) {
                // Deleted while the request was in flight, or never existed: there is nothing
                // readable to report on and nothing to tell the user about.
                CaptureLog.add("lens: $lensId on $spaceId is gone, nothing to build")
                return
            }

            val notes = NoteStore.inSpace(space.id)
            CaptureLog.add("lens: building $lensId on $spaceId, ${notes.size} note(s)")
            val artifact = Lens.generate(filesDir, space, notes, spec)
            names = space.name to spec.name
            if (artifact != null) {
                outcome = RunState.DONE
                CaptureLog.add("lens: built $lensId on $spaceId, ${artifact.body.length} chars")
            } else {
                CaptureLog.add("lens: $lensId on $spaceId failed")
            }
        } catch (t: Throwable) {
            CaptureLog.add("lens: $lensId on $spaceId threw - ${t.message}")
        } finally {
            names?.let { (spaceName, lensName) ->
                postResult(spaceId, lensId, spaceName, lensName, outcome == RunState.DONE)
            }
            finish(spaceId, lensId, outcome)
        }
    }

    /** Publishes [outcome] for the pair and hands the foreground slot back when it was the last. */
    private fun finish(spaceId: String, lensId: String, outcome: RunState) {
        states[keyOf(spaceId, lensId)] = outcome
        val last = synchronized(lifeLock) {
            if (pending > 0) pending--
            pending == 0
        }
        if (!last) return
        // The stop runs on the main thread, and re-checks both facts there: a start() that landed
        // between the counter read and this runnable must not be killed by a stop already on its
        // way, and a destroyed instance must not touch the service again.
        mainHandler.post {
            if (destroyed || instance !== this || isRunning()) return@post
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            CaptureLog.add("lens: stopped")
        }
    }

    private fun processingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROCESSING)
            .setContentTitle("Building your report")
            .setContentText("You can leave the app — we'll notify you when it's ready.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Without this a slow report hides behind the platform's ten-second deferral, which
            // is most of the wait it is supposed to explain.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * Announces one finished run, on its own notification id so two reports over one space never
     * overwrite each other. A refused notification is logged and dropped: the artifact is already
     * saved, so the user only loses the tap shortcut.
     */
    private fun postResult(
        spaceId: String,
        lensId: String,
        spaceName: String,
        lensName: String,
        ok: Boolean
    ) {
        val title = if (ok) "Report ready" else "Report failed"
        val text = if (ok) {
            "$lensName is ready for $spaceName"
        } else {
            "Couldn't build $lensName for $spaceName. Tap to open the space and try again."
        }

        // MainActivity may already be running behind the notification, so the tap must land on
        // the existing task instead of stacking a second copy of the screen.
        // The app context, not the service: a report that finishes after teardown must still be
        // able to post its notification.
        val app = applicationContext
        val open = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SPACE_ID, spaceId)
            // The lens too: a space can have several, and landing on the wrong report makes the
            // notification useless.
            .putExtra(EXTRA_LENS_ID, lensId)
        val tap = PendingIntent.getActivity(
            app,
            notificationId(spaceId, lensId),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_REPORTS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            // A failed report is not news worth pulling the phone out for; the failure is
            // readable in the app and the tap still takes the user to the space.
            .setSilent(!ok)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            NotificationManagerCompat.from(app).notify(notificationId(spaceId, lensId), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+. The report is saved; only the tap is lost.
            CaptureLog.add("lens: notification refused, report is saved - ${e.message}")
        }
    }

    /**
     * Both channels are created eagerly: the foreground one must exist before the first
     * startForeground, and a result channel created after the run would be pointless. No sound
     * on either, so a background report never interrupts what the user moved on to.
     *
     * minSdk is 30, so createNotificationChannel needs no API guard.
     */
    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROCESSING,
                "Report in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a lens report is being built"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REPORTS,
                "Reports",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tells you when a lens report is ready"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {

        const val EXTRA_SPACE_ID = "com.sievex.app.extra.SPACE_ID"
        const val EXTRA_LENS_ID = "com.sievex.app.extra.LENS_ID"

        private const val ACTION_RUN = "com.sievex.app.action.RUN_LENS"

        private const val CHANNEL_PROCESSING = "space_processing"
        private const val CHANNEL_REPORTS = "space_reports"

        /** CaptureService owns 42; the foreground slot here is one notification for the service. */
        private const val FOREGROUND_ID = 43

        /** Last observed state per "$spaceId/$lensId", readable without holding the service. */
        private val states = ConcurrentHashMap<String, RunState>()

        /**
         * Queued plus executing runs, guarded by [lifeLock]. The foreground slot is handed back
         * only at zero, so a second report queued behind the first cannot have it taken away.
         */
        private val lifeLock = Any()
        private var pending = 0

        /** The live instance, so a report finishing after teardown cannot stop a later one. */
        @Volatile
        private var instance: LensService? = null

        private fun keyOf(spaceId: String, lensId: String): String = "$spaceId/$lensId"

        /**
         * A distinct, stable notification id per pair. The high bit keeps report notifications
         * clear of the small ids the capture service and the foreground slot use.
         */
        private fun notificationId(spaceId: String, lensId: String): Int =
            (keyOf(spaceId, lensId).hashCode() and 0x7fffffff) or 0x40000000

        /**
         * Publishes FAILED for a request that never reached the worker. A pair that is genuinely
         * RUNNING keeps its state: that run still owns the answer.
         */
        private fun failQuietly(spaceId: String, lensId: String) {
            val key = keyOf(spaceId, lensId)
            synchronized(lifeLock) {
                if (states[key] != RunState.RUNNING) states[key] = RunState.FAILED
            }
        }

        /**
         * Claims the pair and counts the run. False means that pair is already running, and the
         * duplicate is dropped rather than queued twice.
         */
        private fun begin(spaceId: String, lensId: String): Boolean {
            val key = keyOf(spaceId, lensId)
            return synchronized(lifeLock) {
                if (states[key] == RunState.RUNNING) {
                    false
                } else {
                    states[key] = RunState.RUNNING
                    pending++
                    true
                }
            }
        }

        /** Starts a generation. Safe from the main thread; returns immediately. */
        fun start(context: Context, spaceId: String, lensId: String) {
            val space = spaceId.trim()
            val lens = lensId.trim()
            if (space.isEmpty() || lens.isEmpty()) {
                CaptureLog.add("lens: start without ids")
                return
            }
            try {
                val intent = Intent(context, LensService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_SPACE_ID, space)
                    .putExtra(EXTRA_LENS_ID, lens)
                context.startForegroundService(intent)
                CaptureLog.add("lens: requested $lens on $space")
            } catch (t: Throwable) {
                // Android 12+ can refuse a foreground start from the background. That refusal
                // must read as a failed report, never as a crash in the caller's frame.
                failQuietly(space, lens)
                CaptureLog.add("lens: start refused for $lens on $space - ${t.message}")
            }
        }

        /**
         * Last observed state for one (space, lens) pair: polled by the UI while a report runs,
         * and [RunState.DONE]/[RunState.FAILED] stay readable until that pair runs again.
         */
        fun state(spaceId: String, lensId: String): RunState =
            states[keyOf(spaceId.trim(), lensId.trim())] ?: RunState.IDLE

        /** True while any report is queued or executing, whichever pair asked for it. */
        fun isRunning(): Boolean = synchronized(lifeLock) { pending > 0 }
    }
}
