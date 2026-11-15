package com.sievex.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records one burst to a 16 kHz mono WAV. */
class BurstRecorder(
    private val context: Context,
    private val onComplete: ((File) -> Unit)? = null
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    var currentFile: File? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): File? {
        if (running) return currentFile

        val dir = File(context.getExternalFilesDir(null), "bursts").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "burst-$stamp.wav")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            CaptureLog.add("recorder: bad min buffer $minBuf")
            return null
        }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: Exception) {
            CaptureLog.add("recorder: init failed ${e.message}")
            return null
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            CaptureLog.add("recorder: not initialised")
            return null
        }

        running = true
        currentFile = file

        thread = Thread {
            val startedAt = System.currentTimeMillis()
            var writer: WavWriter? = null
            try {
                // Inside the try: if the writer cannot open, the finally below still has to run so
                // the service is told this burst ended instead of staying "recording" forever.
                val out = WavWriter(file, SAMPLE_RATE)
                writer = out
                val buf = ByteArray(minBuf)
                rec.startRecording()
                CaptureLog.add("burst start -> ${file.name}")
                while (running) {
                    // A pause that never resumes must not record for minutes and then hand an
                    // unbounded file to the decoder. The cap ends the burst like any other stop:
                    // the finally block closes the WAV and still queues it below.
                    if (System.currentTimeMillis() - startedAt >= MAX_BURST_MS) {
                        running = false
                        CaptureLog.add("recorder: ${MAX_BURST_MS / 1000}s cap reached")
                        break
                    }
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> out.write(buf, n)
                        n < 0 -> {
                            CaptureLog.add("recorder: read error $n")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                CaptureLog.add("recorder: ${e.message}")
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
                writer?.close()
                CaptureLog.add("burst stop (${file.length() / 1024} KB)")
                onComplete?.invoke(file)
            }
        }.also { it.start() }

        return file
    }

    fun stop() {
        running = false
        currentFile = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** One burst is one thought; ninety seconds is far more than one thought needs. */
        const val MAX_BURST_MS = 90_000L
    }
}
