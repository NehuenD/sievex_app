package com.sievex.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.sievex.app.CaptureLog
import java.io.File
import kotlin.math.sqrt

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

    private val EXPECTED_BYTES = mapOf(
        DECODER to 11_845_274L,
        JOINER to 6_355_277L,
        TOKENS to 93_939L,
        ENCODER to 652_282_294L
    )

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedPath: String? = null

    fun modelDir(filesDir: File): File = File(filesDir, "models/parakeet")

    fun isInstalled(filesDir: File): Boolean {
        val dir = modelDir(filesDir)
        return FILE_NAMES.all { name ->
            val f = File(dir, name)
            val expected = EXPECTED_BYTES[name] ?: 1L
            f.isFile && f.length() == expected
        }
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

        runCatching { recognizer?.release() }
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
            val samples = wave.samples
            val sampleRate = wave.sampleRate
            val rawSeconds = if (sampleRate > 0) samples.size.toFloat() / sampleRate else 0f

            val segments = extractSpeechSegments(samples, sampleRate, aggressive = false)
            var usedFallback = false
            var decodedSamples = 0
            var segmentCount = segments.size

            val parts = mutableListOf<String>()
            for (range in segments) {
                val len = range.last - range.first
                if (len <= 0) continue
                decodedSamples += len
                val chunk = samples.copyOfRange(range.first, range.last)
                val piece = decodeChunk(rec, chunk, sampleRate)
                if (piece.isNotBlank()) parts.add(piece)
            }

            // Fallback: if primary segmentation returned blank (e.g. quiet speech after long
            // leading silence or a noisy click), retry with aggressive segmentation or bounded
            // 20s windows starting from the first non-silent 1s window.
            if (parts.isEmpty() && samples.isNotEmpty() && sampleRate > 0) {
                usedFallback = true
                val fallbackSegments = extractSpeechSegments(samples, sampleRate, aggressive = true)
                    .ifEmpty { nonSilentWindows(samples, sampleRate) }
                segmentCount = fallbackSegments.size
                decodedSamples = 0
                for (range in fallbackSegments) {
                    val len = range.last - range.first
                    if (len <= 0) continue
                    decodedSamples += len
                    val chunk = samples.copyOfRange(range.first, range.last)
                    val piece = decodeChunk(rec, chunk, sampleRate)
                    if (piece.isNotBlank()) parts.add(piece)
                }
            }

            val text = parts.joinToString(" ").trim()
            val audioSeconds = if (sampleRate > 0) decodedSamples.toFloat() / sampleRate else 0f
            val ms = System.currentTimeMillis() - started
            val rtf = if (audioSeconds > 0f) ms / 1000f / audioSeconds else 0f
            val segLabel = if (segmentCount == 1) "seg" else "segs"
            val fallbackTag = if (usedFallback) ", fallback" else ""
            CaptureLog.add(
                "asr: %.1fs (%.1fs raw, %d %s%s) -> %d ms (rtf %.2f)".format(
                    audioSeconds,
                    rawSeconds,
                    segmentCount,
                    segLabel,
                    fallbackTag,
                    ms,
                    rtf
                )
            )
            text
        } catch (t: Throwable) {
            CaptureLog.add("asr: inference failed - ${t.message}")
            null
        }
    }

    private fun decodeChunk(rec: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun unload() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedPath = null
    }

    /**
     * Extracts bounded speech ranges from 20 ms RMS frames so leading/internal silence and brief
     * mic-open pops cannot skew per-feature normalization or trigger Parakeet TDT duration skips.
     */
    private fun extractSpeechSegments(
        samples: FloatArray,
        sampleRate: Int,
        aggressive: Boolean
    ): List<IntRange> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        val frameSize = (sampleRate / 50).coerceAtLeast(1)
        val nFrames = samples.size / frameSize
        if (nFrames <= 4) return emptyList()

        val rms = FloatArray(nFrames)
        var peakRms = 0f
        var positiveCount = 0
        for (f in 0 until nFrames) {
            val base = f * frameSize
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = samples[base + i]
                sumSq += s * s
            }
            val r = sqrt(sumSq / frameSize).toFloat()
            rms[f] = r
            if (r > 0f) positiveCount++
            if (r > peakRms) peakRms = r
        }
        if (peakRms <= 1e-5f) return emptyList()

        val noiseFloor = if (positiveCount > 0) {
            val nonZero = FloatArray(positiveCount)
            var idx = 0
            for (f in 0 until nFrames) {
                if (rms[f] > 0f) nonZero[idx++] = rms[f]
            }
            nonZero.sort()
            val p20 = (nonZero.size * 0.20f).toInt().coerceIn(0, nonZero.lastIndex)
            nonZero[p20].coerceIn(0.0015f, 0.025f)
        } else {
            0.0015f
        }

        val threshold = if (aggressive) {
            maxOf(noiseFloor * 1.8f, peakRms * 0.035f, 0.003f).coerceAtMost(peakRms * 0.35f)
        } else {
            maxOf(noiseFloor * 3.0f, peakRms * 0.08f, 0.006f).coerceAtMost(peakRms * 0.5f)
        }

        // Sustained-speech onset filter over a 6-frame (120 ms) window to ignore clicks/taps.
        val windowSize = 6
        val minActiveInWindow = if (aggressive) 2 else 4
        val active = BooleanArray(nFrames)
        for (f in 0 until nFrames) {
            val wEnd = (f + windowSize).coerceAtMost(nFrames)
            var hits = 0
            for (k in f until wEnd) {
                if (rms[k] >= threshold) hits++
            }
            if (hits >= minActiveInWindow) {
                for (k in f until wEnd) {
                    if (rms[k] >= threshold) active[k] = true
                }
            }
        }

        // Group contiguous active frames into raw frame ranges.
        val rawSegments = mutableListOf<IntRange>()
        var segStart = -1
        for (f in 0 until nFrames) {
            if (active[f]) {
                if (segStart < 0) segStart = f
            } else if (segStart >= 0) {
                rawSegments.add(segStart until f)
                segStart = -1
            }
        }
        if (segStart >= 0) rawSegments.add(segStart until nFrames)

        // Drop tiny impulse blips in primary pass.
        val minFrames = if (aggressive) 2 else 4
        val filtered = rawSegments.filter { (it.last - it.first + 1) >= minFrames }
        if (filtered.isEmpty()) return emptyList()

        // Merge segments separated by < 1.5s (75 frames of 20 ms) of silence.
        val mergeGapFrames = 75
        val mergedFrames = mutableListOf<IntRange>()
        var curStart = filtered[0].first
        var curEnd = filtered[0].last
        for (i in 1 until filtered.size) {
            val next = filtered[i]
            if (next.first - curEnd - 1 < mergeGapFrames) {
                curEnd = next.last
            } else {
                mergedFrames.add(curStart..curEnd)
                curStart = next.first
                curEnd = next.last
            }
        }
        mergedFrames.add(curStart..curEnd)

        // Expand by 200 ms leading and 250 ms trailing padding (in samples) and merge overlaps.
        val leadPadSamples = (sampleRate / 5) // 200 ms
        val trailPadSamples = (sampleRate / 4) // 250 ms
        val padded = mutableListOf<IntRange>()
        for (fr in mergedFrames) {
            val sStart = (fr.first * frameSize - leadPadSamples).coerceAtLeast(0)
            val sEnd = ((fr.last + 1) * frameSize + trailPadSamples).coerceAtMost(samples.size)
            if (padded.isNotEmpty() && sStart <= padded.last().last) {
                val prev = padded.removeAt(padded.lastIndex)
                padded.add(prev.first..maxOf(prev.last, sEnd))
            } else {
                padded.add(sStart..sEnd)
            }
        }

        // Split any segment > 30s at the lowest-energy frame within ±3s of the 30s mark.
        val maxSegSamples = 30 * sampleRate
        val searchRadiusFrames = 3 * 50 // ±3s in 20 ms frames
        val bounded = mutableListOf<IntRange>()
        for (range in padded) {
            var pos = range.first
            val end = range.last
            while (end - pos > maxSegSamples) {
                val targetFrame = (pos + maxSegSamples) / frameSize
                val lo = (targetFrame - searchRadiusFrames).coerceAtLeast(pos / frameSize + 1)
                val hi = (targetFrame + searchRadiusFrames).coerceAtMost(end / frameSize - 1)
                var bestFrame = targetFrame.coerceIn(lo, hi)
                var bestRms = Float.MAX_VALUE
                for (f in lo..hi) {
                    if (rms[f] < bestRms) {
                        bestRms = rms[f]
                        bestFrame = f
                    }
                }
                val splitAt = (bestFrame * frameSize).coerceIn(pos + 1, end - 1)
                bounded.add(pos..splitAt)
                pos = splitAt
            }
            if (end > pos) bounded.add(pos..end)
        }
        return bounded
    }

    /**
     * Last-resort windowing when RMS segmentation found no clear speech: finds the first
     * non-silent 1s window and slices the remainder into bounded 20s chunks.
     */
    private fun nonSilentWindows(samples: FloatArray, sampleRate: Int): List<IntRange> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        val oneSec = sampleRate
        var startSample = 0
        for (offset in samples.indices step oneSec) {
            val end = (offset + oneSec).coerceAtMost(samples.size)
            var sumSq = 0.0
            for (i in offset until end) {
                val s = samples[i]
                sumSq += s * s
            }
            val r = sqrt(sumSq / (end - offset)).toFloat()
            if (r >= 0.001f) {
                startSample = (offset - sampleRate / 4).coerceAtLeast(0)
                break
            }
        }
        val windowSamples = 20 * sampleRate
        val out = mutableListOf<IntRange>()
        var pos = startSample
        while (pos < samples.size) {
            val end = (pos + windowSamples).coerceAtMost(samples.size)
            out.add(pos..end)
            pos = end
        }
        return out
    }
}
