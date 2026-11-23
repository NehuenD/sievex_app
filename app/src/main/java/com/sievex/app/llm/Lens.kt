package com.sievex.app.llm

import com.sievex.app.CaptureLog
import com.sievex.app.LensArtifact
import com.sievex.app.LensSpec
import com.sievex.app.MediaItem
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import java.io.File
import java.util.UUID

/**
 * Reads every note in one space and writes a single markdown report over that space, shaped by
 * one [LensSpec].
 *
 * A lens is disposable: it records the ids it covered so the UI can tell when it has drifted
 * from the notes, and regenerating it needs no other state. Note fields are only ever read.
 *
 * The lens itself is data, not code: [LensSpec.instruction] is the body of the system prompt and
 * [LensSpec.sections] are the headings the reply must contain, in order. The artifact's `kind`
 * is [LensSpec.id], so two lenses over the same space never overwrite each other.
 */
object Lens {

    private const val MODEL = "LFM3-1.7B-Q4_K_M"
    private const val MAX_TOKENS = 500

    // Per-field ceilings so one long note cannot crowd out the rest of the space.
    private const val TITLE_CHARS = 120
    private const val ONE_LINE_CHARS = 300
    private const val WHY_CHARS = 400
    private const val VERBATIM_CHARS = 400
    private const val ITEMS_CHARS = 400
    private const val FOLLOWUPS_CHARS = 400
    private const val MEDIA_CAPTION_CHARS = 240
    private const val MEDIA_OCR_CHARS = 500

    /** The user's own framing of the space: enough to steer the report, not enough to dominate it. */
    private const val DESCRIPTION_CHARS = 300
    private const val FOCUS_PROMPT_CHARS = 400

    /**
     * The native side sizes its context from the prompt (`n_ctx = prompt tokens + maxTokens`),
     * so a long prompt is a long KV cache on a phone. ~6000 characters is roughly 1500 tokens:
     * room for a dozen or so notes, cheap enough to run without paging out the model.
     */
    private const val PROMPT_BUDGET = 7_500

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val EXTRA_HEADING_HASHES_REGEX = Regex("(?m)^#{3,}[ \\t]*")
    private val TIGHT_HEADING_REGEX = Regex("(?m)^##(?=[^#\\s])")
    private val CITATION_TAG_REGEX = Regex("""\s*\[\s*n\s*\d+\s*]""")
    private val EMPTY_PARENS_REGEX = Regex("""\(\s*\)""")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("""[ \t]+([;:,.!?])""")
    private val MULTI_SPACE_REGEX = Regex("""[ \t]{2,}""")

    /**
     * Runs one LLM pass over [notes] as [spec] reads them, and saves the result as an artifact
     * whose kind is [LensSpec.id].
     *
     * Returns null when there is nothing to read, the model fails to load, the reply is blank,
     * or anything else throws: the lens is disposable, so a failed generation leaves the
     * previous artifact in place and the caller simply reports the failure.
     */
    @Synchronized
    fun generate(
        filesDir: File,
        space: Space,
        notes: List<StoredNote>,
        spec: LensSpec,
        language: String = "en",
        media: List<MediaItem> = emptyList()
    ): LensArtifact? =
        runCatching { LlmGate.exclusive { generateUnsafe(filesDir, space, notes, media, spec, language) } }
            .onFailure { CaptureLog.add("lens ${spec.id}: failed - ${it.message}") }
            .getOrNull()

    private fun generateUnsafe(
        filesDir: File,
        space: Space,
        notes: List<StoredNote>,
        media: List<MediaItem>,
        spec: LensSpec,
        language: String
    ): LensArtifact? {
        val readableMedia = media.filter { it.caption.isNotBlank() || it.ocrText.isNotBlank() }
        // Nothing to read: a report over zero notes and zero readable images could only be invented.
        if (notes.isEmpty() && readableMedia.isEmpty()) {
            CaptureLog.add("lens ${spec.id}: no notes or readable images in ${space.id}")
            return null
        }

        if (!LlamaBridge.ensureBackendsInitialized()) {
            val detail = LlamaBridge.lastError()
            CaptureLog.add(
                if (detail.isNotBlank()) "lens ${spec.id}: backend init failed - $detail"
                else "lens ${spec.id}: backend init failed"
            )
            return null
        }
        if (!LlamaBridge.nativeLoad(TransformModel.modelFile(filesDir).absolutePath)) {
            val detail = LlamaBridge.lastError()
            CaptureLog.add(
                if (detail.isNotBlank()) "lens ${spec.id}: load failed - $detail"
                else "lens ${spec.id}: load failed"
            )
            return null
        }

        val started = System.currentTimeMillis()
        // No grammar here: unlike Transform, the reply is markdown, so sampling stays free-form.
        val raw = LlamaBridge.complete(buildPrompt(space, notes, readableMedia, spec, language), MAX_TOKENS)
        val ms = System.currentTimeMillis() - started
        CaptureLog.add(
            "lens ${spec.id}: %d chars from %d notes, %d images in %d ms".format(
                raw.length,
                notes.size,
                readableMedia.size,
                ms
            )
        )

        if (raw.isBlank()) {
            val detail = LlamaBridge.lastError()
            CaptureLog.add(
                if (detail.isNotBlank()) "lens ${spec.id}: empty reply - $detail"
                else "lens ${spec.id}: empty reply"
            )
            return null
        }

        val body = stripCitations(assemble(raw))
        if (body.isBlank()) return null

        val artifact = LensArtifact(
            id = "ln_" + UUID.randomUUID().toString().replace("-", "").take(12),
            spaceId = space.id,
            // One kind per lens: artifacts of different lenses never overwrite each other.
            kind = spec.id,
            body = body,
            covered = notes.map { it.id },
            model = MODEL,
            generatedAt = System.currentTimeMillis(),
            coveredMedia = readableMedia.map { it.id },
            focusPrompt = space.focusPrompt.trim()
        )
        SpaceStore.saveArtifact(artifact)
        return artifact
    }

