package com.sievex.app.llm

import com.sievex.app.CaptureLog
import com.sievex.app.NoteFormat
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Turns a transcript or text capture into structured note fields with one local LLM call. */
object Transform {

    /** Provenance of the input passed to [apply], kept in the user turn so system KV stays warm. */
    enum class InputKind(val header: String) {
        VOICE("Transcript:"),
        TYPED("Note:"),
        FOLLOW_UP_THREAD("Note and follow-ups:"),
        OCR("Image text (OCR):"),
        CLIP("Clipped text and reaction:")
    }

    /**
     * The note fields produced from one capture.
     *
     * [verbatim] is the ASR text (or the text the user typed), not model output: asking the model
     * to echo the transcript cost it most of its token budget, so the oldest bursts lost the tail
     * of every field. [items] is only filled for a [NoteFormat.LIST] or [NoteFormat.STEPS] note.
     */
    data class Fields(
        val mode: String,
        val title: String,
        val oneLine: String,
        val verbatim: String,
        val why: String,
        val question: String,
        val tags: List<String>,
        val items: List<String> = emptyList(),
        val questionDrafts: List<String> = emptyList()
    )

    /**
     * One constant per format, emitted ahead of the user turn on every call, so the native KV
     * snapshot can be reused byte for byte across calls. Fields are ordered left-to-right to
     * maximize autoregressive conditioning on a 1.7B model (`one_line` before `mode` and `title`;
     * `items` before `title` for lists/steps).
     */
    private const val NOTE_SYSTEM =
        """You structure one captured note into a single JSON object. The full verbatim text is stored separately, so never echo it. Reply with JSON only, never prose or markdown.

Fields (in order):
- one_line: the core claim, takeaway, or request in one tight sentence (at most 160 characters), in the user's voice
- mode: one of insight, idea, action, reference, question, reflection
- title: at most 8 words, specific and concrete, no trailing period
- why_it_matters: one or two sentences of concise inference or implication; use "" for simple reminders, errands, or self-evident logistics
- question_drafts: 0 to 3 short, distinct follow-up questions strictly grounded in the note that would change a decision, test an assumption, or resolve a missing concrete detail (steered by Space focus if provided); use [] when nothing needs chasing or for simple reminders
- tags: up to 4 lowercase single-word tags (no #, no spaces)

Choose mode by intent, not topic: insight = something understood; idea = speculative concept or proposal; action = concrete task the user intends to do; reference = something to look up or save; question = an open puzzle; reflection = how something landed."""

    private const val LIST_SYSTEM =
        """You split one captured note into a clean list. The verbatim text is stored separately, so never echo it. Reply with JSON only, never prose or markdown.

Fields (in order):
- items: 1 to 12 items, only as many as actually mentioned, each a short phrase in the user's own words, without numbering, dashes, or duplicates, in order. Honor self-corrections (drop cancelled items) and merge obvious repeats. Never invent an item or merge two distinct things into one
- title: at most 8 words summarizing the list, no trailing period
- tags: up to 4 lowercase single-word tags (no #, no spaces)"""

    private const val STEPS_SYSTEM =
        """You turn one captured note into an ordered sequence of actionable steps. The verbatim text is stored separately, so never echo it. Reply with JSON only, never prose or markdown.

Fields (in order):
- items: 1 to 10 steps, only as many as actually mentioned, each starting with a direct action verb, one action per item, ordered by execution (move late-mentioned prerequisites earlier). Never invent a step or split one action into two
- title: at most 8 words summarizing the procedure, no trailing period
- tags: up to 4 lowercase single-word tags (no #, no spaces)"""

    /** Assistant-turn JSON prefixes prefilled into batch prompt processing instead of decoded 1-by-1. */
    private const val NOTE_PREFILL = "{\"one_line\":\""
    private const val ITEMS_PREFILL = "{\"items\":[\""

