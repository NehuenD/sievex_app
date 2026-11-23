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
import java.util.UUID

/** Records one burst to a 16 kHz mono WAV. */
class BurstRecorder(
    private val context: Context,
    private val onComplete: ((File) -> Unit)? = null
) {

    @Volatile
    private var running = false
    @Volatile
    private var cancelled = false
    private var thread: Thread? = null

    @Volatile
    var currentFile: File? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): File? {
        if (running) return currentFile

        val dir = File(context.getExternalFilesDir(null), "bursts").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(4)
        val finalFile = File(dir, "burst-$stamp-$suffix.wav")
        val partFile = File(dir, "burst-$stamp-$suffix.wav.part")

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
        cancelled = false
        currentFile = finalFile

        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val startedAt = System.currentTimeMillis()
            var writer: WavWriter? = null
            try {
                // Record into .wav.part and promote to .wav only after WavWriter.close(), so
                // startup orphan recovery can never pick up a half-written live burst.
                val out = WavWriter(partFile, SAMPLE_RATE)
                writer = out
                val buf = ByteArray(minBuf)
                rec.startRecording()
                CaptureLog.add("burst start -> ${finalFile.name}")
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
                if (cancelled) {
                    runCatching { partFile.delete() }
                    runCatching { finalFile.delete() }
                    CaptureLog.add("burst cancelled (${finalFile.name})")
                    onComplete?.invoke(finalFile)
                } else {
                    if (partFile.isFile) {
                        if (!partFile.renameTo(finalFile)) {
                            runCatching {
                                partFile.copyTo(finalFile, overwrite = true)
                                partFile.delete()
                            }
                        }
                    }
                    CaptureLog.add("burst stop (${finalFile.length() / 1024} KB)")
                    onComplete?.invoke(finalFile)
                }
            }
        }, "sievex-recorder").also { it.start() }

        return finalFile
    }

    fun stop() {
        running = false
        currentFile = null
    }

    fun cancel() {
        cancelled = true
        running = false
        currentFile = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** One burst is one thought; ninety seconds is far more than one thought needs. */
        const val MAX_BURST_MS = 90_000L
    }
}
