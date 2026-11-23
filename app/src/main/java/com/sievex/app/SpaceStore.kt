package com.sievex.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A named container for notes. A note belongs to at most one space. */
data class Space(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** The user's own words about what this space is for. Never derived from the notes. */
    val description: String = "",
    /**
     * Optional AI steering prompt for this space: focuses Lens reports and note
     * follow-up question drafts onto a specific angle, goal, or intersection.
     */
    val focusPrompt: String = "",
    /**
     * The lenses enabled on this space, in the order the user picked them. Lens ids, not
     * artifacts: each id names one report kind, and every (space, lens) pair owns its own
     * artifact. A space with no lenses is allowed, so this may be empty.
     */
    val lensIds: List<String> = listOf("insight")
)

/** A derived artifact: the output of a transform over every note in one space. */
data class LensArtifact(
    val id: String,
    val spaceId: String,
    val kind: String,          // "insight"
    val body: String,          // markdown
    val covered: List<Long>,   // note ids
    val model: String,
    val generatedAt: Long,
    val coveredMedia: List<String> = emptyList(),
    /** Snapshot of [Space.focusPrompt] at generation time so changes mark the report stale. */
    val focusPrompt: String = ""
)

/**
 * Spaces and lens artifacts, persisted as one JSON object at `filesDir/spaces.json`.
 *
 * Spaces never own note fields: deleting a space only clears `StoredNote.spaceId`.
 * Artifacts are disposable and may be dropped without losing anything else.
 */
object SpaceStore {

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val spaces = mutableListOf<Space>()
    private val artifacts = mutableListOf<LensArtifact>()
    private var file: File? = null
    private var currentFile: File? = null

    @Volatile
    private var current: String? = null

    /**
     * True when spaces.json existed but could not be parsed. The space list is then empty for
     * reasons that have nothing to do with deletion, so callers must not treat missing ids as
     * deleted ones.
     */
    @Volatile
    private var loadBroken = false

    /**
     * Set when a bad file could not be moved aside. Saving would then replace the only copy of
     * the user's spaces with an empty object, so writes stay off until the process restarts.
     */
    @Volatile
    private var writesBlocked = false

    fun loadBroken(): Boolean = loadBroken

    /**
     * The space new bursts are filed into. Assigning persists immediately, so the choice
     * survives process death. May be null ("no space").
     */
    var currentSpaceId: String?
        get() = current
        @Synchronized
        set(value) {
            if (current == value) return
            current = value
            _version.value++
            saveCurrentOnly()
        }

    @Synchronized
    fun init(root: File) {
        if (file != null) return
        file = File(root, "spaces.json")
        currentFile = File(root, "current_space.txt")
        load()
    }

    @Synchronized
    fun all(): List<Space> = spaces.toList()

    /** Returns `(spaceName, focusPrompt)` for [spaceId], falling back to description if no explicit focus is set. */
    @Synchronized
    fun focusFor(spaceId: String?): Pair<String, String> {
        if (spaceId.isNullOrBlank()) return "" to ""
        val space = spaces.firstOrNull { it.id == spaceId } ?: return "" to ""
        return space.name to space.focusPrompt.ifBlank { space.description }
    }

    @Synchronized
    fun create(name: String): Space {
        val space = Space(
            id = "sp_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifBlank { "Untitled space" },
            createdAt = System.currentTimeMillis()
        )
        spaces.add(space)
        save()
        CaptureLog.add("spaces: created ${space.name}")
        return space
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val i = spaces.indexOfFirst { it.id == id }
        if (i < 0) {
            CaptureLog.add("spaces: rename, unknown space $id")
            return
        }
        val clean = name.trim()
        if (clean.isBlank() || spaces[i].name == clean) return
        spaces[i] = spaces[i].copy(name = clean)
        save()
    }

    /**
     * Detaches every note that pointed at the space **first**, so a crash mid-delete cannot
     * leave notes carrying a dead space id, then removes the space and its artifacts.
     * Note fields are never touched; only `spaceId` becomes null.
     */
    @Synchronized
    fun delete(id: String) {
        if (spaces.none { it.id == id }) {
            CaptureLog.add("spaces: delete, unknown space $id")
            return
        }
        NoteStore.clearSpace(id)
        MediaStore.deleteForSpace(id)

        spaces.removeAll { it.id == id }
        artifacts.removeAll { it.spaceId == id }
        if (current == id) current = null
        save()

        CaptureLog.add("spaces: deleted $id")
    }

    /**
     * Applies the user's edits to one space: its name, its description and which lenses are
     * enabled on it.
     *
     * [Space.id] and [Space.createdAt] are structural, so they stay as stored and a stale editor
     * cannot re-date or recreate a space. Notes and artifacts are untouched: turning a lens off
     * here only hides it on this space, and its artifacts stay on disk for other spaces.
     * A blank name keeps the old one, because an unnamed space would be unclickable.
     */
    @Synchronized
    fun update(space: Space) {
        val i = spaces.indexOfFirst { it.id == space.id }
        if (i < 0) {
            CaptureLog.add("spaces: update, unknown space ${space.id}")
            return
        }
        val existing = spaces[i]
        val edited = existing.copy(
            name = space.name.trim().ifBlank { existing.name },
            description = space.description.trim(),
            focusPrompt = space.focusPrompt.trim(),
            lensIds = cleanLensIds(space.lensIds)
        )
        if (edited == existing) return
        spaces[i] = edited
        save()
        CaptureLog.add("spaces: edited ${edited.id}, ${edited.lensIds.size} lens(es)")
    }

