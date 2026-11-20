package com.sievex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sievex.app.asr.AsrEngine
import com.sievex.app.llm.LlamaBridge
import com.sievex.app.llm.LlmGate
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Armed foreground service. The microphone stays closed until a trigger arrives; the service
 * exists so a background media-pause event can open the recorder without a fresh background start.
 */
class CaptureService : Service() {

    // Written by the service thread and read by the recorder thread in processBurst().
    @Volatile
    private var recorder: BurstRecorder? = null
    @Volatile
    private var recording = false

    /**
     * Bursts whose recorder thread has not reached [processBurst] yet. It decides whether a release
     * can happen now or has to wait for the drain that the finishing burst will start. Atomic
     * because a disarm on the service thread and a finishing recorder thread both touch it.
     */
    private val burstsInFlight = AtomicInteger(0)

    /** Released once the worker has loaded the stores; a burst ending earlier must wait for it. */
    private val storesLoaded = CountDownLatch(1)

    /**
     * True when this service instance exists only to drain the queue (a kick). Such an instance
     * takes the foreground slot for itself and gives it back when the queue runs dry.
     */
    @Volatile
    private var kickDrain = false

    /** Set by [runJob] when it stopped because a model is not ready: the queue is waiting, not failing. */
    @Volatile
    private var held = false

    /**
     * The format snapshotted when the current burst started. The picker stays tappable while
     * recording, so reading `Formats.next` at queue time would reshape the burst being spoken.
     */
    @Volatile
    private var burstFormat: String? = null

    @Volatile
    private var burstMediaIds: List<String> = emptyList()

    @Volatile
    private var burstSpaceId: String? = null

    /** Handler retries are posted here so they survive the worker thread finishing a drain. */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val retryRunnable = Runnable { processNext() }