    /**
     * Bounded GBNF grammars starting immediately after [NOTE_PREFILL] / [ITEMS_PREFILL].
     * Array cardinalities, single-line strings, and lowercase tag characters are enforced by the
     * sampler so empty lists, `#tags`, embedded newlines, and runaway loops cannot occur.
     */
    private const val NOTE_GRAMMAR = """root ::= line-char+ "\",\"mode\":" mode ",\"title\":" ne-line-str ",\"why_it_matters\":" line-str ",\"question_drafts\":" drafts-list ",\"tags\":" tag-list "}"
mode ::= "\"insight\"" | "\"idea\"" | "\"action\"" | "\"reference\"" | "\"question\"" | "\"reflection\""
drafts-list ::= "[" (ne-line-str ("," ne-line-str){0,2})? "]"
tag-list ::= "[" (tag-str ("," tag-str){0,3})? "]"
tag-str ::= "\"" tag-char{1,28} "\""
tag-char ::= [a-z0-9áéíóúüñ-]
ne-line-str ::= "\"" line-char+ "\""
line-str ::= "\"" line-char* "\""
line-char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\] | "u" [0-9a-fA-F]{4})
"""

    private const val ITEMS_GRAMMAR = """root ::= line-char+ "\"" ("," ne-line-str){0,11} "],\"title\":" ne-line-str ",\"tags\":" tag-list "}"
tag-list ::= "[" (tag-str ("," tag-str){0,3})? "]"
tag-str ::= "\"" tag-char{1,28} "\""
tag-char ::= [a-z0-9áéíóúüñ-]
ne-line-str ::= "\"" line-char+ "\""
line-char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\] | "u" [0-9a-fA-F]{4})
"""

    private const val NOTE_MAX_TOKENS = 384
    private const val NOTE_RETRY_TOKENS = 448
    private const val ITEMS_MAX_TOKENS = 288
    private const val ITEMS_RETRY_TOKENS = 384

    /** The bounds the prompts state, applied here only when a field has to be derived instead. */
    private const val TITLE_WORDS = 8
    private const val ONE_LINE_CHARS = 160
    private const val MAX_ITEMS = 12
    private const val MAX_TRANSCRIPT_CHARS = 7_500
    private const val SPACE_NAME_CHARS = 80
    private const val SPACE_FOCUS_CHARS = 300
    private const val SOURCE_CONTEXT_CHARS = 140

    private val WHITESPACE = Regex("\\s+")
    private val HORIZONTAL_SPACE = Regex("[ \\t\\r]+")
    private val LINE_EDGE_SPACE = Regex(" *\\n *")
    private val MULTI_NEWLINE = Regex("\\n{3,}")
    private val LEADING_LIST_MARKER = Regex("^\\s*(?:[-*•—–]+|\\d+[.)]|\\[[ xX]\\])\\s+")
    private val LEADING_FILLER = Regex(
        "^(?:uh+|um+|erm+|so|well|okay|ok|like|eh+|bueno|pues|o sea|a ver)(?:[,.:;]+\\s*|\\s+)",
        RegexOption.IGNORE_CASE
    )
    private val HASHTAG = Regex("(?:^|\\s)#([a-zA-Z0-9áéíóúüñ_-]{2,28})")
    private val INVALID_TAG_CHARS = Regex("[^a-z0-9áéíóúüñ-]")

    fun available(filesDir: File): Boolean = TransformModel.isInstalled(filesDir)

    fun ensureLoaded(filesDir: File): Boolean {
        val model = TransformModel.modelFile(filesDir)
        if (!TransformModel.isInstalled(filesDir)) return false
        if (!LlamaBridge.ensureBackendsInitialized()) return false
        return runCatching {
            LlmGate.exclusive { LlamaBridge.nativeLoad(model.absolutePath) }
        }.getOrDefault(false)
    }

    /** Caller must already hold [LlmGate.exclusive]. */
    fun release() {
        LlamaBridge.nativeUnload()
    }

