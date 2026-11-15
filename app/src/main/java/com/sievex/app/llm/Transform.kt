package com.sievex.app.llm

import com.sievex.app.CaptureLog
import com.sievex.app.NoteFormat
import org.json.JSONObject
import java.io.File

/** Turns a transcript into the structured note fields with one local LLM call. */
object Transform {

    /**
     * The note fields produced from one transcript.
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
        val items: List<String> = emptyList()
    )

    /**
     * One constant per format, emitted ahead of the transcript on every call, so the native KV
     * cache can reuse this turn byte for byte: only the user turn below ever changes.
     */
    private const val NOTE_SYSTEM =
        """You convert one spoken note into a single JSON object. The transcript is stored separately, so do not repeat, quote, or summarise it. Reply with JSON only, never prose or markdown.

Fields:
- mode: one of insight, idea, action, reference, question, reflection
- title: at most 8 words, no trailing period
- one_line: one sentence, at most 160 characters
- why_it_matters: one or two sentences of your own inference, or an empty string
- open_question: one question to chase later, or an empty string
- tags: up to 4 lowercase single words

Choose mode by intent, not topic: insight means something was understood, idea means something might be built, action means something must be done, reference means something should be looked up, question means an open puzzle, reflection means how something landed."""

    private const val LIST_SYSTEM =
        """You split one spoken note into a short list. The transcript is stored separately, so do not repeat, quote, or summarise it. Reply with JSON only, never prose or markdown.

Fields:
- title: at most 8 words, no trailing period
- items: three to twelve items, each a short phrase in the speaker's own words, without numbering, dashes or duplicates, in the order they were said
- tags: up to 4 lowercase single words

Use only what the speaker actually said. Never invent an item to fill the list, and never merge two distinct things into one item."""

    private const val STEPS_SYSTEM =
        """You turn one spoken note into an ordered list of steps. The transcript is stored separately, so do not repeat, quote, or summarise it. Reply with JSON only, never prose or markdown.

Fields:
- title: at most 8 words, no trailing period
- items: two to ten steps, each starting with a verb, one action per item, in the order they should happen
- tags: up to 4 lowercase single words

Use only what the speaker actually said. Never invent a step, and never split one action into two."""

    /**
     * GBNF for the note object, so a reply either parses or cannot be produced at all. The grammar
     * is the token budget: no field outside this list can be emitted, which is what removed the
     * echoed transcript. llama.cpp samples from `root`, and the grammar supplies the opening brace.
     */
    private const val NOTE_GRAMMAR = """root ::= object
object ::= "{" ws "\"mode\"" ws ":" ws mode ws "," ws "\"title\"" ws ":" ws string ws "," ws "\"one_line\"" ws ":" ws string ws "," ws "\"why_it_matters\"" ws ":" ws string ws "," ws "\"open_question\"" ws ":" ws string ws "," ws "\"tags\"" ws ":" ws string-list ws "}"
mode ::= "\"insight\"" | "\"idea\"" | "\"action\"" | "\"reference\"" | "\"question\"" | "\"reflection\""
string-list ::= "[" ws (string (ws "," ws string)*)? ws "]"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
"""

    /** The same string-list shape, without the note-only fields. Used by list and steps. */
    private const val ITEMS_GRAMMAR = """root ::= object
object ::= "{" ws "\"title\"" ws ":" ws string ws "," ws "\"items\"" ws ":" ws string-list ws "," ws "\"tags\"" ws ":" ws string-list ws "}"
string-list ::= "[" ws (string (ws "," ws string)*)? ws "]"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
"""

    /**
     * The expected reply is ~90-130 tokens for a note and less for a list. The headroom covers a
     * long field or two and only costs anything when the model decides to ramble.
     */
    private const val MAX_TOKENS = 320

    /** The one retry buys room rather than dropping the grammar, which is what closes an object. */
    private const val RETRY_TOKENS = 384

    /** The bounds the prompts state, applied here only when a field has to be derived instead. */
    private const val TITLE_WORDS = 8
    private const val ONE_LINE_CHARS = 160
    private const val MAX_ITEMS = 24

    private val WHITESPACE = Regex("\\s+")

    fun available(filesDir: File): Boolean = TransformModel.modelFile(filesDir).length() > 0

    /**
     * Shapes [transcript] in [format]. A [NoteFormat.PLAIN] note never calls the model: it keeps
     * the words exactly as they are and derives only a title.
     */
    fun apply(
        filesDir: File,
        transcript: String,
        format: NoteFormat = NoteFormat.NOTE,
        reportPhase: (Int) -> Unit = {}
    ): Fields? {
        if (!format.usesLlm) return plainFields(transcript)
        return runCatching { LlmGate.exclusive { applyUnsafe(filesDir, transcript, format, reportPhase) } }
            .onFailure { CaptureLog.add("llm: failed - ${it.message}") }
            .getOrNull()
    }

    /** The no-model path: the user's words, a derived title, nothing invented. */
    private fun plainFields(transcript: String): Fields? {
        val said = cleanTranscript(transcript)
        if (said.isEmpty()) return null
        return Fields(
            mode = NOTE_MODE,
            title = deriveTitle("", said),
            oneLine = "",
            verbatim = said,
            why = "",
            question = "",
            tags = emptyList()
        )
    }