    /** A blank or repeated id would each become a dead chip in the picker; drop them here. */
    private fun cleanLensIds(ids: List<String>): List<String> =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Artifacts for one space, newest first. */
    @Synchronized
    fun artifacts(spaceId: String): List<LensArtifact> =
        artifacts.filter { it.spaceId == spaceId }.sortedByDescending { it.generatedAt }

    /** All generated lens artifacts across every space, newest first. */
    @Synchronized
    fun allArtifacts(): List<LensArtifact> =
        artifacts.sortedByDescending { it.generatedAt }

    @Synchronized
    fun latest(spaceId: String, kind: String): LensArtifact? =
        artifacts.filter { it.spaceId == spaceId && it.kind == kind }
            .maxByOrNull { it.generatedAt }

    /**
     * The newest artifact on one space whose `kind` is one of [lensIds], or null when the space
     * has no report from any of them. This is what the spaces list shows: the newest report from
     * any lens that space actually has enabled, so turning insight off stops a stale insight
     * report from speaking for the space.
     */
    @Synchronized
    fun latestFor(spaceId: String, lensIds: List<String>): LensArtifact? {
        val wanted = lensIds.toSet()
        if (wanted.isEmpty()) return null
        return artifacts.filter { it.spaceId == spaceId && it.kind in wanted }
            .maxByOrNull { it.generatedAt }
    }

    /**
     * Single-pass lookup for every space's newest active artifact, avoiding N separate scans
     * across [artifacts] when refreshing the UI snapshot.
     */
    @Synchronized
    fun latestBySpace(spaces: List<Space>): Map<String, LensArtifact?> {
        if (spaces.isEmpty() || artifacts.isEmpty()) return emptyMap()
        val wantedBySpace = HashMap<String, Set<String>>(spaces.size * 2)
        for (s in spaces) {
            if (s.lensIds.isNotEmpty()) wantedBySpace[s.id] = s.lensIds.toSet()
        }
        if (wantedBySpace.isEmpty()) return emptyMap()
        val best = HashMap<String, LensArtifact>(spaces.size * 2)
        for (a in artifacts) {
            val wanted = wantedBySpace[a.spaceId] ?: continue
            if (a.kind !in wanted) continue
            val prev = best[a.spaceId]
            if (prev == null || a.generatedAt > prev.generatedAt) best[a.spaceId] = a
        }
        return best
    }

    /**
     * Removes every artifact of one kind across every space with a single save. Called when a
     * custom lens is deleted, so its reports cannot linger as orphans no space can ever show.
     */
    @Synchronized
    fun deleteArtifactsOfKind(kind: String) {
        val removed = artifacts.count { it.kind == kind }
        if (removed == 0) return
        artifacts.removeAll { it.kind == kind }
        save()
        CaptureLog.add("spaces: removed $removed $kind artifact(s)")
    }

    /**
     * Permanently removes one custom lens from every space and deletes all of its artifacts in
     * a single atomic save.
     */
    @Synchronized
    fun removeLensEverywhere(lensId: String) {
        var changed = artifacts.removeAll { it.kind == lensId }
        for (i in spaces.indices) {
            if (lensId in spaces[i].lensIds) {
                spaces[i] = spaces[i].copy(lensIds = spaces[i].lensIds - lensId)
                changed = true
            }
        }
        if (changed) save()
    }

    @Synchronized
    fun saveArtifact(artifact: LensArtifact) {
        val i = artifacts.indexOfFirst { it.id == artifact.id }
        if (i >= 0) artifacts[i] = artifact else artifacts.add(artifact)
        save()
        CaptureLog.add("spaces: saved ${artifact.kind} for ${artifact.spaceId}")
    }

    @Synchronized
    fun noteCount(spaceId: String): Int = NoteStore.inSpace(spaceId).size

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return

        // Parse into scratch values: a failure half-way through must not leave a partial
        // in-memory state that the next save would persist over the real file.
        var loadedCurrent: String? = null
        val loadedSpaces = mutableListOf<Space>()
        val loadedArtifacts = mutableListOf<LensArtifact>()