    /** Notification text follows the language chosen in Settings, like the rest of the app. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A podcast-triggered burst can arrive after a process death, before any screen has
        // restored the picker: read the persisted choice before a job can be created.
        Formats.restore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sys_capture_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        // Parsing notes.json/jobs.json and scanning for orphans is file work, so it runs on the
        // same single worker that drains the queue: every store read is then ordered behind init.
        WORKER.execute {
            try {
                NoteStore.init(filesDir)
                SpaceStore.init(filesDir)
                JobQueue.init(filesDir)
                // Bursts queued by an earlier process are still on disk: pick them up again.
                // A WAV can also be on disk without a queue entry (crash between the recorder closing
                // the file and JobQueue.add), so it is folded back into the queue before counting.
                recoverOrphanBursts()
                // Only now is the queue content final, so only now may kick() trust it.
                queueReady = true
                val pending = JobQueue.all().size
                if (pending > 0) CaptureLog.add("resuming $pending queued burst(s)")
            } catch (t: Throwable) {
                CaptureLog.add("startup: ${t.message}")
            } finally {
                storesLoaded.countDown()
            }
        }
        processNext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            ACTION_START_BURST -> {
                if (!armed) arm()
                val mediaIds = intent.getStringArrayListExtra(EXTRA_MEDIA_IDS)?.toList() ?: emptyList()
                val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)?.takeIf { it.isNotBlank() }
                startBurst(intent.getStringExtra(EXTRA_WHY) ?: "manual", mediaIds, spaceId)
            }
            ACTION_STOP_BURST -> stopBurst()
            ACTION_KICK -> return handleKick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(retryRunnable)
        recording = false
        isRecording = false
        recorder?.stop()
        recorder = null
        armed = false
        kickDrain = false
        // The process may outlive the service (the media listener keeps it alive), so the models
        // are given back here rather than waiting for the process to die with them. A queued job
        // that the service was retrying has just lost its timer, so the queue can no longer drain
        // on its own: holding the models for it would keep 1.75 GB resident with nothing to run.
        if (burstsInFlight.get() == 0) releaseModels()
        if (instance === this) instance = null
        CaptureLog.add("service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun arm() {
        armed = true
        // The service now has a reason of its own: a drain must not take its notification back.
        kickDrain = false
        startForeground(NOTIF_ID, buildNotification(getString(R.string.sys_capture_armed)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        // Otherwise the first burst of every process pays the 652 MB encoder load and the 1.1 GB
        // LLM load inside the user's wait. Warming on the queue worker keeps the loads ordered
        // behind store init and off the thread that opens the microphone.
        WORKER.execute { warmModels() }
        CaptureLog.add("armed")
    }

    private fun disarm() {
        armed = false
        stopBurst()
        // A burst that is still finishing will queue itself, and the drain it starts releases the
        // models when it runs dry; releasing here would only unload just in time for that drain to
        // reload. With nothing in flight and nothing queued, no drain is coming, so release now.
        if (burstsInFlight.get() == 0 && JobQueue.peek() == null) releaseModels()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        CaptureLog.add("disarmed")
    }

    /**
     * Loads both models while the user is still talking, not while they are waiting for a note.
     *
     * Every failure is swallowed on purpose: the job itself still calls [AsrEngine.ensureLoaded]
     * and [LlamaBridge.nativeLoad] lazily, so a warm-up that ran out of memory or found no model
     * costs nothing but the milliseconds it spent trying. Both loads are idempotent, so repeated
     * arms are cheap.
     */
    private fun warmModels() {
        val started = System.currentTimeMillis()
        var warm = 0

        // The recognizer is 652 MB: it is the load worth paying before the burst, not after it.
        val asrWarm = runCatching {
            AsrEngine.isInstalled(filesDir) && AsrEngine.ensureLoaded(filesDir)
        }.getOrDefault(false)
        if (asrWarm) warm++

        val model = TransformModel.modelFile(filesDir)
        // A Plain burst never calls the model, so warming 1.1 GB for it only competes with the
        // speech encoder for memory. Anything already queued that does need shaping keeps it warm.
        val needsLlm = Formats.next.usesLlm ||
            JobQueue.all().any { NoteFormat.from(it.format).usesLlm }
        if (needsLlm && model.isFile && model.length() > 0L) {
            val llmWarm = runCatching {
                // The native handle is single-session, so the load goes through the same gate
                // every completion uses.
                LlmGate.exclusive { LlamaBridge.nativeLoad(model.absolutePath) }
            }.getOrDefault(false)
            if (llmWarm) warm++
        }

        CaptureLog.add("warm: $warm model(s) in ${System.currentTimeMillis() - started} ms")

        // A disarm that landed while these loads were running already released what it could.
        // Without this check the warm-up would finish afterwards and leave both resident with no
        // session to use them, which is exactly the leak this is meant to remove.
        if (!armed && !recording) releaseModels()
    }

    /**
     * Hands the model memory back. Both unloads are safe to repeat: the recognizer is dropped
     * under its own lock and the native side null-checks its handle under [LlmGate].
     *
     * Runs on a short-lived daemon thread on purpose. The native handle sits behind [LlmGate],
     * which a Lens can hold for a whole generation; waiting for it on the queue worker would stall
     * every drain (and the foreground hand-back) behind a report the user asked for.
     */
    private fun releaseModels() {
        Thread({
            // The session may have resumed between the request and this thread running; keeping
            // the models warm is the whole point of arm(), so do not undo a warm-up that just ran.
            if (!armed && !recording) {
                runCatching { AsrEngine.unload() }
                runCatching { LlmGate.exclusive { LlamaBridge.nativeUnload() } }
            }
        }, "sievex-unload").apply { isDaemon = true }.start()
    }

