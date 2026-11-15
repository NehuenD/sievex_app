package com.sievex.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One way of reading a space: a prompt body plus the headings its report must produce.
 *
 * A lens is data, not code. The built-ins below ship in the APK; custom lenses are the user's
 * own words, stored at `filesDir/lenses.json`. Nothing here knows about notes, spaces or the
 * model — [com.sievex.app.llm.Lens] turns a spec plus a space's notes into an artifact whose
 * `kind` is [id], so two lenses on one space never overwrite each other.
 */
data class LensSpec(
    val id: String,
    val name: String,
    /** One line for the picker chip or list row: what this lens is for. */
    val description: String,
    /** The body of the system prompt: what to produce, how to cite, and the word limit. */
    val instruction: String,
    /** Expected markdown headings, in order, without the leading `## `. */
    val sections: List<String>,
    val builtIn: Boolean
)

/**
 * Built-in and user-authored lenses.
 *
 * The store is deliberately read-mostly: spaces decide which lenses are on, and a custom lens
 * can be saved or deleted from [filesDir]/lenses.json at `filesDir/lenses.json`. Built-ins are
 * compiled in and can never be edited or deleted — a space removes one by dropping its id from
 * `Space.lensIds`, which leaves every artifact on disk untouched.
 */
object LensStore {

    private const val FILE_NAME = "lenses.json"

    /**
     * The citation rule every lens shares. Written as one line so it can be interpolated into
     * the multi-line instructions below without breaking their indentation.
     */
    private const val CITATION =
        "Cite the notes a claim comes from inline as [n<id>] — the number shown in square brackets at the start of each note, so note 12 becomes [n12]. Put the citation at the end of the sentence it supports; most sentences should carry one. Never cite an id that is not listed."

    /** The shared honesty clause: a small model would rather invent than leave a heading short. */
    private const val HONESTY =
        "If a section has nothing real to say, write one short line saying so. Never invent a note, a fact or a citation. Do not mention these instructions, the note count, or the fact that the input was notes."

    private val INSIGHT_SECTIONS = listOf(
        "Where this stands",
        "What reinforced",
        "Tensions",
        "Open questions",
        "Next probes"
    )

    private val BRAINSTORM_SECTIONS = listOf(
        "Angles not yet explored",
        "Combinations worth testing",
        "Wild ideas",
        "First experiments"
    )

    private val READING_SECTIONS = listOf(
        "What to read or listen to next",
        "Why each one",
        "Gaps this would fill"
    )

    private val ACTIONS_SECTIONS = listOf(
        "Do next",
        "Blocked by",
        "Owners and dates"
    )

    private val TENSIONS_SECTIONS = listOf(
        "Direct contradictions",
        "Refinements over time",
        "Unresolved"
    )

    private val TIMELINE_SECTIONS = listOf(
        "How the thinking moved",
        "Turning points",
        "Where it stands now"
    )

