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

    private const val MAX_CACHE_KB = 24 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width.toLong() * value.height.toLong() * 4L) / 1024L)
                .toInt()
                .coerceAtLeast(1)
        }
    }

    @Volatile
    private var lastViewer: Pair<String, ImageBitmap>? = null

    fun peekFile(file: File, maxDimensionPx: Int): ImageBitmap? =
        cache.get("${file.absolutePath}@$maxDimensionPx")

    suspend fun loadFile(file: File, maxDimensionPx: Int): ImageBitmap? {
        val key = "${file.absolutePath}@$maxDimensionPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            cache.get(key)?.let { return@withContext it }
            runCatching {
                val src = ImageDecoder.createSource(file)
                decodeScaled(src, maxDimensionPx)
            }.getOrNull()?.also { bmp ->
                cache.put(key, bmp)
            }
        }
    }

    /** Loads a full-size viewer image without evicting card thumbnails from the LRU cache. */
    suspend fun loadViewerFile(file: File, maxDimensionPx: Int): ImageBitmap? {
        val key = "${file.absolutePath}@$maxDimensionPx"
        lastViewer?.let { (cachedKey, bmp) -> if (cachedKey == key) return bmp }
        return withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            lastViewer?.let { (cachedKey, bmp) -> if (cachedKey == key) return@withContext bmp }
            runCatching {
                val src = ImageDecoder.createSource(file)
                decodeScaled(src, maxDimensionPx)
            }.getOrNull()?.also { bmp ->
                lastViewer = key to bmp
            }
        }
    }

    fun clear() {
        cache.evictAll()
        lastViewer = null
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
            if (maxDimensionPx in 1..480) {
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
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
