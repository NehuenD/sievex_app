package com.sievex.app.llm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    @Volatile
    private var cancelRequested = false

    private class CancelledDownloadException : IOException("Download cancelled")

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
        if (busy) return
        busy = true
        failed = false
        cancelRequested = false
        progress = 0f
        status = context.getString(R.string.sys_model_starting_download)

        val app = context.applicationContext
        Thread {
            val target = modelFile(app.filesDir)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "$NAME.part")

            try {
                if (part.length() > BYTES) {
                    part.delete()
                }
                if (part.length() < BYTES) {
                    fetch(part)
                }

                if (part.length() != BYTES) {
                    if (part.length() > BYTES) part.delete()
                    throw IOException("size ${part.length()} != $BYTES")
                }
                status = app.getString(R.string.sys_model_verifying)
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
                status = app.getString(R.string.sys_llm_status_ready, BYTES / (1024 * 1024))
                CaptureLog.add("llm: model installed")
                // A burst held by the pipeline can finish now; the queue drains on its own.
                CaptureService.kick(app)
            } catch (_: CancelledDownloadException) {
                failed = false
                status = app.getString(R.string.sys_llm_status_not_installed)
                CaptureLog.add("llm: download cancelled")
            } catch (t: Throwable) {
                failed = true
                status = app.getString(R.string.sys_model_download_failed, t.message)
                CaptureLog.add("llm: download failed - ${t.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun fetch(part: File, allowReset: Boolean = true) {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "SievexAI/0.8")

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
                    throw IOException("HTTP $code")
                }

                RandomAccessFile(part, "rw").use { raf ->
                    if (code == HttpURLConnection.HTTP_OK) {
                        have = 0L
                        raf.setLength(0)
                    } else {
                        raf.seek(have)
                    }

                    var written = have
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(512 * 1024)
                        while (true) {
                            if (cancelRequested) throw CancelledDownloadException()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            raf.write(buffer, 0, n)
                            written += n
                            progress = (written.toFloat() / BYTES).coerceIn(0f, 1f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (retryFromZero) {
            fetch(part, allowReset = false)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