    /**
     * Shapes [transcript] in [format]. A [NoteFormat.PLAIN] note never calls the model: it keeps
     * the words as captured and derives only a title and explicit `#hashtags`.
     */
    fun apply(
        filesDir: File,
        transcript: String,
        format: NoteFormat = NoteFormat.NOTE,
        language: String = "en",
        spaceName: String = "",
        spaceFocus: String = "",
        inputKind: InputKind = InputKind.VOICE,
        sourceContext: String = "",
        reportPhase: (Int) -> Unit = {}
    ): Fields? {
        if (!format.usesLlm) return plainFields(transcript)
        return runCatching {
            LlmGate.exclusive {
                applyUnsafe(
                    filesDir,
                    transcript,
                    format,
                    language,
                    spaceName,
                    spaceFocus,
                    inputKind,
                    sourceContext,
                    reportPhase
                )
            }
        }
            .onFailure { CaptureLog.add("llm: failed - ${it.message}") }
            .getOrNull()
    }

    /** The no-model path: the user's words, a clause-aware derived title, and explicit #hashtags. */
    private fun plainFields(transcript: String): Fields? {
        val said = cleanTranscript(transcript)
        if (said.isEmpty()) return null
        val explicitTags = HASHTAG.findAll(said)
            .mapNotNull { normalizeTag(it.groupValues[1]) }
            .filter { tag -> tag.any { !it.isDigit() } }
            .distinct()
            .take(4)
            .toList()
        return Fields(
            mode = NOTE_MODE,
            title = deriveTitle("", said),
            oneLine = "",
            verbatim = said,
            why = "",
            question = "",
            tags = explicitTags
        )
    }

    private fun applyUnsafe(
        filesDir: File,
        transcript: String,
        format: NoteFormat,
        language: String,
        spaceName: String,
        spaceFocus: String,
        inputKind: InputKind,
        sourceContext: String,
        reportPhase: (Int) -> Unit
    ): Fields? {
        val model = TransformModel.modelFile(filesDir)
        if (!TransformModel.isInstalled(filesDir)) return null
        if (!LlamaBridge.ensureBackendsInitialized()) {
            val detail = LlamaBridge.lastError()
            CaptureLog.add(if (detail.isNotBlank()) "llm: backend init failed - $detail" else "llm: backend init failed")
            return null
        }
        if (!LlamaBridge.nativeLoad(model.absolutePath)) {
            val detail = LlamaBridge.lastError()
            CaptureLog.add(if (detail.isNotBlank()) "llm: load failed - $detail" else "llm: load failed")
            return null
        }

        val base = when (format) {
            NoteFormat.LIST -> LIST_SYSTEM
            NoteFormat.STEPS -> STEPS_SYSTEM
            else -> NOTE_SYSTEM
        }
        val system = base + languageClause(language, format)
        val grammar = if (format == NoteFormat.NOTE) NOTE_GRAMMAR else ITEMS_GRAMMAR
        val prefill = if (format == NoteFormat.NOTE) NOTE_PREFILL else ITEMS_PREFILL
        val safeTranscript = sanitizeChatMl(transcript).take(MAX_TRANSCRIPT_CHARS)
        val safeSpaceName = sanitizeChatMl(spaceName).replace(WHITESPACE, " ").trim().take(SPACE_NAME_CHARS)
        val safeSpaceFocus = sanitizeChatMl(spaceFocus).replace(WHITESPACE, " ").trim().take(SPACE_FOCUS_CHARS)
        val safeSourceContext = sanitizeChatMl(sourceContext).replace(WHITESPACE, " ").trim().take(SOURCE_CONTEXT_CHARS)

        // A retry restarts the native phase counter at 1; the card must not run backwards.
        var lastPhase = 0
        var lastFailureReason = ""
        repeat(2) { attempt ->
            val prompt = buildPrompt(
                system = system,
                transcript = if (attempt == 0) safeTranscript else retryTranscript(safeTranscript, format, lastFailureReason),
                format = format,
                prefill = prefill,
                spaceName = safeSpaceName,
                spaceFocus = safeSpaceFocus,
                inputKind = inputKind,
                sourceContext = safeSourceContext
            )
            // Grammar on both attempts: a truncated object is still an object prefix, and free
            // sampling is the path least likely to close it. The retry buys headroom instead.
            val cap = when {
                format == NoteFormat.NOTE && attempt == 0 -> NOTE_MAX_TOKENS
                format == NoteFormat.NOTE -> NOTE_RETRY_TOKENS
                attempt == 0 -> ITEMS_MAX_TOKENS
                else -> ITEMS_RETRY_TOKENS
            }
            val started = System.currentTimeMillis()
            // An explicit object, not a SAM lambda: the native side resolves `onPhase(I)V` on the
            // receiver's class, and an anonymous object is guaranteed to declare it.
            val progress = object : LlamaBridge.TransformProgress {
                override fun onPhase(phase: Int) {
                    if (phase > lastPhase) {
                        lastPhase = phase
                        reportPhase(phase)
                    }
                }
            }
            val raw = LlamaBridge.complete(
                prompt,
                cap,
                grammar = grammar,
                progress = progress
            )
            val ms = System.currentTimeMillis() - started
            val nativeErr = if (raw.isEmpty()) LlamaBridge.lastError() else ""
            CaptureLog.add(
                if (nativeErr.isNotBlank()) {
                    "llm: %s attempt %d (%d tok cap) 0 chars in %d ms - %s"
                        .format(format.id, attempt + 1, cap, ms, nativeErr)
                } else {
                    "llm: %s attempt %d (%d tok cap) %d chars in %d ms"
                        .format(format.id, attempt + 1, cap, raw.length, ms)
                }
            )
            if (raw.isEmpty() && isNonRetryableNativeError(nativeErr)) {
                return null
            }

            val fullRaw = if (raw.isNotBlank()) prefill + raw else raw
            val parsed = if (format == NoteFormat.NOTE) {
                parseNote(fullRaw, transcript)
            } else {
                parseItems(fullRaw, transcript)
            }
            parsed?.let { return it }
            lastFailureReason = if (fullRaw.isBlank()) "empty" else "parse"
            CaptureLog.add("llm: parse failed on attempt ${attempt + 1}")
        }
        return null
    }

