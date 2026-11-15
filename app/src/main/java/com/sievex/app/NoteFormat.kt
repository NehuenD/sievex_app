package com.sievex.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The shape a note is written in.
 *
 * It is chosen before a burst and can be changed later by reanalyzing the note: it decides the
 * prompt, the JSON grammar and how the note is displayed. [PLAIN] is the one format that never
 * calls the model at all — it keeps the words exactly as dictated.
 */
enum class NoteFormat(
    val id: String,
    val label: String,
    val blurb: String,
    val usesLlm: Boolean,
    /** Steps are the same JSON as a list, but displayed numbered and prompted as ordered actions. */
    val ordered: Boolean = false
) {
    NOTE("note", "Note", "Summary, insight, question and tags", true),
    LIST("list", "List", "Short items, one per bullet", true),
    STEPS("steps", "Steps", "Ordered steps, one per line", true, ordered = true),
    PLAIN("plain", "Plain", "What you said, nothing added", false);

    companion object {
        fun from(id: String?): NoteFormat = entries.firstOrNull { it.id == id } ?: NOTE
    }
}

/**
 * The format the next capture will be shaped in.
 *
 * Process-wide on purpose: a burst can start from the podcast pause with no screen involved, so
 * the service has to be able to read the selection without an Activity. It is persisted because a
 * podcast-triggered burst can also arrive after a process death, when no screen has restored it —
 * falling back to [NoteFormat.NOTE] there would silently reshape a Plain user's dictation.
 */
object Formats {

    private const val PREFS = "sievex_formats"
    private const val KEY = "next_format"

    var next by mutableStateOf(NoteFormat.NOTE)
        private set

    /** Loads the persisted choice. Safe to call from any component at startup. */
    fun restore(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        next = NoteFormat.from(stored)
    }

    /** Selects the format for the next capture and remembers it across process restarts. */
    fun select(context: Context, format: NoteFormat) {
        next = format
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, format.id)
            .apply()
    }
}
