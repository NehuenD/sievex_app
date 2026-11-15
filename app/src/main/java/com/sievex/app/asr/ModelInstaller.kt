package com.sievex.app.asr

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

    fun download(context: Context) {
        if (busy) return
        busy = true
        failed = false
        progress = 0f
        status = context.getString(R.string.sys_model_starting_download)

        val app = context.applicationContext
        Thread {
            val target = AsrEngine.modelDir(app.filesDir).apply { mkdirs() }
            val temp = File(app.filesDir, "models/download").apply { mkdirs() }
            var doneBytes = 0L

            try {
                for (spec in FILES) {
                    val dest = File(target, spec.name)
                    if (dest.length() == spec.bytes && sha256(dest) == spec.sha256) {
                        doneBytes += spec.bytes
                        progress = (doneBytes.toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
                        continue
                    }

                    val part = File(temp, spec.name + ".part")
                    status = app.getString(R.string.sys_asr_status_downloading, spec.name)
                    captureLog("model: ${spec.name} from ${part.length()} bytes")

                    fetch(spec, part, doneBytes)

                    if (part.length() != spec.bytes) {
                        throw IOException("${spec.name}: size ${part.length()} != ${spec.bytes}")
                    }
                    status = app.getString(R.string.sys_asr_status_verifying, spec.name)
                    val digest = sha256(part)
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
                }

                AsrEngine.unload()
                installed = AsrEngine.isInstalled(app.filesDir)
                status = if (installed) {
                    app.getString(
                        R.string.sys_asr_status_installed,
                        AsrEngine.installedBytes(app.filesDir) / (1024 * 1024)
                    )
                } else {
                    app.getString(R.string.sys_asr_status_incomplete)
                }
                progress = if (installed) 1f else progress
                captureLog("model: $status")
                // A job may have been held for this model while it downloaded: drain it now so
                // the note appears without the user touching anything.
                if (installed) CaptureService.kick(app)
            } catch (t: Throwable) {
                failed = true
                status = app.getString(R.string.sys_model_download_failed, t.message)
                captureLog("model: download failed - ${t.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun fetch(spec: Spec, part: File, doneBytes: Long) {
        val url = URL("$BASE/${spec.name}")
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("User-Agent", "SievexAI/0.5")

        var have = if (part.exists()) part.length() else 0L
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code for ${spec.name}")
            }

            val raf = RandomAccessFile(part, "rw")
            if (code == HttpURLConnection.HTTP_OK) {
                // Server ignored the range request; start over.
                have = 0L
                raf.setLength(0)
            } else {
                raf.seek(have)
            }

            var written = have
            conn.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    raf.write(buffer, 0, n)
                    written += n
                    progress = ((doneBytes + written).toFloat() / TOTAL_BYTES).coerceIn(0f, 1f)
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

    private fun captureLog(message: String) {
        CaptureLog.add(message)
    }
}