    private fun isNonRetryableNativeError(error: String): Boolean {
        if (error.isBlank()) return false
        val lower = error.lowercase(Locale.ROOT)
        return "context ceiling" in lower ||
            "load failed" in lower ||
            "backend init" in lower ||
            "prefill failed" in lower ||
            "without a loaded model" in lower
    }

    /** Only the user turn changes on retry, so the cached system turn above it still matches. */
    private fun retryTranscript(transcript: String, format: NoteFormat, reason: String): String {
        val instruction = when {
            format == NoteFormat.NOTE -> "Keep one_line, title, why_it_matters and question_drafts concise."
            reason == "empty" -> "Extract at least one concise item and a short title."
            else -> "Keep each item a short phrase and the title concise."
        }
        return "$transcript\n\nReply with JSON only. $instruction"
    }

    /**
     * The one line that decides the output language. The instructions stay English — a 1.7B model
     * follows them fine and they are already tuned — while the fields it writes are Spanish.
     * `verbatim` is untouched either way: it is the transcript, not a translation.
     */
    private fun languageClause(language: String, format: NoteFormat): String {
        if (language != "es") return ""
        return when (format) {
            NoteFormat.LIST -> "\n\nWrite every item in items, title, and tags (as Spanish lowercase single words) in Spanish. Keep every JSON key exactly as specified."
            NoteFormat.STEPS -> "\n\nWrite every step in items (each starting with a Spanish infinitive verb), title, and tags (as Spanish lowercase single words) in Spanish. Keep every JSON key exactly as specified."
            else -> "\n\nWrite one_line, title, why_it_matters, every item in question_drafts, and tags (as Spanish lowercase single words) in Spanish. Keep every JSON key and the mode value in English exactly as specified."
        }
    }

