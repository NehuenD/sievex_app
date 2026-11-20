package com.sievex.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class StoredNote(
    val id: Long,
    val title: String,
    val oneLine: String,
    val mode: String,
    val whenText: String,
    val verbatim: String,
    val why: String,
    val question: String,
    val tags: List<String>,
    val audioPath: String?,
    /** The space this note is filed under, or null for "no space". Never changes other fields. */
    val spaceId: String? = null,
    /**
     * When the user last edited this note by hand, or null if they never have. Only
     * [NoteStore.update] may set it, so its presence means "this note is the user's words now",
     * which is what the editor badge reads.
     */
    val editedAt: Long? = null,
    /** How the note was shaped; see [NoteFormat]. Defaults keep older files readable. */
    val format: String = NoteFormat.NOTE.id,
    /** The bullet or step list a LIST/STEPS note carries; empty for every other format. */
    val items: List<String> = emptyList(),
    /** Epoch millis when the note was created; migrated descending for legacy notes. */
    val createdAt: Long = 0L
)

/** Flat JSON persistence. Enough until the Markdown filing layer lands. */
object NoteStore {

    private val notes = mutableListOf<StoredNote>()
    private var file: File? = null

    /**
     * Note ids, seeded past the highest id already on disk. Capture timestamps are not unique
     * enough (two bursts can land in the same millisecond, and a clock can move backwards).
     */
    private val ids = AtomicLong(0)

    /**
     * Set when a bad file could not be moved aside. Saving would then overwrite the only copy
     * of the user's notes with an empty array, so writes stay off until the process restarts.
     */
    @Volatile
    private var writesBlocked = false

    fun nextId(): Long = ids.incrementAndGet()

    fun init(root: File) {
        synchronized(this) {
            if (file != null) return
            file = File(root, "notes.json")
            load()
        }
        // Same root, same process: whoever sets notes up also gets spaces, lenses and the job
        // queue. Deliberately outside the lock above so the stores never lock each other backwards.
        // Every init is idempotent, so a caller that inits one of them directly is harmless.
        SpaceStore.init(root)
        LensStore.init(root)
        JobQueue.init(root)
        MediaStore.init(root)
        // Dangling space links can only be detected once spaces.json is in memory, so this is
        // after SpaceStore.init and never inside load().
        repairSpaceLinks()
    }

    @Synchronized
    fun all(): List<StoredNote> = notes.toList()

    @Synchronized
    fun byId(noteId: Long): StoredNote? = notes.firstOrNull { it.id == noteId }

    @Synchronized
    fun inSpace(spaceId: String): List<StoredNote> = notes.filter { it.spaceId == spaceId }

    /**
     * Files one note under a space, or detaches it with null. Only `spaceId` is written;
     * every other field is copied through untouched.
     */
    @Synchronized
    fun assignSpace(noteId: Long, spaceId: String?) {
        val i = notes.indexOfFirst { it.id == noteId }
        if (i < 0) {
            CaptureLog.add("notes: assignSpace, unknown note $noteId")
            return
        }
        if (notes[i].spaceId == spaceId) return
        notes[i] = notes[i].copy(spaceId = spaceId)
        save()
    }

    /**
     * Detaches every note filed under a space in one pass and one save. Called by
     * `SpaceStore.delete` before the space itself is removed, so a crash cannot leave
     * notes pointing at a space that no longer exists.
     */
    @Synchronized
    fun clearSpace(spaceId: String) {
        var detached = 0
        for (i in notes.indices) {
            if (notes[i].spaceId == spaceId) {
                notes[i] = notes[i].copy(spaceId = null)
                detached++
            }
        }
        if (detached == 0) return
        save()
        CaptureLog.add("notes: detached $detached note(s) from $spaceId")
    }

    @Synchronized
    fun add(note: StoredNote) {
        val stamped = if (note.createdAt > 0L) note else note.copy(createdAt = System.currentTimeMillis())
        notes.add(0, stamped)
        save()
    }

    /**
     * Replaces one note with a user-edited copy of itself and stamps [StoredNote.editedAt].
     *
     * This is the **only** writer of note content: capture, filing and the lens code never call
     * it. [StoredNote.id] and [StoredNote.audioPath] are not content, so they always keep the
     * stored value — an edit can never detach a recording or rename a note out of existence. The
     * space link is likewise owned by [assignSpace] only, so a stale editor cannot undo a move.
     * Returns the note as saved, or null when [note]'s id is not in the store.
     */
    @Synchronized
    fun update(note: StoredNote): StoredNote? = write(note, markEdited = true)

    /**
     * Replaces the AI-shaped content of a note without pretending the user edited it.
     *
     * Used by reanalysis: the title, summary, insight and tags are the model's work, so stamping
     * [StoredNote.editedAt] would light up the "your words now" badge for text nobody wrote. The
     * stored stamp (and the audio, the space and the id) is kept as it was.
     */
    fun replaceWithAi(note: StoredNote): StoredNote? = write(note, markEdited = false)

    /**
     * The one writer of note content. Synchronised because reanalysis runs on an IO thread while
     * the UI poll reads [all] and the queue worker may [add]: without the monitor, `save()` could
     * iterate the list while it is being replaced.
     */
    @Synchronized
    private fun write(note: StoredNote, markEdited: Boolean): StoredNote? {        val i = notes.indexOfFirst { it.id == note.id }
        if (i < 0) {
            CaptureLog.add("notes: update, unknown note ${note.id}")
            return null
        }
        val existing = notes[i]
        val edited = note.copy(
            audioPath = existing.audioPath,
            spaceId = existing.spaceId,
            editedAt = if (markEdited) System.currentTimeMillis() else existing.editedAt,
            createdAt = existing.createdAt
        )
        notes[i] = edited
        save()
        CaptureLog.add("notes: edited ${edited.id}")
        return edited
    }

