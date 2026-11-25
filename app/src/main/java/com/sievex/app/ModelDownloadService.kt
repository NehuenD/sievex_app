package com.sievex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.TransformModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps on-device model downloads alive while the screen is locked or the user switches apps.
 *
 * Holds a `dataSync` foreground slot together with a partial WakeLock and low-latency WifiLock so
 * Android does not throttle the network socket or suspend CPU execution when the phone locks
 * during a 640 MB–1.05 GB transfer.
 */
class ModelDownloadService : Service() {

    enum class Outcome { SUCCESS, CANCELLED, FAILED }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var executor: ExecutorService

    private val stateLock = Any()
    private val activeTargets = HashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastNotifyAtMs = 0L
    private var lastNotifiedPct = -1
    private var lastNotifiedKey = ""
    private var stopped = false

    @Volatile
    private var destroyed = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val threadCounter = AtomicInteger(0)
        executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "sievex-dl-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (isIdle()) stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL) {
            TransformModel.cancel()
            ModelInstaller.cancel()
            if (isIdle()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val target = intent.getStringExtra(EXTRA_TARGET)
        if (intent.action != ACTION_DOWNLOAD || (target != TARGET_LLM && target != TARGET_ASR)) {
            if (isIdle()) stopSelf()
            return START_NOT_STICKY
        }

        val foregroundOk = runCatching {
            startForeground(
                FOREGROUND_ID,
                buildProgressNotification(snapshotTargets(extra = target)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }.isSuccess

        if (!foregroundOk) {
            CaptureLog.add("download: startForeground refused for $target; falling back to worker thread")
            if (target == TARGET_LLM) {
                TransformModel.runFallbackThread(applicationContext)
            } else {
                ModelInstaller.runFallbackThread(applicationContext)
            }
            if (isIdle()) stopSelf()
            return START_NOT_STICKY
        }

        val added = synchronized(stateLock) {
            stopped = false
            val inserted = activeTargets.add(target)
            if (activeTargets.isNotEmpty()) acquireLocksLocked()
            inserted
        }
        if (!added) {
            updateProgressNotification(force = true)
            return START_NOT_STICKY
        }

        updateProgressNotification(force = true)

        val submitted = runCatching {
            executor.execute { runTarget(target) }
        }
        if (submitted.isFailure) {
            CaptureLog.add("download: executor rejected $target - ${submitted.exceptionOrNull()?.message}")
            finishTarget(target, Outcome.FAILED, postCompletionNotice = false)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        CaptureLog.add("download: dataSync FGS timeout (startId=$startId)")
        TransformModel.cancel()
        ModelInstaller.cancel()
        synchronized(stateLock) {
            stopped = true
            activeTargets.clear()
            releaseLocksLocked()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        TransformModel.finishServiceDownload()
        ModelInstaller.finishServiceDownload()
        stopSelf()
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::executor.isInitialized) executor.shutdown()
        synchronized(stateLock) {
            stopped = true
            activeTargets.clear()
            releaseLocksLocked()
        }
        runCatching { NotificationManagerCompat.from(this).cancel(FOREGROUND_ID) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runTarget(target: String) {
        var outcome = Outcome.FAILED
        try {
            outcome = if (target == TARGET_LLM) {
                TransformModel.executeDownload(applicationContext, manageInFlight = false) {
                    updateProgressNotification(force = false)
                }
            } else {
                ModelInstaller.executeDownload(applicationContext, manageInFlight = false) {
                    updateProgressNotification(force = false)
                }
            }
        } catch (t: Throwable) {
            CaptureLog.add("download: $target threw unexpectedly - ${t.message}")
        } finally {
            finishTarget(target, outcome, postCompletionNotice = true)
        }
    }

    private fun finishTarget(target: String, outcome: Outcome, postCompletionNotice: Boolean) {
        if (postCompletionNotice && outcome != Outcome.CANCELLED) {
            postOutcomeNotification(target, outcome == Outcome.SUCCESS)
        }
        val empty = synchronized(stateLock) {
            activeTargets.remove(target)
            if (activeTargets.isEmpty()) {
                releaseLocksLocked()
                true
            } else {
                false
            }
        }
        // Release the target's inFlight latch strictly AFTER removing it from activeTargets
        // so a new tap can never be dropped while activeTargets still contains the finished target.
        if (target == TARGET_LLM) {
            TransformModel.finishServiceDownload()
        } else {
            ModelInstaller.finishServiceDownload()
        }
        if (!empty) {
            updateProgressNotification(force = true)
            return
        }
        mainHandler.post {
            val shouldStop = synchronized(stateLock) {
                if (destroyed) return@synchronized false
                if (activeTargets.isEmpty()) {
                    stopped = true
                    releaseLocksLocked()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    true
                } else {
                    false
                }
            }
            if (shouldStop) stopSelf()
        }
    }

    private fun isIdle(): Boolean = synchronized(stateLock) { activeTargets.isEmpty() }

    private fun snapshotTargets(extra: String? = null): Set<String> = synchronized(stateLock) {
        if (extra == null) HashSet(activeTargets) else HashSet(activeTargets).apply { add(extra) }
    }

    private fun acquireLocksLocked() {
        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sievex:model_download")?.apply {
                    setReferenceCounted(false)
                    acquire(LOCK_TIMEOUT_MS)
                }
            }
        } else {
            runCatching { wakeLock?.acquire(LOCK_TIMEOUT_MS) }
        }

        if (wifiLock == null) {
            runCatching {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sievex:model_download")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else if (wifiLock?.isHeld == false) {
            runCatching { wifiLock?.acquire() }
        }
    }

    private fun releaseLocksLocked() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun updateProgressNotification(force: Boolean) {
        synchronized(stateLock) {
            if (destroyed || stopped || activeTargets.isEmpty()) return
            val targets = HashSet(activeTargets)
            val state = computeSummary(targets)
            val now = SystemClock.elapsedRealtime()
            val pctMoved = kotlin.math.abs(state.pct - lastNotifiedPct) >= 2
            val phaseChanged = state.phaseKey != lastNotifiedKey
            if (!force && !phaseChanged && (!pctMoved || now - lastNotifyAtMs < NOTIFY_MIN_INTERVAL_MS)) {
                return
            }
            lastNotifyAtMs = now
            lastNotifiedPct = state.pct
            lastNotifiedKey = state.phaseKey

            val notif = buildProgressNotification(targets, state)
            runCatching { NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notif) }
        }
    }

    private data class ProgressSummary(
        val pct: Int,
        val indeterminate: Boolean,
        val phaseKey: String,
        val text: String
    )

    private fun computeSummary(targets: Set<String>): ProgressSummary {
        var sum = 0f
        var count = 0
        var anyVerifying = false
        var anyRetrying = false

        if (TARGET_LLM in targets) {
            sum += TransformModel.progress.coerceIn(0f, 1f)
            count++
            if (TransformModel.verifying) anyVerifying = true
            if (TransformModel.retrying) anyRetrying = true
        }
        if (TARGET_ASR in targets) {
            sum += ModelInstaller.progress.coerceIn(0f, 1f)
            count++
            if (ModelInstaller.verifying) anyVerifying = true
            if (ModelInstaller.retrying) anyRetrying = true
        }

        val avg = if (count > 0) (sum / count).coerceIn(0f, 1f) else 0f
        val pct = (avg * 100f).toInt().coerceIn(0, 100)
        return when {
            anyVerifying -> ProgressSummary(pct, true, "verify", getString(R.string.sys_download_notif_verifying))
            anyRetrying -> ProgressSummary(pct, true, "retry", getString(R.string.sys_model_retrying))
            pct <= 0 -> ProgressSummary(0, true, "start", getString(R.string.sys_model_starting_download))
            else -> ProgressSummary(pct, false, "dl", getString(R.string.sys_download_notif_progress, pct))
        }
    }

    private fun buildProgressNotification(
        targets: Set<String>,
        summary: ProgressSummary = computeSummary(targets)
    ): Notification {
        val title = when {
            TARGET_LLM in targets && TARGET_ASR in targets -> getString(R.string.sys_download_notif_both_title)
            TARGET_ASR in targets -> getString(R.string.sys_download_notif_asr_title)
            else -> getString(R.string.sys_download_notif_llm_title)
        }

        val app = applicationContext
        val openIntent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            app,
            FOREGROUND_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(app, ModelDownloadService::class.java)
            .setAction(ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(
            app,
            FOREGROUND_ID + 100,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_DOWNLOADS)
            .setContentTitle(title)
            .setContentText(summary.text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.sys_download_notif_cancel), cancelPending)
            .setProgress(100, summary.pct, summary.indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postOutcomeNotification(target: String, success: Boolean) {
        val app = applicationContext
        val notifId = if (target == TARGET_LLM) DONE_LLM_ID else DONE_ASR_ID
        val title = if (success) {
            if (target == TARGET_LLM) getString(R.string.sys_download_done_llm_title)
            else getString(R.string.sys_download_done_asr_title)
        } else {
            getString(R.string.sys_download_failed_title)
        }
        val text = if (success) {
            getString(R.string.sys_download_done_text)
        } else {
            getString(R.string.sys_download_failed_text)
        }
        val icon = if (success) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }

        val open = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            app,
            notifId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(app, CHANNEL_DOWNLOADS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            NotificationManagerCompat.from(app).notify(notifId, notif)
        } catch (e: SecurityException) {
            CaptureLog.add("download: completion notification refused - ${e.message}")
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.sys_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sys_download_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val TARGET_LLM = "llm"
        const val TARGET_ASR = "asr"

        private const val ACTION_DOWNLOAD = "com.sievex.app.action.DOWNLOAD_MODEL"
        private const val ACTION_CANCEL = "com.sievex.app.action.CANCEL_DOWNLOAD"
        private const val EXTRA_TARGET = "target"

        private const val CHANNEL_DOWNLOADS = "model_downloads"
        private const val FOREGROUND_ID = 44
        private const val DONE_LLM_ID = 45
        private const val DONE_ASR_ID = 46

        private const val LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L
        private const val NOTIFY_MIN_INTERVAL_MS = 500L

        fun start(context: Context, target: String): Boolean {
            return runCatching {
                val intent = Intent(context, ModelDownloadService::class.java)
                    .setAction(ACTION_DOWNLOAD)
                    .putExtra(EXTRA_TARGET, target)
                context.startForegroundService(intent)
                true
            }.onFailure {
                CaptureLog.add("download: foreground service start refused for $target - ${it.message}")
            }.getOrDefault(false)
        }
    }
}