    private fun sanitizeChatMl(text: String): String =
        text.replace("<|im_start|>", "").replace("<|im_end|>", "")

    /**
     * ChatML with an assistant JSON prefix prefilled into batch prompt processing, matching the
     * start of [NOTE_GRAMMAR] / [ITEMS_GRAMMAR]. Space and source context live in the user turn
     * so the cached system prefix remains identical across calls.
     */
    private fun buildPrompt(
        system: String,
        transcript: String,
        format: NoteFormat,
        prefill: String,
        spaceName: String = "",
        spaceFocus: String = "",
        inputKind: InputKind = InputKind.VOICE,
        sourceContext: String = ""
    ): String = buildString {
        append("<|im_start|>system\n")
        append(system)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        var hasHeader = false
        if (spaceName.isNotEmpty()) {
            append("Space: ").append(spaceName).append('\n')
            hasHeader = true
        }
        if (spaceFocus.isNotEmpty()) {
            val focusLabel = if (format == NoteFormat.NOTE) {
                "Space focus (steer why_it_matters and question_drafts toward this): "
            } else {
                "Space context (for domain terms and tags only): "
            }
            append(focusLabel).append(spaceFocus).append('\n')
            hasHeader = true
        }
        if (sourceContext.isNotEmpty()) {
            append("Listening to: ").append(sourceContext).append('\n')
            hasHeader = true
        }
        if (hasHeader) {
            append('\n')
        }
        append(inputKind.header).append('\n')
        append(transcript.trim())
        append("\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(prefill)
    }

    /**
     * Reads a note reply, tolerating the ways a small model drifts.
     *
     * [transcript] is the verbatim record, so the only unusable reply is one carrying no content
     * at all: a blank title or one_line is repaired from the note itself rather than regenerated.
     */
    private fun parseNote(raw: String, transcript: String): Fields? {
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(stripToJson(raw)) }.getOrNull() ?: return null

        val title = json.optString("title").trim().trimEnd('.')
        val oneLine = json.optString("one_line").trim()
        val said = cleanTranscript(transcript)
        if (title.isEmpty() && oneLine.isEmpty() && said.isEmpty()) return null

        val line = oneLine.ifBlank { deriveOneLine(said) }
        val drafts = stringList(json, "question_drafts", 3).ifEmpty {
            listOfNotNull(json.optString("open_question").trim().takeIf { it.isNotEmpty() })
        }

        return Fields(
            mode = normaliseMode(json.optString("mode")),
            title = title.ifBlank { deriveTitle(line, said) },
            oneLine = line,
            verbatim = said,
            why = json.optString("why_it_matters").trim(),
            question = "",
            tags = tags(json),
            questionDrafts = drafts
        )
    }

    /** Reads a list or steps reply. An empty list is a failure: there is nothing to show. */
    private fun parseItems(raw: String, transcript: String): Fields? {
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(stripToJson(raw)) }.getOrNull() ?: return null

        val said = cleanTranscript(transcript)
        val items = stringList(json, "items", MAX_ITEMS, stripListPrefix = true)
        val title = json.optString("title").trim().trimEnd('.')
        if (items.isEmpty()) return null