    /**
     * The system turn: the lens's own instruction, then the heading list derived from the spec.
     *
     * The headings are restated from [LensSpec.sections] rather than trusted to the instruction
     * text, because a custom lens is arbitrary user input that may have forgotten them. When the
     * instruction already spells out this exact ordered list — every built-in does — the clause
     * is skipped, so the model is never shown two competing copies of the same outline.
     */
    private fun systemPrompt(spec: LensSpec, language: String, focusPrompt: String): String {
        val instruction = sanitizeChatMl(spec.instruction).trim()
        val cleanSections = sections(spec)
        val base = instruction + headingClause(instruction, cleanSections)
        val focus = flat(focusPrompt, FOCUS_PROMPT_CHARS)
        val withFocus = if (focus.isNotEmpty()) {
            base + "\n\nSpace focus (prioritize this angle throughout every section; look for insights, patterns, and connections aligned with it):\n" + focus
        } else {
            base
        }
        // The instructions stay English (they are tuned), the report does not. The heading list
        // above is English too, so Spanish asks for a translation of it, order preserved.
        return if (language == "es") {
            withFocus + "\n\nWrite the whole report in Spanish, including the section headings: " +
                "translate each heading, keep the order and the number of sections, and add none."
        } else {
            withFocus
        }
    }

