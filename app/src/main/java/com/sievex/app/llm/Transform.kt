package com.sievex.app.llm

import com.sievex.app.CaptureLog
import org.json.JSONObject
import java.io.File

/** Turns a transcript into the structured note fields with one local LLM call. */
object Transform {

    /**
     * The note fields produced from one transcript.
     *
     * [verbatim] is the ASR text itself, not model output: asking the model to echo the transcript
     * cost it most of its token budget, so the oldest bursts lost the tail of every field.
     */
    data class Fields(
        val mode: String,
        val title: String,
        val oneLine: String,
        val verbatim: String,
        val why: String,
        val question: String,
        val tags: List<String>
    )

    /**
     * One constant, emitted ahead of the transcript on every call, so the native KV cache can reuse
     * this turn byte for byte: only the user turn below ever changes between bursts.
     */
    private const val SYSTEM = """You convert one spoken note into a single JSON object. The transcript is stored separately, so do not repeat, quote, or summarise it. Reply with JSON only, never prose or markdown.

Fields:
- mode: one of insight, idea, action, reference, question, reflection
- title: at most 8 words, no trailing period
- one_line: one sentence, at most 160 characters
- why_it_matters: one or two sentences of your own inference, or an empty string
- open_question: one question to chase later, or an empty string
- tags: up to 4 lowercase single words

Choose mode by intent, not topic: insight means something was understood, idea means something might be built, action means something must be done, reference means something should be looked up, question means an open puzzle, reflection means how something landed."""

    /**
     * GBNF for the six-field object, so a reply either parses or cannot be produced at all.
     *
     * The grammar is the token budget: no field outside this list can be emitted, which is what
     * removed the echoed transcript. llama.cpp samples from the `root` rule, and it supplies the
     * opening `{` itself; see [buildPrompt].
     */
    private const val GRAMMAR = """root ::= object
object ::= "{" ws "\"mode\"" ws ":" ws mode ws "," ws "\"title\"" ws ":" ws string ws "," ws "\"one_line\"" ws ":" ws string ws "," ws "\"why_it_matters\"" ws ":" ws string ws "," ws "\"open_question\"" ws ":" ws string ws "," ws "\"tags\"" ws ":" ws string-list ws "}"
mode ::= "\"insight\"" | "\"idea\"" | "\"action\"" | "\"reference\"" | "\"question\"" | "\"reflection\""
string-list ::= "[" ws (string (ws "," ws string)*)? ws "]"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
"""

    /**
     * The expected reply is ~90-130 tokens. The headroom covers a long one_line plus two why
     * sentences plus four tags, and only costs anything when the model decides to ramble; the old
     * 360 was sized for a transcript echo the schema no longer asks for.
     */
    private const val MAX_TOKENS = 320

    /** The one retry buys room rather than dropping the grammar, which is what closes an object. */
    private const val RETRY_TOKENS = 384

    /** The bounds [SYSTEM] states, applied here only when a field has to be derived instead. */
    private const val TITLE_WORDS = 8
    private const val ONE_LINE_CHARS = 160

    private val WHITESPACE = Regex("\\s+")

    fun available(filesDir: File): Boolean = TransformModel.modelFile(filesDir).length() > 0

    fun apply(filesDir: File, transcript: String, reportPhase: (Int) -> Unit = {}): Fields? =
        runCatching { LlmGate.exclusive { applyUnsafe(filesDir, transcript, reportPhase) } }
            .onFailure { CaptureLog.add("llm: failed - ${it.message}") }
            .getOrNull()

    private fun applyUnsafe(
        filesDir: File,
        transcript: String,
        reportPhase: (Int) -> Unit
    ): Fields? {
        val model = TransformModel.modelFile(filesDir)
        if (!model.exists() || model.length() == 0L) return null
        if (!LlamaBridge.nativeLoad(model.absolutePath)) {
            CaptureLog.add("llm: load failed")
            return null
        }

        // A retry restarts the native phase counter at 1; the card must not run backwards.
        var lastPhase = 0
        repeat(2) { attempt ->
            val prompt = buildPrompt(if (attempt == 0) transcript else retryTranscript(transcript))
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
                grammar = GRAMMAR,
                progress = progress
            )
            val ms = System.currentTimeMillis() - started
            CaptureLog.add(
                "llm: attempt %d (%d tok cap) %d chars in %d ms"
                    .format(attempt + 1, cap, raw.length, ms)
            )

            parse(raw, transcript)?.let { return it }
            CaptureLog.add("llm: parse failed on attempt ${attempt + 1}")
        }
        return null
    }

    /** Only the user turn changes on retry, so the cached system turn above it still matches. */
    private fun retryTranscript(transcript: String) =
        "$transcript\n\nReply with JSON only. Keep title, one_line, why_it_matters and open_question short."

    /**
     * ChatML with an empty assistant turn. The grammar opens the object with `{`, and a prefilled
     * brace would have to be primed out of the sampler by hand instead of being generated under
     * the grammar like every other character.
     */
    private fun buildPrompt(transcript: String): String = buildString {
        append("<|im_start|>system\n")
        append(SYSTEM)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append("Transcript:\n")
        append(transcript.trim())
        append("\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /**
     * Reads the reply into fields, tolerating the ways a small model drifts.
     *
     * [transcript] is the verbatim record, so the only unusable reply is one carrying no content
     * at all: a blank title or one_line is repaired from the note itself rather than regenerated.
     */
    private fun parse(raw: String, transcript: String): Fields? {
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
     * The ASR text is the record now, so it is only made presentable: outer whitespace goes and
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
    private fun tags(json: JSONObject): List<String> {
        val array = json.optJSONArray("tags") ?: return emptyList()
        return (0 until array.length())
            .map { array.optString(it).trim().lowercase() }
            .filter { it.isNotEmpty() }
            .take(4)
    }

    private fun normaliseMode(raw: String): String = when (raw.trim().lowercase()) {
        "idea" -> "Idea"
        "action" -> "Action"
        "reference" -> "Reference"
        "question" -> "Question"
        "reflection" -> "Reflection"
        else -> "Insight"
    }
}