    /**
     * A space id on disk that no longer exists is the same as no space, e.g. after a crash
     * between `clearSpace` and the space removal. Repairs silently, one save.
     */
    private fun repairSpaceLinks() {
        if (SpaceStore.loadBroken()) {
            // An unreadable spaces.json looks exactly like "every space is gone"; repairing
            // here would strip every note's space because of a broken read.
            CaptureLog.add("notes: skipping space repair, spaces.json did not load")
            return
        }
        val live = SpaceStore.all().map { it.id }.toSet()
        synchronized(this) {
            var repaired = 0
            for (i in notes.indices) {
                val spaceId = notes[i].spaceId ?: continue
                if (spaceId !in live) {
                    notes[i] = notes[i].copy(spaceId = null)
                    repaired++
                }
            }
            if (repaired == 0) return
            save()
            CaptureLog.add("notes: unassigned $repaired note(s) from missing space(s)")
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return

        // Parse into a scratch list: a failure half-way through must not leave a partial
        // in-memory list that the next save would persist over the real one.
        val parsed = runCatching {
            val arr = JSONArray(f.readText())
            val loaded = mutableListOf<StoredNote>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tags = mutableListOf<String>()
                val ta = o.optJSONArray("tags")
                if (ta != null) for (j in 0 until ta.length()) tags.add(ta.getString(j))
                val items = mutableListOf<String>()
                val ia = o.optJSONArray("items")
                if (ia != null) for (j in 0 until ia.length()) items.add(ia.getString(j))
                loaded.add(
                    StoredNote(
                        id = o.optLong("id"),
                        title = o.optString("title"),
                        oneLine = o.optString("oneLine"),
                        mode = o.optString("mode", "Insight"),
                        whenText = o.optString("whenText"),
                        verbatim = o.optString("verbatim"),
                        why = o.optString("why"),
                        question = o.optString("question"),
                        tags = tags,
                        audioPath = o.optString("audioPath").ifBlank { null },
                        spaceId = o.optString("spaceId").ifBlank { null },
                        // Files written before editing existed have no key at all; 0 is the
                        // on-disk spelling of "never edited", never a real timestamp.
                        editedAt = o.optLong("editedAt", 0L).takeIf { it > 0L },
                        // Every note written before formats existed is a regular note.
                        format = o.optString("format", NoteFormat.NOTE.id)
                            .takeIf { NoteFormat.entries.any { f -> f.id == it } }
                            ?: NoteFormat.NOTE.id,
                        items = items,
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            loaded
        }

        parsed.onSuccess { loaded ->
            var migrated = false
            if (loaded.any { it.createdAt <= 0L }) {
                val base = loaded.filter { it.createdAt > 0L }.minOfOrNull { it.createdAt }?.minus(1000L)
                    ?: System.currentTimeMillis()
                for (i in loaded.indices) {
                    if (loaded[i].createdAt <= 0L) {
                        loaded[i] = loaded[i].copy(createdAt = (base - i * 1000L).coerceAtLeast(1L))
                        migrated = true
                    }
                }
            }
            notes.clear()
            notes.addAll(loaded)
            ids.set(loaded.maxOfOrNull { it.id } ?: 0L)
            if (migrated) save()
        }.onFailure { e ->
            CaptureLog.add("notes: load failed - ${e.message}")
            quarantineUnreadable(f)
            notes.clear()
            ids.set(0L)
        }
    }

    /** Never let an unreadable file be silently replaced by the next save's empty array. */
    private fun quarantineUnreadable(bad: File) {
        val target = File(bad.parentFile, bad.name + ".corrupt-" + System.currentTimeMillis())
        if (bad.renameTo(target)) {
            CaptureLog.add("notes: moved unreadable file to ${target.name}")
        } else {
            // No second copy exists: block writes rather than destroy the only data there is.
            writesBlocked = true
            CaptureLog.add("notes: could not move ${bad.name} aside, saving disabled")
        }
    }

    private fun save() {
        val f = file ?: return
        if (writesBlocked) {
            CaptureLog.add("notes: save skipped, file was left unreadable")
            return
        }
        runCatching {
            val arr = JSONArray()
            notes.forEach { n ->
                arr.put(
                    JSONObject().apply {
                        put("id", n.id)
                        put("title", n.title)
                        put("oneLine", n.oneLine)
                        put("mode", n.mode)
                        put("whenText", n.whenText)
                        put("verbatim", n.verbatim)
                        put("why", n.why)
                        put("question", n.question)
                        put("tags", JSONArray(n.tags))
                        put("audioPath", n.audioPath ?: "")
                        put("spaceId", n.spaceId ?: "")
                        // 0 rather than JSON null: keeps the field numeric for every reader.
                        put("editedAt", n.editedAt ?: 0L)
                        put("format", n.format)
                        put("items", JSONArray(n.items))
                        put("createdAt", n.createdAt)
                    }
                )
            }
            writeAtomic(f, arr.toString())
        }.onFailure { CaptureLog.add("notes: save failed - ${it.message}") }
    }

    /** Write through a temp file so a crash never leaves half a JSON array behind. */
    private fun writeAtomic(target: File, text: String) {
        val dir = target.parentFile
        if (dir == null) {
            target.writeText(text)
            return
        }
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
