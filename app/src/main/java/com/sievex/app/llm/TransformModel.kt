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
    private const val BYTES = 1_099_627_808L
    private const val SHA256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4"

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

    fun refresh(context: Context) {
        val f = modelFile(context.filesDir)
        installed = f.length() == BYTES
        if (installed) {
            status = context.getString(R.string.sys_llm_status_ready, BYTES / (1024 * 1024))
            progress = 1f
        } else if (!busy && !failed) {
            status = context.getString(R.string.sys_llm_status_not_installed)
        }
    }

    fun download(context: Context) {
        if (busy) return
        busy = true
        failed = false
        progress = 0f
        status = context.getString(R.string.sys_model_starting_download)

        val app = context.applicationContext
        Thread {
            val target = modelFile(app.filesDir)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "$NAME.part")

            try {
                fetch(part)

                if (part.length() != BYTES) {
                    throw IOException("size ${part.length()} != $BYTES")
                }
                status = app.getString(R.string.sys_model_verifying)
                if (sha256(part) != SHA256) {
                    part.delete()
                    throw IOException("checksum mismatch")
                }

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
            } catch (t: Throwable) {
                failed = true
                status = app.getString(R.string.sys_model_download_failed, t.message)
                CaptureLog.add("llm: download failed - ${t.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun fetch(part: File) {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "SievexAI/0.8")

        var have = if (part.exists()) part.length() else 0L
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }

            val raf = RandomAccessFile(part, "rw")
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
                    val n = input.read(buffer)
                    if (n <= 0) break
                    raf.write(buffer, 0, n)
                    written += n
                    progress = (written.toFloat() / BYTES).coerceIn(0f, 1f)
                }
            }
            raf.close()
        } finally {
            conn.disconnect()
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