        return Fields(
            mode = NOTE_MODE,
            title = title.ifBlank { deriveTitle("", items.firstOrNull().orEmpty().ifBlank { said }) },
            oneLine = "",
            verbatim = said,
            why = "",
            question = "",
            tags = tags(json),
            items = items
        )
    }

    /**
     * The grammar should make the reply a bare object, but a model that rambles can still wrap it
     * in prose before the grammar's first token is accepted, so keep from the first brace to the
     * last one.
     */
    private fun stripToJson(raw: String): String {
        var text = raw.trim()
        val start = text.indexOf('{')
        if (start >= 0) text = text.substring(start)
        val end = text.lastIndexOf('}')
        if (end >= 0) text = text.substring(0, end + 1)
        return text
    }

    /**
     * Collapses horizontal runs and trims outer whitespace while preserving paragraph breaks (`\n\n`)
     * for typed notes and follow-up threads. Punctuation is left untouched.
     */
    private fun cleanTranscript(transcript: String): String =
        transcript
            .replace("\r\n", "\n")
            .replace(HORIZONTAL_SPACE, " ")
            .replace(LINE_EDGE_SPACE, "\n")
            .replace(MULTI_NEWLINE, "\n\n")
            .trim()

    private fun deriveOneLine(said: String): String {
        val flat = said.replace(WHITESPACE, " ").trim()
        if (flat.length <= ONE_LINE_CHARS) return flat
        val cut = flat.lastIndexOf(' ', ONE_LINE_CHARS - 1)
        val end = if (cut >= ONE_LINE_CHARS / 2) cut else ONE_LINE_CHARS - 1
        return flat.substring(0, end).trimEnd('.', ',', ';', ':') + "\u2026"
    }

    /**
     * Clause-aware deterministic title derivation for Plain notes, blank LLM titles, and fallbacks.
     * Strips leading spoken fillers and breaks at a sentence/clause boundary inside 3..8 words.
     */
    internal fun deriveTitle(oneLine: String, verbatim: String): String {
        val raw = oneLine.ifBlank { verbatim }
        val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        var source = firstLine.replace(WHITESPACE, " ").trim()
        if (source.isEmpty()) return ""
        repeat(3) {
            val stripped = LEADING_FILLER.replaceFirst(source, "").trim()
            if (stripped.isEmpty() || stripped == source) return@repeat
            source = stripped
        }
        val words = source.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        var takeCount = minOf(words.size, TITLE_WORDS)
        for (i in 2 until takeCount) {
            val w = words[i]
            val last = w.last()
            if (last == '.' || last == '?' || last == '!' || last == ';' || last == ':' || last == ',') {
                takeCount = i + 1
                break
            }
        }
        val joined = words.take(takeCount).joinToString(" ")
        val trimmed = joined.trimEnd('.', ',', ';', ':').ifBlank { joined }
        return trimmed.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    private fun normalizeTag(raw: String): String? {
        val firstWord = raw.trim()
            .removePrefix("#")
            .lowercase(Locale.ROOT)
            .split(WHITESPACE)
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val cleaned = INVALID_TAG_CHARS.replace(firstWord, "").trim('-')
        return cleaned.takeIf { it.isNotEmpty() }?.take(28)
    }

    /** The grammar forbids extra fields, so a malformed tag array only costs the tags. */
    private fun tags(json: JSONObject): List<String> {
        val array = json.optJSONArray("tags") ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val tag = normalizeTag(array.optString(i)) ?: continue
            if (!seen.add(tag)) continue
            out.add(tag)
            if (out.size == 4) break
        }
        return out
    }

    /** Reads one array of strings, trimmed, non-blank, de-duplicated case-insensitively. */
    private fun stringList(
        json: JSONObject,
        key: String,
        limit: Int,
        stripListPrefix: Boolean = false
    ): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            // Items must stay one-liners: the editor round-trips them one per line, so an
            // embedded newline would silently become two items on the next save.
            var value = array.optString(i).replace(WHITESPACE, " ").trim()
            if (stripListPrefix) {
                value = LEADING_LIST_MARKER.replaceFirst(value, "").trim()
            }
            if (value.isEmpty()) continue
            if (!seen.add(value.lowercase(Locale.ROOT))) continue
            out.add(value)
            if (out.size == limit) break
        }
        return out
    }

    private fun normaliseMode(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
        "idea" -> "Idea"
        "action" -> "Action"
        "reference" -> "Reference"
        "question" -> "Question"
        "reflection" -> "Reflection"
        else -> "Insight"
    }

    /**
     * The mode a plain or listed note carries: it is not an intent, so it must not pretend to be
     * one of the six. The UI renders unknown modes in the neutral colour.
     */
    private const val NOTE_MODE = "Note"
}
