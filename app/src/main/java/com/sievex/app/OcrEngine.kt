package com.sievex.app

import android.graphics.ImageDecoder
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline on-device OCR using bundled ML Kit Text Recognition.
 *
 * Blocking: call from [kotlinx.coroutines.Dispatchers.IO] or a background worker thread.
 */
object OcrEngine {

    private const val MAX_OCR_DIMENSION_PX = 2048

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Returns extracted text (possibly empty when no text is present), or null on error. */
    fun extractText(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = max(w, h)
                if (longest > MAX_OCR_DIMENSION_PX && longest > 0) {
                    val scale = MAX_OCR_DIMENSION_PX.toFloat() / longest.toFloat()
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = Tasks.await(recognizer.process(image))
                result.text
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            } finally {
                bitmap.recycle()
            }
        }.getOrElse { e ->
            CaptureLog.add("ocr: failed on ${file.name} - ${e.message}")
            null
        }
    }
}