    /**
     * A kick asks the queue to be drained. It never arms the service and never opens the
     * microphone: it only makes sure the process survives long enough to file what is queued.
     */
    private fun handleKick(): Int {
        // A startForegroundService start must reach startForeground quickly or the platform
        // kills the process. When the service is already armed its slot and text are its own.
        if (!armed) {
            val raised = runCatching {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.sys_capture_processing_queued)))
            }
            if (raised.isFailure) {
                // Never leave the foreground obligation hanging: stopping clears it. The queue
                // is durable, so the next kick picks the work up again.
                CaptureLog.add("kick: startForeground refused - ${raised.exceptionOrNull()?.message}")
                WORKER.execute { kickDrainNow() }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        CaptureLog.add("kick: drain requested")
        // Read the queue on the worker: jobs.json may not be loaded yet when the platform
        // delivers this intent straight after onCreate.
        WORKER.execute { kickDrainNow() }
        return START_STICKY
    }

    /** Runs on the worker, so the job queue is necessarily initialised by the time it is read. */
    private fun kickDrainNow() {
        if (JobQueue.peek() == null) {
            if (armed || recording) return
            CaptureLog.add("kick: nothing queued")
            kickDrain = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        kickDrain = true
        // Through processNext so a thrown job cannot escape the worker's own guard.
        processNext()
    }

    fun startBurst(
        why: String,
        mediaIds: List<String> = emptyList(),
        spaceId: String? = null
    ) {
        if (recording) return
        if (!armed) {
            CaptureLog.add("burst ignored, not armed ($why)")
            return
        }
        val next = BurstRecorder(this) { file -> processBurst(file) }
        recorder = next
        // Snapshot the shape now: the picker stays on screen while recording, and changing it must
        // apply to the next burst, not the one being spoken.
        burstFormat = Formats.next.id
        burstMediaIds = mediaIds
        burstSpaceId = spaceId
        // Set before the capture thread starts: a recorder that dies on its first read invokes
        // onComplete() immediately, and processBurst() has to see the state stopBurst() leaves
        // behind, not a service that still believes it is recording.
        recording = true
        isRecording = true
        burstsInFlight.incrementAndGet()
        val file = runCatching { next.start() }.getOrNull()
        if (file == null) {
            // start() returned null, so no capture thread exists and no onComplete can fire.
            burstsInFlight.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            recording = false
            isRecording = false
            recorder = null
            burstFormat = null
            CaptureLog.add("burst failed to start ($why)")
            return
        }
        // An instant-death recorder may already have posted its own "Armed" notification through
        // processBurst(); do not paint "Recording" over a burst that is gone.
        if (recording) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(getString(R.string.sys_capture_recording, file.name)))
        }
    }

    fun stopBurst() {
        if (!recording) return
        recording = false
        isRecording = false
        Pipeline.transcribing()
        recorder?.stop()
        recorder = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(getString(R.string.sys_capture_armed)))
    }

    /**
     * A burst just finished: write it to the durable queue and return. Nothing is transcribed
     * on the recorder thread, so a process death between here and the note loses no audio.
     */
    private fun processBurst(file: File) {
        try {
            processBurstNow(file)
        } finally {
            // The burst is on disk or already queued; either way this recorder thread is done, and
            // that is what makes a pending release safe.
            burstsInFlight.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    private fun processBurstNow(file: File) {
        // The recorder caps a burst at 90 s and stops itself, which bypasses stopBurst(). Without
        // this the service would believe it is still recording: the next pause would be ignored
        // and the queue would never be allowed to release the foreground slot.
        if (recording && recorder?.currentFile?.absolutePath == file.absolutePath) {
            recording = false
            isRecording = false
            recorder = null
            // The cap ended this burst the way stopBurst() would, so the UI must leave IDLE.
            Pipeline.transcribing()
            if (armed) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(getString(R.string.sys_capture_armed)))
            }
        }
        // The stores load on the worker; a burst that ends before that must not add its job to an
        // uninitialised queue, where the entry would live only in memory. The WAV is on disk, so
        // orphan recovery is the backstop if this ever times out.
        try {
            if (!storesLoaded.await(STORE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                CaptureLog.add("jobs: stores not ready, ${file.name} left for orphan recovery")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Orphan recovery may already have queued this exact file; one WAV never gets two jobs.
        if (JobQueue.all().any { it.wavPath == file.absolutePath }) {
            CaptureLog.add("already queued ${file.name}")
            processNext()
            return
        }
        val job = Job(
            id = UUID.randomUUID().toString(),
            wavPath = file.absolutePath,
            spaceId = burstSpaceId ?: SpaceStore.currentSpaceId,
            attempts = 0,
            // Read now, not when the job drains: the user may change the format for the next
            // burst while this one is still queued.
            format = burstFormat ?: Formats.next.id,
            mediaIds = burstMediaIds
        )
        JobQueue.add(job)
        burstFormat = null
        burstMediaIds = emptyList()
        burstSpaceId = null
        CaptureLog.add("queued ${file.name}${job.spaceId?.let { " into $it" } ?: ""}")
        processNext()
    }

    /** Drains the durable queue, one job at a time, on the single worker thread. */
    fun processNext() {
        WORKER.execute {
            try {
                drain()
            } catch (t: Throwable) {
                CaptureLog.add("jobs: worker stopped - ${t.message}")
            }
        }
    }

    private fun drain() {
        if (drainLoop()) onQueueEmpty()
    }

    /** Returns true when the queue drained to empty, false when the head must stay queued. */
    private fun drainLoop(): Boolean {
        while (true) {
            val job = JobQueue.peek() ?: return true

            // A job that already burned every attempt can only ever fail again. Drop it and
            // keep going instead of peeking the same head forever and wedging the queue.
            if (job.attempts >= JobQueue.MAX_ATTEMPTS) {
                JobQueue.remove(job.id)
                CaptureLog.add("job ${job.id}: dropped, ${job.attempts} attempts already spent")
                quarantineWav(job, "attempts exhausted")
                continue
            }

            val result = runCatching { runJob(job) }

            // An exception used to leave the attempt counter untouched, so the same job was
            // retried forever. It now burns an attempt like any other failure.
            val crash = result.exceptionOrNull()
            if (crash != null) {
                CaptureLog.add("jobs: ${job.id} crashed - ${crash.message}")
                retryOrDrop(job, crash.message ?: "crashed")
                // Only continue when the job is gone; a surviving job gets one timer retry.
                if (JobQueue.all().any { it.id == job.id }) {
                    scheduleRetry()
                    return false
                }
                continue
            }

            if (!result.getOrDefault(false)) {
                if (held) {
                    // Waiting for a model: no attempt was burned, so no timer is armed.
                    onQueueHeld()
                    return false
                }
                // A failure that burned its last attempt drops the job: keep draining instead
                // of leaving the queue idle for a minute.
                if (JobQueue.all().any { it.id == job.id }) {
                    scheduleRetry()
                    return false
                }
                continue
            }
        }
    }

    /**
     * A job survived a failure: retry it once in a minute instead of waiting for the next user
     * action. Only one timer is ever armed, so repeated failures never stack up.
     */
    private fun scheduleRetry() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        CaptureLog.add("jobs: retry in ${RETRY_DELAY_MS / 1000}s")
    }

    /** The queue is empty: the foreground slot exists only for the queue, so give it back. */    private fun onQueueEmpty() {
        if (armed || recording) return
        if (JobQueue.peek() != null) return
        // Nothing is waiting: whatever the models were kept warm for is over. This runs before the
        // kickDrain gate below so that a drain started by a finishing burst also frees them, even
        // when no kick asked for it (that drain is what follows a disarm).
        releaseModels()
        if (!kickDrain) return
        CaptureLog.add("jobs: queue empty, foreground released")
        kickDrain = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The head is waiting for a model rather than failing, so no attempt is burned and no timer
     * is armed: ModelInstaller/TransformModel kick the queue again as soon as the model lands.
     */
    private fun onQueueHeld() {
        if (armed || recording) return
        CaptureLog.add("jobs: queue held, waiting for a model")
        kickDrain = false
        // Nothing can run until the missing model lands, and the next kick reloads on demand.
        releaseModels()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Returns true when the job is gone (filed or dropped), false when it must stay queued. */
    private fun runJob(job: Job): Boolean {
        held = false
        val file = File(job.wavPath)

        // The job can never succeed without its audio. Drop it without burning a retry: the
        // WAV is not coming back, and retrying would block every job behind it.
        if (!file.exists()) {
            CaptureLog.add("job ${job.id}: wav missing ${file.name}, dropped")
            JobQueue.remove(job.id)
            return true
        }

        // Same rule for the model: a missing prerequisite is not the job's fault, so the
        // counter stays where it is and the queue waits instead of burning attempts to zero.
        // This is a held state, not a failure: the UI clears it once the model installs.
        if (!AsrEngine.isInstalled(filesDir)) {
            CaptureLog.add("asr not installed, holding ${file.name}")
            held = true
            Pipeline.block(getString(R.string.sys_pipeline_waiting_for_speech_model))
            return false
        }

        // A crash between NoteStore.add and JobQueue.remove leaves the burst queued even
        // though its note exists. Filing it again would duplicate the note.
        if (isFiled(file.absolutePath)) {
            CaptureLog.add("job ${job.id}: ${file.name} already filed, dropped duplicate")
            JobQueue.remove(job.id)
            return true
        }

        // A present but broken model (torn download, wrong runtime) is also a hold, not a
        // failure: burning the job's attempts would throw the recording away for nothing.
        if (!AsrEngine.ensureLoaded(filesDir)) {
            CaptureLog.add("asr not ready, holding ${file.name}")
            held = true
            Pipeline.block(getString(R.string.sys_pipeline_speech_model_not_ready))
            return false
        }

        CaptureLog.add("transcribing ${file.name}")
        Pipeline.transcribing()
        val text = AsrEngine.transcribe(filesDir, job.wavPath)

        // A thrown inference returns null. That is a real failure and may well clear on a retry,
        // so it keeps the normal attempt-and-retry path.
        if (text == null) {
            CaptureLog.add("job ${job.id}: asr returned no result")
            Pipeline.failed(getString(R.string.sys_pipeline_transcribe_failed))
            retryOrDrop(job, "asr returned no result")
            return false
        }

        // An empty transcript belongs to the audio, not to this attempt: decoding the same
        // samples again can only return the same empty string. Retrying it would block the queue
        // head for another minute for nothing, so the job is dropped on the spot and the WAV is
        // quarantined (moved, never deleted) so orphan recovery cannot queue it in a loop.
        if (text.isBlank()) {
            // Quarantine first: it is the move that stops orphan recovery from re-queuing this
            // silent file, and it must survive a crash between these two steps.
            quarantineWav(job, "no speech detected")
            JobQueue.remove(job.id)
            CaptureLog.add("job ${job.id}: blank transcript, ${file.name} quarantined")
            Pipeline.failed(getString(R.string.sys_pipeline_no_speech_detected))
            return true
        }

        val format = NoteFormat.from(job.format)
        Pipeline.transforming(format)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val whenText = "Today $time"
        val structured = Transform.apply(
            filesDir,
            text,
            format,
            AppLocale.modelLanguage(this)
        ) { phase ->
            Pipeline.transformingStep(phase)
        }

        // The space could have been deleted while this burst sat in the queue.
        val spaceId = job.spaceId?.takeIf { id -> SpaceStore.all().any { it.id == id } }

        val note = if (structured != null) {
            CaptureLog.add("transform: ${structured.mode} / ${structured.title}")
            StoredNote(
                id = NoteStore.nextId(),
                title = structured.title,
                oneLine = structured.oneLine,
                mode = structured.mode,
                whenText = whenText,
                verbatim = structured.verbatim,
                why = structured.why,
                question = structured.question,
                tags = structured.tags,
                audioPath = file.absolutePath,
                spaceId = spaceId,
                format = format.id,
                items = structured.items,
                createdAt = System.currentTimeMillis()
            )
        } else {
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val title = words.take(9).joinToString(" ").trimEnd('.', ',', ';', ':')
            CaptureLog.add("transform unavailable, filing raw transcript")
            StoredNote(
                id = NoteStore.nextId(),
                title = title.ifBlank { getString(R.string.common_untitled_note) },
                oneLine = "",
                mode = "Note",
                whenText = whenText,
                verbatim = text,
                why = "",
                question = "",
                tags = emptyList(),
                audioPath = file.absolutePath,
                spaceId = spaceId,
                // The format could not be applied, so the note is honestly plain.
                format = NoteFormat.PLAIN.id,
                createdAt = System.currentTimeMillis()
            )
        }

        NoteStore.add(note)
        if (job.mediaIds.isNotEmpty()) {
            MediaStore.linkNoteToMedia(job.mediaIds, note.id)
        }
        Pipeline.done(note.title)
        CaptureLog.add("note filed")
        JobQueue.remove(job.id)
        CaptureLog.add("job ${job.id}: filed as note ${note.id}")
        return true
    }

    /** Bumps the attempt counter and drops the job once it has burned all of its attempts. */
    private fun retryOrDrop(job: Job, error: String) {
        val attempts = JobQueue.bumpAttempts(job.id, error)
        if (attempts >= JobQueue.MAX_ATTEMPTS) {
            JobQueue.remove(job.id)
            CaptureLog.add("job ${job.id}: dropped after $attempts attempts - $error")
            quarantineWav(job, error)
        }
    }

    /** True when a note already owns this audio, so filing it again would duplicate a note. */
    private fun isFiled(wavPath: String): Boolean =
        NoteStore.all().any { it.audioPath == wavPath }

    /**
     * A dropped job's WAV is moved out of `bursts/` so orphan recovery cannot queue it again
     * in a loop. The file is only ever moved, never deleted: it is the only recording of what
     * the user said. Audio a note already points at is left exactly where it is.
     */
    private fun quarantineWav(job: Job, reason: String) {
        val src = File(job.wavPath)
        if (!src.isFile) return
        if (isFiled(src.absolutePath)) {
            CaptureLog.add("job ${job.id}: ${src.name} belongs to a note, left in place")
            return
        }

        val dir = File(filesDir, "bursts_failed")
        if (!dir.isDirectory && !dir.mkdirs()) {
            CaptureLog.add("jobs: cannot create ${dir.name}, ${src.name} left in bursts")
            return
        }

        val dest = uniqueIn(dir, src)
        val moved = if (src.renameTo(dest)) {
            true
        } else runCatching {
            // bursts/ lives on external storage, so the move can cross filesystems. Copy,
            // verify the byte count, and only then remove the original.
            src.copyTo(dest, overwrite = false)
            val whole = dest.length() == src.length()
            if (whole) src.delete() else dest.delete()
            whole
        }.getOrDefault(false)

        if (moved) {
            CaptureLog.add("job ${job.id}: moved ${src.name} to ${dir.name} ($reason)")
        } else {
            CaptureLog.add("job ${job.id}: could not move ${src.name} to ${dir.name}")
        }
    }

    /** Free name inside [dir] for [src], so a second drop never overwrites the first WAV. */
    private fun uniqueIn(dir: File, src: File): File {
        var dest = File(dir, src.name)
        var n = 1
        while (dest.exists()) {
            val base = src.nameWithoutExtension
            val ext = src.extension
            dest = File(dir, if (ext.isBlank()) "$base-$n" else "$base-$n.$ext")
            n++
        }
        return dest
    }

    /**
     * A burst is written to disk before it is queued, so a crash in between leaves a real
     * recording that nothing references. Fold those back into the queue at startup.
     */
    private fun recoverOrphanBursts() {
        val dir = File(getExternalFilesDir(null), "bursts")
        if (!dir.isDirectory) return

        val known = HashSet<String>()
        JobQueue.all().forEach { known.add(File(it.wavPath).absolutePath) }
        NoteStore.all().forEach { note -> note.audioPath?.let { known.add(File(it).absolutePath) } }

        val orphans = dir.listFiles { f ->
            f.isFile && f.extension.equals("wav", ignoreCase = true) && f.length() > 0L
        } ?: return
        if (orphans.isEmpty()) return

        var recovered = 0
        orphans.sortedBy { it.name }.forEach { wav ->
            if (wav.absolutePath in known) return@forEach
            JobQueue.add(
                Job(
                    id = UUID.randomUUID().toString(),
                    wavPath = wav.absolutePath,
                    spaceId = SpaceStore.currentSpaceId,
                    attempts = 0,
                    // The burst's own choice died with its process; the persisted picker is the
                    // closest honest guess.
                    format = Formats.next.id
                )
            )
            CaptureLog.add("recovered orphan ${wav.name}")
            recovered++
        }
        if (recovered > 0) CaptureLog.add("recovered $recovered orphan burst(s)")
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val ACTION_ARM = "com.sievex.ARM"
        const val ACTION_DISARM = "com.sievex.DISARM"
        const val ACTION_START_BURST = "com.sievex.START_BURST"
        const val ACTION_STOP_BURST = "com.sievex.STOP_BURST"
        const val ACTION_KICK = "com.sievex.KICK"
        const val EXTRA_WHY = "why"
        const val EXTRA_MEDIA_IDS = "media_ids"
        const val EXTRA_SPACE_ID = "space_id"

        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 42

        /** One minute: long enough for a transient failure to clear, short enough to feel automatic. */
        private const val RETRY_DELAY_MS = 60_000L

        /** How long a finishing burst waits for the store init before it gives up and defers. */
        private const val STORE_READY_TIMEOUT_MS = 10_000L

        /**
         * One job at a time, independent of the service instance: jobs.json is the source of
         * truth, so a burst keeps draining even if this service is torn down mid-flight.
         */
        private val WORKER: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sievex-jobs").apply { isDaemon = true }
        }

        /** True once the worker has loaded jobs.json, so [kick] can answer without touching disk. */
        @Volatile
        private var queueReady = false

        @Volatile
        var armed = false
            private set

        @Volatile
        var instance: CaptureService? = null
            private set

        @Volatile
        var isRecording = false
            private set

        /**
         * Asks the service to drain the queue. Safe from any thread and from the background:
         * the platform can refuse a foreground start, and that refusal must never surface.
         */
        fun kick(context: Context) {
            // Once jobs.json is loaded, an empty queue is a known answer: do not start a service
            // just to find that out again.
            if (queueReady && JobQueue.peek() == null) return
            runCatching {
                val intent = Intent(context, CaptureService::class.java).setAction(ACTION_KICK)
                context.startForegroundService(intent)
            }.onFailure {
                CaptureLog.add("kick refused - ${it.message}")
            }
        }

        fun send(context: Context, action: String, why: String? = null) {
            val intent = Intent(context, CaptureService::class.java).setAction(action)
            if (why != null) intent.putExtra(EXTRA_WHY, why)
            if (action == ACTION_ARM || action == ACTION_START_BURST) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendBurstWithMedia(
            context: Context,
            mediaIds: List<String>,
            spaceId: String?,
            why: String = "share"
        ) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START_BURST)
                .putExtra(EXTRA_WHY, why)
                .putStringArrayListExtra(EXTRA_MEDIA_IDS, ArrayList(mediaIds))
            if (!spaceId.isNullOrBlank()) intent.putExtra(EXTRA_SPACE_ID, spaceId)
            context.startForegroundService(intent)
        }

        /** Called from the media listener, which runs in the same process. */
        fun trigger(start: Boolean, why: String) {
            val svc = instance
            if (svc == null) {
                CaptureLog.add("trigger dropped, service not running ($why)")
                return
            }
            if (start) svc.startBurst(why) else svc.stopBurst()
        }
    }
}
