package com.sievex.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One image filed into a [Space], optionally linked to zero or more [StoredNote]s.
 *
 * [file] and [thumb] are relative paths under `filesDir` (e.g. `media/originals/<sha>.jpg`).
 */
data class MediaItem(
    val id: String,
    val spaceId: String,
    val noteIds: List<Long>,
    val file: String,
    val thumb: String,
    val caption: String,
    val addedAt: Long,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sha256: String,
    val ocrText: String = "",
    val ocrScanned: Boolean = false
)

/**
 * App-private media persistence under `filesDir/media/`.
 *
 * Originals are copied into `filesDir/media/originals/` and deduplicated by SHA-256 so
 * gallery URIs are never retained and re-sharing the same image into a space never duplicates
 * storage or feed entries.
 */
object MediaStore {

    private const val THUMB_MAX_PX = 320
    private val EXT_REGEX = Regex("[a-z0-9]{1,8}")
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private val OCR_WORKER = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sievex-ocr").apply { isDaemon = true }
    }

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val items = mutableListOf<MediaItem>()
    private val inFlightPaths = HashMap<String, Int>()
    @Volatile
    private var rootDir: File? = null
    private var mediaFile: File? = null

    private fun enterInFlight(path: String) {
        inFlightPaths[path] = (inFlightPaths[path] ?: 0) + 1
    }

    private fun leaveInFlight(path: String) {
        val left = (inFlightPaths[path] ?: 0) - 1
        if (left <= 0) inFlightPaths.remove(path) else inFlightPaths[path] = left
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }

    @Volatile
    private var writesBlocked = false

    @Synchronized
    fun init(root: File) {
        if (mediaFile != null) return
        rootDir = root
        val dir = File(root, "media").apply { mkdirs() }
        File(dir, "originals").mkdirs()
        File(dir, "thumbs").mkdirs()
        mediaFile = File(dir, "media.json")
        load()
    }

    @Synchronized
    fun all(): List<MediaItem> = items.toList()

    @Synchronized
    fun byId(id: String): MediaItem? = items.firstOrNull { it.id == id }

    @Synchronized
    fun inSpace(spaceId: String): List<MediaItem> = items.filter { it.spaceId == spaceId }

    @Synchronized
    fun forNote(noteId: Long): List<MediaItem> = items.filter { noteId in it.noteIds }

    @Synchronized
    fun countInSpace(spaceId: String): Int = items.count { it.spaceId == spaceId }

    fun resolveFile(relativePath: String): File? {
        val root = rootDir ?: return null
        if (relativePath.isBlank()) return null
        return File(root, relativePath)
    }

    /**
     * Copies an image from [uri] into app-private storage, generates a ~320 px thumbnail via
     * [ImageDecoder], and records it under [spaceId]. Deduplicates by SHA-256 within [spaceId].
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        spaceId: String,
        caption: String = ""
    ): MediaItem? {
        val root = rootDir ?: return null
        val mediaDir = File(root, "media")
        val originalsDir = File(mediaDir, "originals").apply { mkdirs() }
        val thumbsDir = File(mediaDir, "thumbs").apply { mkdirs() }

        val mime = context.contentResolver.getType(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.matches(EXT_REGEX) }
            ?: "jpg"

        val tmpFile = File(originalsDir, "tmp_${UUID.randomUUID()}.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        totalBytes += read
                    }
                }
                true
            } ?: false
        }.getOrElse { e ->
            CaptureLog.add("media: copy failed - ${e.message}")
            false
        }

        if (!copied || totalBytes <= 0L) {
            tmpFile.delete()
            return null
        }

        val sha256 = toHex(digest.digest())
        val cleanCaption = caption.trim()

        // If this exact image is already in the target space, reuse it without duplicating.
        synchronized(this) {
            val existingInSpace = items.firstOrNull { it.spaceId == spaceId && it.sha256 == sha256 }
            if (existingInSpace != null) {
                tmpFile.delete()
                if (cleanCaption.isNotBlank() && existingInSpace.caption.isBlank()) {
                    val idx = items.indexOfFirst { it.id == existingInSpace.id }
                    if (idx >= 0) {
                        val updated = existingInSpace.copy(caption = cleanCaption)
                        items[idx] = updated
                        save()
                        return updated
                    }
                }
                return existingInSpace
            }
        }

        val relOriginal = "media/originals/$sha256.$ext"
        val relThumb = "media/thumbs/$sha256.jpg"
        val finalOriginal = File(root, relOriginal)
        val finalThumb = File(root, relThumb)

        synchronized(this) {
            enterInFlight(relOriginal)
            enterInFlight(relThumb)
        }
        try {
            if (finalOriginal.exists() && finalOriginal.length() == totalBytes) {
                tmpFile.delete()
            } else {
                if (!tmpFile.renameTo(finalOriginal)) {
                    runCatching { tmpFile.copyTo(finalOriginal, overwrite = true) }
                    tmpFile.delete()
                }
            }

            var width = 0
            var height = 0
            var decoded = false
            if (finalThumb.exists() && finalThumb.length() > 0L) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(finalOriginal.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                    decoded = true
                }
            }
            if (!decoded) {
                decoded = runCatching {
                    val source = ImageDecoder.createSource(finalOriginal)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        width = info.size.width
                        height = info.size.height
                        val longest = max(width, height)
                        if (longest > THUMB_MAX_PX && longest > 0) {
                            val scale = THUMB_MAX_PX.toFloat() / longest.toFloat()
                            val tw = (width * scale).roundToInt().coerceAtLeast(1)
                            val th = (height * scale).roundToInt().coerceAtLeast(1)
                            decoder.setTargetSize(tw, th)
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        if (!finalThumb.exists() || finalThumb.length() == 0L) {
                            val tmpThumb = File(thumbsDir, "tmp_${UUID.randomUUID()}.jpg")
                            FileOutputStream(tmpThumb).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                out.fd.sync()
                            }
                            if (!tmpThumb.renameTo(finalThumb)) {
                                tmpThumb.copyTo(finalThumb, overwrite = true)
                                tmpThumb.delete()
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    true
                }.getOrElse { e ->
                    CaptureLog.add("media: decode failed - ${e.message}")
                    false
                }
            }

            if (!decoded) {
                return null
            }

            val item = MediaItem(
                id = "md_" + UUID.randomUUID().toString().replace("-", "").take(12),
                spaceId = spaceId,
                noteIds = emptyList(),
                file = relOriginal,
                thumb = relThumb,
                caption = cleanCaption,
                addedAt = System.currentTimeMillis(),
                width = width,
                height = height,
                bytes = totalBytes,
                sha256 = sha256,
                ocrText = "",
                ocrScanned = false
            )

            synchronized(this) {
                val existingInSpace = items.firstOrNull { it.spaceId == spaceId && it.sha256 == sha256 }
                if (existingInSpace != null) {
                    if (cleanCaption.isNotBlank() && existingInSpace.caption.isBlank()) {
                        val idx = items.indexOfFirst { it.id == existingInSpace.id }
                        if (idx >= 0) {
                            val updated = existingInSpace.copy(caption = cleanCaption)
                            items[idx] = updated
                            save()
                            return updated
                        }
                    }
                    return existingInSpace
                }
                items.add(0, item)
                save()
            }
            OCR_WORKER.execute { ensureOcr(item.id) }
            CaptureLog.add("media: imported ${item.id} into $spaceId (${width}x${height})")
            return item
        } finally {
            synchronized(this) {
                leaveInFlight(relOriginal)
                leaveInFlight(relThumb)
            }
            deleteFileIfUnreferenced(relOriginal, finalOriginal)
            deleteFileIfUnreferenced(relThumb, finalThumb)
        }
    }

    /** Runs offline OCR on [id] if it has not been scanned yet, persisting the result. */
    fun ensureOcr(id: String): MediaItem? {
        val current = byId(id) ?: return null
        if (current.ocrScanned) return current
        val file = resolveFile(current.file) ?: return current
        val text = OcrEngine.extractText(file) ?: return current
        synchronized(this) {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0) return null
            val updated = items[i].copy(ocrText = text, ocrScanned = true)
            items[i] = updated
            save()
            return updated
        }
    }

    /** Ensures every image in [spaceId] has been OCR-scanned before a Lens reads the space. */
    fun ensureOcrForSpace(spaceId: String) {
        val pending = synchronized(this) {
            items.filter { it.spaceId == spaceId && !it.ocrScanned }
                .mapNotNull { m -> resolveFile(m.file)?.let { f -> m.id to f } }
        }
        if (pending.isEmpty()) return
        val results = ArrayList<Pair<String, String>>(pending.size)
        for ((id, file) in pending) {
            val text = OcrEngine.extractText(file) ?: continue
            results.add(id to text)
        }
        if (results.isEmpty()) return
        synchronized(this) {
            var changed = false
            for ((id, text) in results) {
                val i = items.indexOfFirst { it.id == id }
                if (i >= 0 && !items[i].ocrScanned) {
                    items[i] = items[i].copy(ocrText = text, ocrScanned = true)
                    changed = true
                }
            }
            if (changed) save()
        }
    }

    @Synchronized
    fun updateCaption(id: String, caption: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val clean = caption.trim()
        if (items[i].caption == clean) return
        items[i] = items[i].copy(caption = clean)
        save()
    }

    @Synchronized
    fun setNoteLinks(id: String, noteIds: List<Long>) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val distinct = noteIds.distinct()
        if (items[i].noteIds == distinct) return
        items[i] = items[i].copy(noteIds = distinct)
        save()
    }

    @Synchronized
    fun linkNoteToMedia(mediaIds: List<String>, noteId: Long) {
        if (mediaIds.isEmpty()) return
        var changed = false
        for (id in mediaIds) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0 && noteId !in items[i].noteIds) {
                items[i] = items[i].copy(noteIds = items[i].noteIds + noteId)
                changed = true
            }
        }
        if (changed) {
            save()
            CaptureLog.add("media: linked note $noteId to ${mediaIds.size} item(s)")
        }
    }

    @Synchronized
    fun unlinkNote(noteId: Long) {
        var changed = false
        for (i in items.indices) {
            if (noteId in items[i].noteIds) {
                items[i] = items[i].copy(noteIds = items[i].noteIds - noteId)
                changed = true
            }
        }
        if (changed) save()
    }

    @Synchronized
    fun delete(id: String) {
        val removed = items.filter { it.id == id }
        if (removed.isEmpty()) return
        items.removeAll { it.id == id }
        save()
        cleanupOrphanFiles(removed)
        ReminderStore.cancelForTarget(null, ReminderTargetType.MEDIA, id)
        CaptureLog.add("media: deleted $id")
    }

    /**
     * Deletes every media row, original, and thumbnail belonging to [spaceId].
     * Called by `SpaceStore.delete` so deleting a space removes its images cleanly.
     */
    @Synchronized
    fun deleteForSpace(spaceId: String) {
        val removed = items.filter { it.spaceId == spaceId }
        if (removed.isEmpty()) return
        items.removeAll { it.spaceId == spaceId }
        save()
        removed.forEach { ReminderStore.cancelForTarget(null, ReminderTargetType.MEDIA, it.id) }
        cleanupOrphanFiles(removed)
        CaptureLog.add("media: deleted ${removed.size} item(s) for space $spaceId")
    }

    private fun cleanupOrphanFiles(removed: List<MediaItem>) {
        val root = rootDir ?: return
        for (item in removed) {
            deleteFileIfUnreferenced(item.file, File(root, item.file))
            deleteFileIfUnreferenced(item.thumb, File(root, item.thumb))
        }
    }

    private fun deleteFileIfUnreferenced(relPath: String, target: File) {
        if (relPath.isBlank()) return
        val stillUsed = synchronized(this) {
            relPath in inFlightPaths || items.any { it.file == relPath || it.thumb == relPath }
        }
        if (!stillUsed && target.exists()) {
            target.delete()
        }
    }

    private fun load() {
        val f = mediaFile ?: return
        if (!f.exists()) return
        val parsed = runCatching {
            val arr = JSONArray(f.readText())
            val loaded = mutableListOf<MediaItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val spaceId = o.optString("spaceId")
                val file = o.optString("file")
                if (id.isBlank() || spaceId.isBlank() || file.isBlank()) continue
                val noteIds = mutableListOf<Long>()
                val na = o.optJSONArray("noteIds")
                if (na != null) {
                    for (j in 0 until na.length()) {
                        val nid = na.optLong(j, -1L)
                        if (nid >= 0L) noteIds.add(nid)
                    }
                }
                loaded.add(
                    MediaItem(
                        id = id,
                        spaceId = spaceId,
                        noteIds = noteIds.distinct(),
                        file = file,
                        thumb = o.optString("thumb"),
                        caption = o.optString("caption"),
                        addedAt = o.optLong("addedAt", 0L),
                        width = o.optInt("width", 0),
                        height = o.optInt("height", 0),
                        bytes = o.optLong("bytes", 0L),
                        sha256 = o.optString("sha256"),
                        ocrText = o.optString("ocrText"),
                        ocrScanned = o.optBoolean("ocrScanned", false)
                    )
                )
            }
            loaded
        }

        parsed.onSuccess { loaded ->
            items.clear()
            items.addAll(loaded)
            _version.value++
        }.onFailure { e ->
            CaptureLog.add("media: load failed - ${e.message}")
            quarantineUnreadable(f)
            items.clear()
        }
    }

    private fun quarantineUnreadable(bad: File) {
        val target = File(bad.parentFile, bad.name + ".corrupt-" + System.currentTimeMillis())
        if (bad.renameTo(target)) {
            CaptureLog.add("media: moved unreadable file to ${target.name}")
        } else {
            writesBlocked = true
            CaptureLog.add("media: could not move ${bad.name} aside, saving disabled")
        }
    }

    @Synchronized
    private fun save() {
        val f = mediaFile ?: return
        _version.value++
        if (writesBlocked) {
            CaptureLog.add("media: save skipped, file was left unreadable")
            return
        }
        val snapshot = items.toList()
        StoreIo.executor.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { m ->
                    arr.put(
                        JSONObject().apply {
                            put("id", m.id)
                            put("spaceId", m.spaceId)
                            put("noteIds", JSONArray(m.noteIds))
                            put("file", m.file)
                            put("thumb", m.thumb)
                            put("caption", m.caption)
                            put("addedAt", m.addedAt)
                            put("width", m.width)
                            put("height", m.height)
                            put("bytes", m.bytes)
                            put("sha256", m.sha256)
                            put("ocrText", m.ocrText)
                            put("ocrScanned", m.ocrScanned)
                        }
                    )
                }
                StoreIo.writeAtomic(f, arr.toString())
            }.onFailure { CaptureLog.add("media: save failed - ${it.message}") }
        }
    }
}