        runCatching {
            val root = JSONObject(f.readText())
            loadedCurrent = root.optString("current").trim().ifBlank { null }

            val sa = root.optJSONArray("spaces")
            if (sa != null) {
                for (i in 0 until sa.length()) {
                    val o = sa.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    loadedSpaces.add(
                        Space(
                            id = id,
                            name = o.optString("name", "Untitled space"),
                            createdAt = o.optLong("createdAt"),
                            description = o.optString("description"),
                            focusPrompt = o.optString("focusPrompt"),
                            lensIds = lensIdsOf(o)
                        )
                    )
                }
            }

            val aa = root.optJSONArray("artifacts")
            if (aa != null) {
                for (i in 0 until aa.length()) {
                    val o = aa.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val spaceId = o.optString("spaceId")
                    if (id.isBlank() || spaceId.isBlank()) continue
                    val covered = mutableListOf<Long>()
                    val ca = o.optJSONArray("covered")
                    if (ca != null) for (j in 0 until ca.length()) covered.add(ca.optLong(j))
                    val coveredMedia = mutableListOf<String>()
                    val cm = o.optJSONArray("coveredMedia")
                    if (cm != null) {
                        for (j in 0 until cm.length()) {
                            val mid = cm.optString(j)
                            if (mid.isNotBlank()) coveredMedia.add(mid)
                        }
                    }
                    loadedArtifacts.add(
                        LensArtifact(
                            id = id,
                            spaceId = spaceId,
                            kind = o.optString("kind", "insight"),
                            body = o.optString("body"),
                            covered = covered,
                            model = o.optString("model"),
                            generatedAt = o.optLong("generatedAt"),
                            coveredMedia = coveredMedia,
                            focusPrompt = o.optString("focusPrompt")
                        )
                    )
                }
            }
        }.onSuccess {
            val cf = currentFile
            current = if (cf != null && cf.exists()) {
                runCatching { cf.readText().trim().ifBlank { null } }.getOrDefault(loadedCurrent)
            } else {
                loadedCurrent
            }
            spaces.clear()
            spaces.addAll(loadedSpaces)
            artifacts.clear()
            artifacts.addAll(loadedArtifacts)
            // A current space that no longer exists is the same as no current space.
            if (current != null && spaces.none { it.id == current }) current = null
            loadBroken = false
            _version.value++
        }.onFailure { e ->
            CaptureLog.add("spaces: load failed - ${e.message}")
            quarantineUnreadable(f)
            current = null
            spaces.clear()
            artifacts.clear()
            loadBroken = true
        }
    }

    /**
     * Sets an unreadable file aside so the next save cannot silently replace it with an empty
     * object. If it cannot be moved, writes are blocked instead: there is no second copy.
     */
    private fun quarantineUnreadable(bad: File) {
        val target = File(bad.parentFile, bad.name + ".corrupt-" + System.currentTimeMillis())
        if (bad.renameTo(target)) {
            CaptureLog.add("spaces: moved unreadable file to ${target.name}")
        } else {
            writesBlocked = true
            CaptureLog.add("spaces: could not move ${bad.name} aside, saving disabled")
        }
    }

    /**
     * `description` and `lensIds` arrived with the editing and lens features, so an older
     * spaces.json has neither key. Such a space had no description and always showed the insight
     * report, which is exactly what the defaults say. A file that *does* carry `lensIds` is
     * honoured as written — including an empty array, which means the user switched every lens
     * off and must not be silently handed `insight` back.
     */
    private fun lensIdsOf(o: JSONObject): List<String> {
        if (!o.has("lensIds")) return listOf("insight")
        val arr = o.optJSONArray("lensIds") ?: return listOf("insight")
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optString(i).trim()
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.distinct()
    }

    private fun saveCurrentOnly() {
        val cf = currentFile ?: return
        if (writesBlocked) {
            CaptureLog.add("spaces: save skipped, file was left unreadable")
            return
        }
        val value = current ?: ""
        StoreIo.executor.execute {
            runCatching { StoreIo.writeAtomic(cf, value) }
                .onFailure { CaptureLog.add("spaces: save current failed - ${it.message}") }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        _version.value++
        if (writesBlocked) {
            CaptureLog.add("spaces: save skipped, file was left unreadable")
            return
        }
        val currentSnap = current ?: ""
        val spacesSnap = spaces.toList()
        val artifactsSnap = artifacts.toList()
        val cf = currentFile
        StoreIo.executor.execute {
            runCatching {
                val root = JSONObject()
                root.put("current", currentSnap)

                val sa = JSONArray()
                spacesSnap.forEach { s ->
                    sa.put(
                        JSONObject().apply {
                            put("id", s.id)
                            put("name", s.name)
                            put("createdAt", s.createdAt)
                            put("description", s.description)
                            put("focusPrompt", s.focusPrompt)
                            put("lensIds", JSONArray(s.lensIds))
                        }
                    )
                }
                root.put("spaces", sa)

                val aa = JSONArray()
                artifactsSnap.forEach { a ->
                    aa.put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("spaceId", a.spaceId)
                            put("kind", a.kind)
                            put("body", a.body)
                            put("covered", JSONArray(a.covered))
                            put("coveredMedia", JSONArray(a.coveredMedia))
                            put("model", a.model)
                            put("generatedAt", a.generatedAt)
                            put("focusPrompt", a.focusPrompt)
                        }
                    )
                }
                root.put("artifacts", aa)

                StoreIo.writeAtomic(f, root.toString())
                if (cf != null) StoreIo.writeAtomic(cf, currentSnap)
            }.onFailure { CaptureLog.add("spaces: save failed - ${it.message}") }
        }
    }
}