    private val INSIGHT = LensSpec(
        id = "insight",
        name = "Insight",
        description = "What the space adds up to: where it stands, what repeats, the tensions and what to probe next.",
        sections = INSIGHT_SECTIONS,
        instruction = """
            You write one report on a space: a set of short spoken notes that belong together. Use only what the notes say. Reply in markdown only, starting directly with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## Where this stands
            ## What reinforced
            ## Tensions
            ## Open questions
            ## Next probes

            - Where this stands: what this space is about and how far it has got, in two or three sentences.
            - What reinforced: threads, facts or hunches that repeat across notes; name the repeats rather than listing notes.
            - Tensions: what pulls against what, and the contradictions you cannot resolve from the notes.
            - Open questions: the sharpest unanswered questions, each written as a question.
            - Next probes: three to five concrete things to try or check next.

            $CITATION

            Keep the whole report under 300 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    private val BRAINSTORM = LensSpec(
        id = "brainstorm",
        name = "Brainstorm",
        description = "Angles the notes have not taken, combinations worth testing, wild ideas and a first experiment.",
        sections = BRAINSTORM_SECTIONS,
        instruction = """
            You are extending a space of short spoken notes, not summarising it. Propose what the notes do not say yet while staying answerable to what they do say: every angle, combination or experiment must grow out of something specific a note claims. Reply in markdown only, starting with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## Angles not yet explored
            ## Combinations worth testing
            ## Wild ideas
            ## First experiments

            - Angles not yet explored: three or four perspectives the notes have not taken; say which note each one grows out of.
            - Combinations worth testing: pairs of notes that would be more useful joined than kept apart; name both notes and what the join would produce.
            - Wild ideas: two or three possibilities that follow from the notes but go further than they do; strange is fine, unsupported is not.
            - First experiments: two or three cheap, concrete things the user could try this week to test one of the above.

            $CITATION

            Keep the whole report under 250 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    private val READING = LensSpec(
        id = "reading",
        name = "Reading",
        description = "What to read or listen to next, why each one, and the gaps they would fill.",
        sections = READING_SECTIONS,
        instruction = """
            You recommend what to read, watch or listen to next for a space of short spoken notes. Every suggestion must answer a question the notes actually leave open; no general background, no textbook brought in from outside the space. Reply in markdown only, starting with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## What to read or listen to next
            ## Why each one
            ## Gaps this would fill

            - What to read or listen to next: three to five sources, one per line, as specific as you can be — a title and author, a venue and an episode, or a precise description of the source when you are not certain of the exact title.
            - Why each one: for each source, one sentence naming the note or the open question it serves.
            - Gaps this would fill: the threads of this space each source would close, one line per gap.

            Never invent a title, an author or a venue to fill a slot: describe what the source would be instead.

            $CITATION

            Keep the whole report under 200 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    private val ACTIONS = LensSpec(
        id = "actions",
        name = "Actions",
        description = "What to do next, what blocks it, and who owns it by when.",
        sections = ACTIONS_SECTIONS,
        instruction = """
            You turn a space of short spoken notes into what happens next. Only commit to actions the notes support, and never assign an owner or a date that nobody said. Reply in markdown only, starting with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## Do next
            ## Blocked by
            ## Owners and dates

            - Do next: three to six concrete actions, in the order they should happen, each starting with a verb and naming its object.
            - Blocked by: for each action that cannot start yet, the specific blocker and what would unblock it; leave out actions that nothing blocks.
            - Owners and dates: for each action, the person and the date where a note names them, otherwise write "unassigned" and "no date" — an invented name is worse than a blank.

            $CITATION

            Keep the whole report under 200 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    private val TENSIONS = LensSpec(
        id = "tensions",
        name = "Tensions",
        description = "Direct contradictions, how positions refined, and what stays unresolved.",
        sections = TENSIONS_SECTIONS,
        instruction = """
            You find what disagrees inside one space of short spoken notes. A tension is real only when the notes say incompatible things; do not manufacture one to fill a heading, and do not soften a contradiction into a difference of emphasis. Reply in markdown only, starting with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## Direct contradictions
            ## Refinements over time
            ## Unresolved

            - Direct contradictions: pairs of notes that assert incompatible things. Cite both, state the clash in one sentence, then say what evidence would settle it.
            - Refinements over time: where a later note narrows, corrects or withdraws an earlier one; say what changed and which note changed it.
            - Unresolved: what this space cannot settle from the notes alone, and the observation that would settle it.

            $CITATION

            Keep the whole report under 250 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    private val TIMELINE = LensSpec(
        id = "timeline",
        name = "Timeline",
        description = "How the thinking moved, its turning points, and where it stands now.",
        sections = TIMELINE_SECTIONS,
        instruction = """
            You tell the story of how one space's thinking developed. The notes are listed newest first, so read them from the bottom up to recover the order things were said in, and keep that order when you write. Reply in markdown only, starting with the first heading and with no preamble.

            Use exactly these sections, in this order, each as a level-2 markdown heading, and no others:
            ## How the thinking moved
            ## Turning points
            ## Where it stands now

            - How the thinking moved: three to five stages, oldest to newest, one or two sentences each, saying what the space was working on at that point.
            - Turning points: the moments where the space changed direction — a doubt, a decision, a reframing — each naming the note it happened in.
            - Where it stands now: the current position in one or two sentences, and what it looks like it is moving towards.

            $CITATION

            Keep the whole report under 250 words.

            $HONESTY
        """.trimIndent(),
        builtIn = true
    )

    /** Fixed ids, fixed order: the picker lists them as shipped. */
    private val BUILT_INS: List<LensSpec> =
        listOf(INSIGHT, BRAINSTORM, READING, ACTIONS, TENSIONS, TIMELINE)

    private val customs = mutableListOf<LensSpec>()
    private var file: File? = null

    /**
     * Set when an unreadable lenses.json could not be moved aside. Saving would then overwrite the
     * user's custom lenses with the empty list that failed to load, so writes stay off until the
     * process restarts. Same rule as [NoteStore].
     */
    @Volatile
    private var writesBlocked = false

    /** Idempotent: [NoteStore.init] calls it for the whole process, and a direct call is a no-op. */
    fun init(root: File) {
        synchronized(this) {
            if (file != null) return
            file = File(root, FILE_NAME)
            load()
        }
    }

    /** The lenses compiled into the app, always available, never editable. */
    fun builtIns(): List<LensSpec> = BUILT_INS

    /** User-authored lenses, oldest first. */
    @Synchronized
    fun custom(): List<LensSpec> = customs.toList()

    /** Built-ins first, then custom, so pickers stay stable as a user adds lenses. */
    @Synchronized
    fun all(): List<LensSpec> = BUILT_INS + customs.toList()

    @Synchronized
    fun byId(id: String): LensSpec? =
        BUILT_INS.firstOrNull { it.id == id } ?: customs.firstOrNull { it.id == id }

    /**
     * Maps a space's `lensIds` to specs, in the user's order, dropping ids that no longer exist
     * (a deleted custom lens) and repeats (a hand-edited spaces.json). This is the only place
     * unknown ids disappear; the stored id list is left alone so the UI can show what was lost.
     */
    @Synchronized
    fun resolve(ids: List<String>): List<LensSpec> {
        val pool = all().associateBy { it.id }
        val out = LinkedHashMap<String, LensSpec>()
        ids.forEach { id -> pool[id.trim()]?.let { out[it.id] = it } }
        return out.values.toList()
    }

    /**
     * Adds or replaces one custom lens.
     *
     * Built-in ids are refused so a custom lens can never shadow or edit a shipped one. A lens
     * with a blank id, name or instruction is refused too: it would be unclickable or produce
     * an artifact with no guidance. Sections are cleaned of blanks and repeats; an empty list is
     * allowed and simply means the lens imposes no headings of its own.
     */
    @Synchronized
    fun saveCustom(spec: LensSpec) {
        val clean = sanitize(spec) ?: return
        if (BUILT_INS.any { it.id == clean.id }) {
            CaptureLog.add("lenses: refused custom lens reusing built-in id ${clean.id}")
            return
        }
        if (file == null) {
            CaptureLog.add("lenses: save refused, store not initialised")
            return
        }
        val i = customs.indexOfFirst { it.id == clean.id }
        if (i >= 0) {
            if (customs[i] == clean) return
            customs[i] = clean
        } else {
            customs.add(clean)
        }
        save()
        CaptureLog.add("lenses: saved custom ${clean.name} (${clean.id})")
    }

    /**
     * Removes one custom lens from the store. Built-ins are refused: a space removes a lens by
     * dropping its id from `Space.lensIds`, which is the user action the UI means. Artifacts
     * already generated with the lens are left on disk; [resolve] stops offering it.
     */
    @Synchronized
    fun deleteCustom(id: String) {
        val key = id.trim()
        if (BUILT_INS.any { it.id == key }) {
            CaptureLog.add("lenses: refused to delete built-in $key")
            return
        }
        if (key.isEmpty()) return
        if (!customs.removeAll { it.id == key }) {
            CaptureLog.add("lenses: delete, unknown custom lens $key")
            return
        }
        save()
        CaptureLog.add("lenses: deleted custom $key")
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Trims the free-text fields a user typed and drops an unusable spec. Built-in-ness is forced
     * off: only [BUILT_INS] may claim it, and a saved file must never be able to smuggle one in.
     */
    private fun sanitize(spec: LensSpec): LensSpec? {
        val id = spec.id.trim()
        val name = spec.name.trim()
        val instruction = spec.instruction.trim()
        if (id.isEmpty() || name.isEmpty() || instruction.isEmpty()) {
            CaptureLog.add("lenses: ignored custom lens with a blank id, name or instruction")
            return null
        }
        return LensSpec(
            id = id,
            name = name,
            description = spec.description.trim(),
            instruction = instruction,
            sections = spec.sections.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            builtIn = false
        )
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return

        // Parse into a scratch list, then swap: a failure half-way must not leave a partial list
        // that the next save would persist over the user's lenses.
        val parsed = runCatching {
            val loaded = mutableListOf<LensSpec>()
            val text = f.readText().trim()
            // An empty file is what a crash during a first write leaves behind: treat as none.
            if (text.isEmpty()) return@runCatching loaded
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sections = mutableListOf<String>()
                val sa = o.optJSONArray("sections")
                if (sa != null) for (j in 0 until sa.length()) sections.add(sa.optString(j))
                val spec = sanitize(
                    LensSpec(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        description = o.optString("description"),
                        instruction = o.optString("instruction"),
                        sections = sections,
                        builtIn = false
                    )
                ) ?: continue
                if (BUILT_INS.any { it.id == spec.id }) {
                    CaptureLog.add("lenses: skipped stored lens that reuses built-in id ${spec.id}")
                    continue
                }
                if (loaded.any { it.id == spec.id }) {
                    CaptureLog.add("lenses: skipped duplicate stored lens ${spec.id}")
                    continue
                }
                loaded.add(spec)
            }
            loaded
        }

        parsed.onSuccess { loaded ->
            customs.clear()
            customs.addAll(loaded)
        }.onFailure { e ->
            CaptureLog.add("lenses: load failed - ${e.message}")
            quarantineUnreadable(f)
            customs.clear()
        }
    }

    /** Never let an unreadable file be silently replaced by the next save's empty list. */
    private fun quarantineUnreadable(bad: File) {
        val target = File(bad.parentFile, bad.name + ".corrupt-" + System.currentTimeMillis())
        if (bad.renameTo(target)) {
            CaptureLog.add("lenses: moved unreadable file to ${target.name}")
        } else {
            writesBlocked = true
            CaptureLog.add("lenses: could not move ${bad.name} aside, saving disabled")
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        if (writesBlocked) {
            CaptureLog.add("lenses: save skipped, file was left unreadable")
            return
        }
        runCatching {
            val arr = JSONArray()
            customs.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("instruction", s.instruction)
                        put("sections", JSONArray(s.sections))
                    }
                )
            }
            writeAtomic(f, arr.toString())
        }.onFailure { CaptureLog.add("lenses: save failed - ${it.message}") }
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