    /** Trimmed, non-blank, de-duplicated heading names, original order kept. */
    private fun sections(spec: LensSpec): List<String> =
        spec.sections
            .map { sanitizeChatMl(it).trim().removePrefix("##").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * `## ` headings exactly as the spec lists them, in order, and a sentence saying no others
     * are allowed. Empty when the spec has no sections or the instruction already lists them all.
     */
    private fun headingClause(instruction: String, sections: List<String>): String {
        if (sections.isEmpty()) return ""
        if (sections.all { instruction.contains("## $it") }) return ""
        return buildString {
            append("\n\nUse exactly these level-2 markdown headings, in this order, and no others:\n")
            for (section in sections) append("## ").append(section).append('\n')
        }
    }

    /**
     * ChatML, with the assistant turn prefilled with `##` so the model continues in markdown
     * instead of opening a sentence. Notes go in newest first, which is the order NoteStore gives.
     */
    private fun buildPrompt(
        space: Space,
        notes: List<StoredNote>,
        media: List<MediaItem>,
        spec: LensSpec,
        language: String
    ): String =
        buildString {
            append("<|im_start|>system\n")
            append(systemPrompt(spec, language, space.focusPrompt))
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append("Space: ").append(flat(space.name, TITLE_CHARS).ifBlank { "Untitled space" })
                .append('\n')

            // The space description is the user's own words, so it outranks anything inferred.
            val description = flat(space.description, DESCRIPTION_CHARS)
            if (description.isNotEmpty()) {
                append("About this space: ").append(description).append('\n')
            }

            append("Notes: ").append(notes.size).append('\n')
            if (media.isNotEmpty()) {
                append("Images: ").append(media.size).append('\n')
            }
            if (notes.size + media.size < 2) {
                append("Only one item is in this space. Say that plainly in the first section")
                append(" and keep every other section to a line or two.\n")
            }
            append('\n')

            var used = length
            var shown = 0
            for (note in notes) {
                val block = noteBlock(note)
                // Always include the first note, even if a single note alone blows the budget.
                if (shown > 0 && used + block.length > PROMPT_BUDGET) break
                append(block).append('\n')
                used += block.length
                shown++
            }

            val hidden = notes.size - shown
            if (hidden > 0) {
                append("($hidden older ").append(if (hidden == 1) "note is" else "notes are")
                    .append(" not shown here.)\n")
            }

            if (media.isNotEmpty()) {
                val noteTitles = notes.associate { it.id to flat(it.title, TITLE_CHARS) }
                var shownMedia = 0
                for (item in media) {
                    val block = mediaBlock(item, noteTitles)
                    if (block.isEmpty()) continue
                    if ((shown > 0 || shownMedia > 0) && used + block.length > PROMPT_BUDGET) break
                    append(block).append('\n')
                    used += block.length
                    shownMedia++
                }
                val hiddenMedia = media.size - shownMedia
                if (hiddenMedia > 0) {
                    append("($hiddenMedia older ").append(if (hiddenMedia == 1) "image is" else "images are")
                        .append(" not shown here.)\n")
                }
            }

            append("<|im_end|>\n")
            append("<|im_start|>assistant\n##")
        }

    /** One image as a compact block carrying its caption, OCR text, and linked note titles. */
    private fun mediaBlock(item: MediaItem, noteTitles: Map<Long, String>): String {
        val caption = flat(item.caption, MEDIA_CAPTION_CHARS)
        val ocr = flat(item.ocrText, MEDIA_OCR_CHARS)
        if (caption.isEmpty() && ocr.isEmpty()) return ""
        return buildString {
            append("[Image]\n")
            if (caption.isNotEmpty()) append("caption: ").append(caption).append('\n')
            if (ocr.isNotEmpty()) append("text_in_image: ").append(ocr).append('\n')
            val linked = item.noteIds.mapNotNull { noteTitles[it] }.filter { it.isNotEmpty() }
            if (linked.isNotEmpty()) {
                append("linked_notes: ").append(flat(linked.joinToString("; "), 200)).append('\n')
            }
        }
    }

    /** One note as a compact, line-per-field block. */
    private fun noteBlock(note: StoredNote): String = buildString {
        append(flat(note.title, TITLE_CHARS).ifBlank { "(untitled)" })
        append('\n')
        append("mode: ").append(flat(note.mode, 40).ifBlank { "Insight" }).append('\n')

        val oneLine = flat(note.oneLine, ONE_LINE_CHARS)
        if (oneLine.isNotEmpty()) append("one_line: ").append(oneLine).append('\n')

        // A list note's one_line is only a count, so the actual items have to travel too.
        if (note.items.isNotEmpty()) {
            append("items: ").append(flat(note.items.joinToString("; "), ITEMS_CHARS)).append('\n')
        }

        val why = flat(note.why, WHY_CHARS)
        if (why.isNotEmpty()) append("why: ").append(why).append('\n')

        val said = flat(note.verbatim, VERBATIM_CHARS)
        if (said.isNotEmpty()) append("said: ").append(said).append('\n')

        if (note.followUps.isNotEmpty()) {
            val thread = flat(note.followUps.joinToString(" | ") { it.text }, FOLLOWUPS_CHARS)
            if (thread.isNotEmpty()) append("follow_ups: ").append(thread).append('\n')
        }
    }

    /** Collapses whitespace and strips ChatML delimiters, then trims to [max] characters. */
    private fun flat(text: String, max: Int): String {
        val one = sanitizeChatMl(text).replace(WHITESPACE_REGEX, " ").trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "\u2026"
    }

    private fun sanitizeChatMl(text: String): String =
        text.replace("<|im_start|>", "").replace("<|im_end|>", "")

    /**
     * The prompt ends in `##`, so the reply is the remainder of the first heading. Repair the
     * two ways a small model breaks that: a repeated heading level (`####`), or no space after
     * the prefill (`##Where`). Trailing ChatML control tokens are dropped. This repairs heading
     * syntax only: whether the model emitted the sections the spec asked for is not rewritten
     * here, because inventing a missing section would mean inventing its content.
     */
    private fun assemble(raw: String): String {
        var body = "##" + raw.trimStart('\n').trimEnd()
        body = body.replace(EXTRA_HEADING_HASHES_REGEX, "## ")
        body = body.replace(TIGHT_HEADING_REGEX, "## ")
        body = body.replace("<|im_end|>", "").replace("<|im_start|>", "")
        return body.trim()
    }

    /**
     * Removes the inline note handles older reports carry (`[n12]`), together with the spacing
     * they leave behind. The prompt no longer asks for citations, but artifacts already on disk
     * were written under it, so the UI runs this before rendering as well.
     */
    fun stripCitations(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace(CITATION_TAG_REGEX, "")
            .replace(EMPTY_PARENS_REGEX, "")
            .replace(SPACE_BEFORE_PUNCT_REGEX) { it.groupValues[1] }
            .replace(MULTI_SPACE_REGEX, " ")
    }
}
