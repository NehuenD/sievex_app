package com.sievex.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.sievex.app.CaptureLog
import java.io.File

/**
 * Parakeet TDT 0.6B v3 (int8 ONNX) through sherpa-onnx.
 *
 * Model files live in filesDir/models/parakeet and are imported by the user, so the app still
 * needs no network permission.
 */
object AsrEngine {

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    val FILE_NAMES = listOf(ENCODER, DECODER, JOINER, TOKENS)

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedPath: String? = null

    fun modelDir(filesDir: File): File = File(filesDir, "models/parakeet")

    fun isInstalled(filesDir: File): Boolean {
        val dir = modelDir(filesDir)
        return FILE_NAMES.all { File(dir, it).length() > 0L }
    }

    fun installedBytes(filesDir: File): Long {
        val dir = modelDir(filesDir)
        return FILE_NAMES.sumOf { File(dir, it).length() }
    }

    /**
     * Loads the recognizer if it is not already loaded and reports whether it really works.
     * Public because the service checks it before transcribing: a model that is present but
     * broken must be a held state, not a job failure that burns attempts.
     */
    @Synchronized
    fun ensureLoaded(filesDir: File): Boolean {
        val dir = modelDir(filesDir)
        if (!isInstalled(filesDir)) return false
        if (recognizer != null && loadedPath == dir.absolutePath) return true

        recognizer = null
        loadedPath = null

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, ENCODER).absolutePath,
                    decoder = File(dir, DECODER).absolutePath,
                    joiner = File(dir, JOINER).absolutePath
                ),
                tokens = File(dir, TOKENS).absolutePath,
                modelType = "nemo_transducer",
                // Decoding holds the whole queue, so it gets the big cores: two threads left the
                // rest of the CPU idle for the seconds a burst spends waiting on this.
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )

        return try {
            // The int8 encoder alone is 652 MB, so this is the most expensive call in the app and
            // the one worth measuring: it is the difference between a warm and a cold first burst.
            val started = System.currentTimeMillis()
            recognizer = OfflineRecognizer(null, config)
            loadedPath = dir.absolutePath
            CaptureLog.add("asr: model loaded in ${System.currentTimeMillis() - started} ms")
            true
        } catch (t: Throwable) {
            CaptureLog.add("asr: load failed - ${t.message}")
            recognizer = null
            false
        }
    }

    /**
     * Transcribe one WAV. Returns null when the model is missing or inference throws.
     *
     * Synchronised on the same monitor as [ensureLoaded] and [unload]: a release that nulls the
     * field mid-call would otherwise turn a real burst into a burned attempt, and a later load
     * could build a second 652 MB recognizer while the first is still decoding.
     */
    @Synchronized
    fun transcribe(filesDir: File, wavPath: String): String? {
        if (!ensureLoaded(filesDir)) return null
        val rec = recognizer ?: return null

        return try {
            val started = System.currentTimeMillis()
            val wave = WaveReader.readWave(wavPath)
            val audioSeconds = wave.samples.size.toFloat() / wave.sampleRate

            val stream = rec.createStream()
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()

            val ms = System.currentTimeMillis() - started
            val rtf = if (audioSeconds > 0f) ms / 1000f / audioSeconds else 0f
            CaptureLog.add(
                "asr: %.1fs audio -> %d ms (rtf %.2f)".format(audioSeconds, ms, rtf)
            )
            text
        } catch (t: Throwable) {
            CaptureLog.add("asr: inference failed - ${t.message}")
            null
        }
    }

    @Synchronized
    fun unload() {
        recognizer = null
        loadedPath = null
    }
}
