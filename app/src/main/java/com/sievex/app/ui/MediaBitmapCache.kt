package com.sievex.app.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight in-memory [ImageDecoder] cache for app-private thumbnails, viewer images,
 * and share-sheet previews. Keeps Gradle dependencies zero and avoids decoding on the UI thread.
 */
object MediaBitmapCache {

    private const val MAX_CACHE_ENTRIES = 64

    private val cache = LruCache<String, ImageBitmap>(MAX_CACHE_ENTRIES)

    suspend fun loadFile(file: File, maxDimensionPx: Int): ImageBitmap? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}@$maxDimensionPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key)?.let { return@withContext it }
            runCatching {
                val src = ImageDecoder.createSource(file)
                decodeScaled(src, maxDimensionPx)
            }.getOrNull()?.also { bmp ->
                cache.put(key, bmp)
            }
        }
    }

    suspend fun loadUri(context: Context, uri: Uri, maxDimensionPx: Int): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                decodeScaled(src, maxDimensionPx)
            }.getOrNull()
        }
    }

    private fun decodeScaled(source: ImageDecoder.Source, maxDimensionPx: Int): ImageBitmap {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (maxDimensionPx > 0 && longest > maxDimensionPx && longest > 0) {
                val scale = maxDimensionPx.toFloat() / longest.toFloat()
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
        return bitmap.asImageBitmap()
    }
}
