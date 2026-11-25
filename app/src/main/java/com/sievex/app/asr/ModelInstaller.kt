package com.sievex.app.asr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.ModelDownloadService
import com.sievex.app.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-launch download of the Parakeet model.
 *
 * Four files, HTTPS, every byte checked against a pinned SHA-256. Downloads resume from a partial
 * file, so a dropped connection does not restart 640 MB. After this runs once the app works
 * entirely offline.
 */
object ModelInstaller {

    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

    private data class Spec(val name: String, val bytes: Long, val sha256: String)

    private val FILES = listOf(
        Spec(
            "decoder.int8.onnx", 11_845_274L,
            "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"
        ),
        Spec(
            "joiner.int8.onnx", 6_355_277L,
            "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"
        ),
        Spec(
            "tokens.txt", 93_939L,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
        ),
        Spec(
            "encoder.int8.onnx", 652_282_294L,
            "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"
        )
    )

    private val TOTAL_BYTES = FILES.sumOf { it.bytes }
    val totalMb: Long = TOTAL_BYTES / (1024 * 1024)
    private const val MAX_STALL_RETRIES = 8
    private val RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L, 20_000L, 25_000L, 30_000L)

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var cancelRequested = false

    @Volatile
    internal var verifying = false
        private set

    @Volatile
    internal var retrying = false
        private set

    private class CancelledDownloadException : IOException("Download cancelled")
    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    var installed by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0f)
        private set

    /**
     * Shown by the UI. The initial value is an English fallback because an object has no Context
     * at class-init; AppUi calls refresh(context) before the card is shown, and every later write
     * is resolved from resources.
     */
    var status by mutableStateOf("Speech model not installed")
        private set

    var failed by mutableStateOf(false)
        private set

    fun refresh(context: Context) {
        installed = AsrEngine.isInstalled(context.filesDir)
        when {
            installed -> {
                val mb = AsrEngine.installedBytes(context.filesDir) / (1024 * 1024)
                status = context.getString(R.string.sys_asr_status_installed, mb)
                progress = 1f
            }
            busy -> Unit
            failed -> Unit
            else -> status = context.getString(R.string.sys_asr_status_not_installed)
        }
    }

    fun cancel() {
        if (busy) cancelRequested = true
    }

    /** Removes installed model files cleanly. Must be called from a background IO thread. */
    fun deleteInstalled(context: Context) {
        if (busy) return
        val app = context.applicationContext
        AsrEngine.unload()
        val target = AsrEngine.modelDir(app.filesDir)
        FILES.forEach { File(target, it.name).delete() }
        val temp = File(app.filesDir, "models/download")
        FILES.forEach { File(temp, it.name + ".part").delete() }
        failed = false
        progress = 0f
        refresh(app)
        captureLog("model: deleted speech model")
    }

    fun download(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        val localized = AppLocale.wrap(app)

        busy = true
        failed = false
        cancelRequested = false
        verifying = false
        retrying = false
        progress = existingProgress(app.filesDir)
        status = localized.getString(R.string.sys_model_starting_download)

        if (!ModelDownloadService.start(context, ModelDownloadService.TARGET_ASR)) {
            runFallbackThread(app)
        }
    }

    internal fun finishServiceDownload() {
        verifying = false
        retrying = false
        busy = false
        inFlight.set(false)
    }

    internal fun runFallbackThread(appContext: Context) {
        Thread { executeDownload(appContext, manageInFlight = true) }.start()
    }

    private fun existingProgress(filesDir: File): Float {
        val target = AsrEngine.modelDir(filesDir)
        val temp = File(filesDir, "models/download")
        var have = 0L
        for (spec in FILES) {
            val dest = File(target, spec.name)
            if (dest.isFile && dest.length() == spec.bytes) {
                have += spec.bytes
            } else {
                val part = File(temp, spec.name + ".part")
                if (part.isFile && part.length() in 1L..spec.bytes) {
                    have += part.length()
                }
            }
        }
        return (have.toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
    }

    internal fun executeDownload(
        context: Context,
        manageInFlight: Boolean = true,
        onUpdate: () -> Unit = {}
    ): ModelDownloadService.Outcome {
        val app = context.applicationContext
        val localized = AppLocale.wrap(app)
        val target = AsrEngine.modelDir(app.filesDir).apply { mkdirs() }
        val temp = File(app.filesDir, "models/download").apply { mkdirs() }
        var doneBytes = 0L

        busy = true
        failed = false
        verifying = false
        retrying = false

        return try {
            for (spec in FILES) {
                if (cancelRequested) throw CancelledDownloadException()
                val dest = File(target, spec.name)
                if (dest.length() == spec.bytes && sha256(dest) == spec.sha256) {
                    doneBytes += spec.bytes
                    progress = (doneBytes.toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
                    onUpdate()
                    continue
                }

                val part = File(temp, spec.name + ".part")
                if (part.length() > spec.bytes) {
                    part.delete()
                }
                if (part.length() < spec.bytes) {
                    status = localized.getString(R.string.sys_asr_status_downloading)
                    onUpdate()
                    captureLog("model: ${spec.name} from ${part.length()} bytes")
                    fetchWithRetry(spec, part, doneBytes, localized, onUpdate)
                } else {
                    captureLog("model: ${spec.name} already at ${part.length()} bytes, verifying")
                }

                if (part.length() != spec.bytes) {
                    if (part.length() > spec.bytes) part.delete()
                    throw IOException("${spec.name}: size ${part.length()} != ${spec.bytes}")
                }
                verifying = true
                retrying = false
                status = localized.getString(R.string.sys_asr_status_verifying)
                onUpdate()
                val digest = sha256(part)
                verifying = false
                if (digest != spec.sha256) {
                    part.delete()
                    throw IOException("${spec.name}: checksum mismatch")
                }

                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }

                doneBytes += spec.bytes
                progress = (doneBytes.toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
                onUpdate()
            }

            AsrEngine.unload()
            installed = AsrEngine.isInstalled(app.filesDir)
            status = if (installed) {
                localized.getString(
                    R.string.sys_asr_status_installed,
                    AsrEngine.installedBytes(app.filesDir) / (1024 * 1024)
                )
            } else {
                localized.getString(R.string.sys_asr_status_incomplete)
            }
            progress = if (installed) 1f else progress
            captureLog("model: $status")
            // A job may have been held for this model while it downloaded: drain it now so
            // the note appears without the user touching anything.
            if (installed) {
                CaptureService.kick(app)
                ModelDownloadService.Outcome.SUCCESS
            } else {
                failed = true
                ModelDownloadService.Outcome.FAILED
            }
        } catch (_: CancelledDownloadException) {
            failed = false
            status = localized.getString(R.string.sys_asr_status_not_installed)
            captureLog("model: download cancelled")
            ModelDownloadService.Outcome.CANCELLED
        } catch (t: Throwable) {
            failed = true
            status = localized.getString(R.string.sys_model_download_failed, t.message)
            captureLog("model: download failed - ${t.message}")
            ModelDownloadService.Outcome.FAILED
        } finally {
            verifying = false
            retrying = false
            if (manageInFlight) {
                busy = false
                inFlight.set(false)
            }
        }
    }

    private fun fetchWithRetry(
        spec: Spec,
        part: File,
        doneBytes: Long,
        localized: Context,
        onUpdate: () -> Unit
    ) {
        var stalledAttempts = 0
        while (part.length() < spec.bytes) {
            if (cancelRequested) throw CancelledDownloadException()
            val before = part.length()
            try {
                retrying = false
                fetch(spec, part, doneBytes, onUpdate)
                if (part.length() < spec.bytes) {
                    if (part.length() > before) {
                        stalledAttempts = 0
                    } else {
                        stalledAttempts++
                        if (stalledAttempts > MAX_STALL_RETRIES) {
                            throw IOException("${spec.name}: incomplete transfer at ${part.length()} bytes")
                        }
                    }
                    sleepBeforeRetry(spec, stalledAttempts, part.length(), localized, onUpdate)
                }
            } catch (c: CancelledDownloadException) {
                throw c
            } catch (h: HttpStatusException) {
                val retryable = h.code == 408 || h.code == 429 || h.code >= 500
                if (!retryable) throw h
                stalledAttempts++
                if (stalledAttempts > MAX_STALL_RETRIES) throw h
                sleepBeforeRetry(spec, stalledAttempts, part.length(), localized, onUpdate)
            } catch (io: IOException) {
                if (cancelRequested) throw CancelledDownloadException()
                if (part.length() > before) {
                    stalledAttempts = 0
                } else {
                    stalledAttempts++
                    if (stalledAttempts > MAX_STALL_RETRIES) throw io
                }
                sleepBeforeRetry(spec, stalledAttempts, part.length(), localized, onUpdate)
            }
        }
    }

    private fun sleepBeforeRetry(
        spec: Spec,
        attempt: Int,
        haveBytes: Long,
        localized: Context,
        onUpdate: () -> Unit
    ) {
        val delayMs = RETRY_BACKOFF_MS[(attempt - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
        retrying = true
        status = localized.getString(R.string.sys_model_retrying)
        onUpdate()
        captureLog("model: ${spec.name} interrupted at $haveBytes bytes, retrying in ${delayMs / 1000}s")
        var remaining = delayMs
        while (remaining > 0L) {
            if (cancelRequested) throw CancelledDownloadException()
            val step = remaining.coerceAtMost(250L)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                if (cancelRequested) throw CancelledDownloadException()
            }
            remaining -= step
        }
        retrying = false
        status = localized.getString(R.string.sys_asr_status_downloading)
        onUpdate()
    }

    private fun fetch(
        spec: Spec,
        part: File,
        doneBytes: Long,
        onUpdate: () -> Unit,
        allowReset: Boolean = true
    ) {
        val url = URL("$BASE/${spec.name}")
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("User-Agent", "SievexAI/0.5")
        conn.setRequestProperty("Accept-Encoding", "identity")

        var have = if (part.exists()) part.length() else 0L
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")

        var retryFromZero = false
        try {
            val code = conn.responseCode
            if (code == 416 && allowReset) {
                part.delete()
                retryFromZero = true
            } else {
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw HttpStatusException(code, "HTTP $code for ${spec.name}")
                }

                RandomAccessFile(part, "rw").use { raf ->
                    if (code == HttpURLConnection.HTTP_OK) {
                        // Server ignored the range request; start over.
                        have = 0L
                        raf.setLength(0)
                    } else {
                        raf.seek(have)
                    }

                    var written = have
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelRequested) throw CancelledDownloadException()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            raf.write(buffer, 0, n)
                            written += n
                            val nextProgress = ((doneBytes + written).toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
                            progress = nextProgress
                            val pct = (nextProgress * 100f).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onUpdate()
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (retryFromZero) {
            fetch(spec, part, doneBytes, onUpdate, allowReset = false)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                if (cancelRequested) throw CancelledDownloadException()
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun captureLog(message: String) {
        CaptureLog.add(message)
    }
}