    private fun applyUnsafe(
        filesDir: File,
        transcript: String,
        format: NoteFormat,
        reportPhase: (Int) -> Unit
    ): Fields? {
        val model = TransformModel.modelFile(filesDir)
        if (!model.exists() || model.length() == 0L) return null
        if (!LlamaBridge.nativeLoad(model.absolutePath)) {
            CaptureLog.add("llm: load failed")
            return null
        }

        val system = when (format) {
            NoteFormat.LIST -> LIST_SYSTEM
            NoteFormat.STEPS -> STEPS_SYSTEM
            else -> NOTE_SYSTEM
        }
        val grammar = if (format == NoteFormat.NOTE) NOTE_GRAMMAR else ITEMS_GRAMMAR

        // A retry restarts the native phase counter at 1; the card must not run backwards.
        var lastPhase = 0
        repeat(2) { attempt ->
            val prompt = buildPrompt(
                system,
                if (attempt == 0) transcript else retryTranscript(transcript, format)
            )
            // Grammar on both attempts: a truncated object is still an object prefix, and free
            // sampling is the path least likely to close it. The retry buys headroom instead.
            val cap = if (attempt == 0) MAX_TOKENS else RETRY_TOKENS
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
            CaptureLog.add(
                "llm: %s attempt %d (%d tok cap) %d chars in %d ms"
                    .format(format.id, attempt + 1, cap, raw.length, ms)
            )

            val parsed = if (format == NoteFormat.NOTE) {
                parseNote(raw, transcript)
            } else {
                parseItems(raw, transcript, format)
            }
            parsed?.let { return it }
            CaptureLog.add("llm: parse failed on attempt ${attempt + 1}")
        }
        return null
    }

    /** Only the user turn changes on retry, so the cached system turn above it still matches. */
    private fun retryTranscript(transcript: String, format: NoteFormat): String {
        val instruction = if (format == NoteFormat.NOTE) {
            "Keep title, one_line, why_it_matters and open_question short."
        } else {
            "Keep the title short and every item a short phrase."
        }
        return "$transcript\n\nReply with JSON only. $instruction"
    }

    /**
     * ChatML with an empty assistant turn. The grammar opens the object with `{`, and a prefilled
     * brace would have to be primed out of the sampler by hand instead of being generated under
     * the grammar like every other character.
     */
    private fun buildPrompt(system: String, transcript: String): String = buildString {
        append("<|im_start|>system\n")
        append(system)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append("Transcript:\n")
        append(transcript.trim())
        append("\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
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

        val title = json.optString("title").trim()
        val oneLine = json.optString("one_line").trim()
        val said = cleanTranscript(transcript)
        if (title.isEmpty() && oneLine.isEmpty() && said.isEmpty()) return null

        val line = oneLine.ifBlank { said.take(ONE_LINE_CHARS) }

        return Fields(
            mode = normaliseMode(json.optString("mode")),
            title = title.ifBlank { deriveTitle(line, said) },
            oneLine = line,
            verbatim = said,
            why = json.optString("why_it_matters").trim(),
            question = json.optString("open_question").trim(),
            tags = tags(json)
        )
    }

    /** Reads a list or steps reply. An empty list is a failure: there is nothing to show. */
    private fun parseItems(raw: String, transcript: String, format: NoteFormat): Fields? {
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(stripToJson(raw)) }.getOrNull() ?: return null

        val said = cleanTranscript(transcript)
        val items = stringList(json, "items", MAX_ITEMS)
        val title = json.optString("title").trim()
        if (items.isEmpty()) return null

        val line = if (format.ordered) "${items.size} steps" else "${items.size} items"

        return Fields(
            mode = NOTE_MODE,
            title = title.ifBlank { deriveTitle(line, said) },
            oneLine = line,
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
     * The dictated text is the record, so it is only made presentable: outer whitespace goes and
     * internal runs collapse to one space. Punctuation is left exactly as recognised, because
     * inventing it would make the "verbatim" label a lie.
     */
    private fun cleanTranscript(transcript: String): String =
        transcript.replace(WHITESPACE, " ").trim()

    /** A blank title is not worth another generation: the note still has words to title it with. */
    private fun deriveTitle(oneLine: String, verbatim: String): String {
        val source = oneLine.ifBlank { verbatim }.replace(WHITESPACE, " ").trim()
        if (source.isEmpty()) return ""
        val words = source.split(' ').take(TITLE_WORDS).joinToString(" ")
        val trimmed = words.trimEnd('.', ',', ';', ':')
        return trimmed.ifBlank { words }
    }

    /** The grammar forbids extra fields, so a malformed tag array only costs the tags. */
    private fun tags(json: JSONObject): List<String> = stringList(json, "tags", 4)

    /** Reads one array of strings, trimmed, non-blank, de-duplicated case-insensitively. */
    private fun stringList(json: JSONObject, key: String, limit: Int): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            // Items must stay one-liners: the editor round-trips them one per line, so an
            // embedded newline would silently become two items on the next save.
            val value = array.optString(i).replace(WHITESPACE, " ").trim()
            if (value.isEmpty()) continue
            if (!seen.add(value.lowercase())) continue
            out.add(value)
            if (out.size == limit) break
        }
        return out
    }

    private fun normaliseMode(raw: String): String = when (raw.trim().lowercase()) {
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
