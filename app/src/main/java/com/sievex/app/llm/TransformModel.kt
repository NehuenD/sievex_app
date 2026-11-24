package com.sievex.app.llm

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
 * LFM3-1.7B Q4_K_M, downloaded once and pinned by checksum.
 * Same contract as the ASR downloader: resume on failure, verify before install.
 */
object TransformModel {

    private const val NAME = "LFM3-1.7B-Q4_K_M.gguf"
    private const val SOURCE =
        "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/main/LFM3-1.7B-Q4_K_M.gguf"
    const val BYTES = 1_099_627_808L
    val totalMb: Long = BYTES / (1024 * 1024)
    private const val SHA256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4"
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
    var status by mutableStateOf("Transform model not installed")
        private set
    var failed by mutableStateOf(false)
        private set

    fun modelFile(filesDir: File): File = File(File(filesDir, "models/llm"), NAME)

    fun isInstalled(filesDir: File): Boolean {
        val f = modelFile(filesDir)
        return f.isFile && f.length() == BYTES
    }

    fun refresh(context: Context) {
        installed = isInstalled(context.filesDir)
        if (installed) {
            status = context.getString(R.string.sys_llm_status_ready, BYTES / (1024 * 1024))
            progress = 1f
        } else if (!busy && !failed) {
            status = context.getString(R.string.sys_llm_status_not_installed)
        }
    }

    fun cancel() {
        if (busy) cancelRequested = true
    }

    /** Removes installed LLM weights cleanly. Must be called from a background IO thread. */
    fun deleteInstalled(context: Context) {
        if (busy) return
        val app = context.applicationContext
        LlmGate.exclusive { LlamaBridge.nativeUnload() }
        val target = modelFile(app.filesDir)
        target.delete()
        File(target.parentFile, "$NAME.part").delete()
        failed = false
        progress = 0f
        refresh(app)
        CaptureLog.add("llm: deleted model")
    }

    fun download(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        val localized = AppLocale.wrap(app)
        val part = File(modelFile(app.filesDir).parentFile, "$NAME.part")
        val existing = if (part.isFile && part.length() in 1L..BYTES) part.length() else 0L

        busy = true
        failed = false
        cancelRequested = false
        verifying = false
        retrying = false
        progress = (existing.toFloat() / BYTES).coerceIn(0f, 1f)
        status = localized.getString(R.string.sys_model_starting_download)

        if (!ModelDownloadService.start(context, ModelDownloadService.TARGET_LLM)) {
            runFallbackThread(app)
        }
    }

    internal fun abortBeforeStart() {
        verifying = false
        retrying = false
        busy = false
        inFlight.set(false)
    }

    internal fun runFallbackThread(appContext: Context) {
        Thread { executeDownload(appContext) }.start()
    }

    internal fun executeDownload(
        context: Context,
        onUpdate: () -> Unit = {}
    ): ModelDownloadService.Outcome {
        val app = context.applicationContext
        val localized = AppLocale.wrap(app)
        val target = modelFile(app.filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$NAME.part")

        busy = true
        failed = false
        verifying = false
        retrying = false

        return try {
            if (part.length() > BYTES) {
                part.delete()
            }
            if (part.length() < BYTES) {
                fetchWithRetry(part, localized, onUpdate)
            }

            if (part.length() != BYTES) {
                if (part.length() > BYTES) part.delete()
                throw IOException("size ${part.length()} != $BYTES")
            }
            verifying = true
            retrying = false
            status = localized.getString(R.string.sys_model_verifying)
            onUpdate()
            if (sha256(part) != SHA256) {
                part.delete()
                throw IOException("checksum mismatch")
            }

            LlmGate.exclusive { LlamaBridge.nativeUnload() }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }

            installed = true
            progress = 1f
            status = localized.getString(R.string.sys_llm_status_ready, BYTES / (1024 * 1024))
            CaptureLog.add("llm: model installed")
            // A burst held by the pipeline can finish now; the queue drains on its own.
            CaptureService.kick(app)
            ModelDownloadService.Outcome.SUCCESS
        } catch (_: CancelledDownloadException) {
            failed = false
            status = localized.getString(R.string.sys_llm_status_not_installed)
            CaptureLog.add("llm: download cancelled")
            ModelDownloadService.Outcome.CANCELLED
        } catch (t: Throwable) {
            failed = true
            status = localized.getString(R.string.sys_model_download_failed, t.message)
            CaptureLog.add("llm: download failed - ${t.message}")
            ModelDownloadService.Outcome.FAILED
        } finally {
            verifying = false
            retrying = false
            busy = false
            inFlight.set(false)
        }
    }

    private fun fetchWithRetry(part: File, localized: Context, onUpdate: () -> Unit) {
        var stalledAttempts = 0
        while (part.length() < BYTES) {
            if (cancelRequested) throw CancelledDownloadException()
            val before = part.length()
            try {
                retrying = false
                fetch(part, localized, onUpdate)
            } catch (c: CancelledDownloadException) {
                throw c
            } catch (h: HttpStatusException) {
                val retryable = h.code == 408 || h.code == 429 || h.code >= 500
                if (!retryable) throw h
                stalledAttempts++
                if (stalledAttempts > MAX_STALL_RETRIES) throw h
                sleepBeforeRetry(stalledAttempts, part.length(), localized, onUpdate)
            } catch (io: IOException) {
                if (cancelRequested) throw CancelledDownloadException()
                if (part.length() > before) {
                    stalledAttempts = 0
                } else {
                    stalledAttempts++
                    if (stalledAttempts > MAX_STALL_RETRIES) throw io
                }
                sleepBeforeRetry(stalledAttempts, part.length(), localized, onUpdate)
            }
        }
    }

    private fun sleepBeforeRetry(attempt: Int, haveBytes: Long, localized: Context, onUpdate: () -> Unit) {
        val delayMs = RETRY_BACKOFF_MS[(attempt - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
        retrying = true
        status = localized.getString(R.string.sys_model_retrying)
        onUpdate()
        CaptureLog.add("llm: connection interrupted at $haveBytes bytes, retrying in ${delayMs / 1000}s")
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
    }

    private fun fetch(part: File, localized: Context, onUpdate: () -> Unit, allowReset: Boolean = true) {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "SievexAI/0.8")
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
                    throw HttpStatusException(code, "HTTP $code")
                }

                RandomAccessFile(part, "rw").use { raf ->
                    if (code == HttpURLConnection.HTTP_OK) {
                        have = 0L
                        raf.setLength(0)
                    } else {
                        raf.seek(have)
                    }

                    var written = have
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(512 * 1024)
                        while (true) {
                            if (cancelRequested) throw CancelledDownloadException()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            raf.write(buffer, 0, n)
                            written += n
                            val nextProgress = (written.toFloat() / BYTES).coerceIn(0f, 1f)
                            progress = nextProgress
                            val pct = (nextProgress * 100f).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                status = localized.getString(R.string.sys_llm_status_downloading, pct)
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
            fetch(part, localized, onUpdate, allowReset = false)
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
}
