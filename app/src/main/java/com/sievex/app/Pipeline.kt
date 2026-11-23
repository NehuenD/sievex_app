package com.sievex.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PipelineStage { IDLE, TRANSCRIBING, TRANSFORMING, DONE, FAILED }

/**
 * One action inside the transform, as a key rather than a sentence: the UI owns the words, so
 * the status card can be English or Spanish without the service knowing either language.
 */
enum class PipelineStep { INTENT, DICTATION, TITLE, SUMMARY, ITEMS, STEPS, INSIGHTS, TAGS, SAVING }

/**
 * Observable state of the background pipeline. The service writes, the UI reads.
 *
 * [step] is reported by the native decode loop as the JSON fields appear, so the status card can
 * name what the phone is doing instead of naming a model. The label depends on the format: a list
 * is "extracting items", a note is "writing the summary". Kept in memory only: it describes the
 * current burst, not history.
 */
object Pipeline {

    var stage by mutableStateOf(PipelineStage.IDLE)
        private set

    var detail by mutableStateOf("")
        private set

    var lastTitle by mutableStateOf("")
        private set

    var lastNoteId by mutableStateOf<Long?>(null)
        private set

    /** One action inside [PipelineStage.TRANSFORMING]; null when not transforming. */
    var step by mutableStateOf<PipelineStep?>(null)
        private set

    /**
     * True when the current failure is a hold rather than a lost burst: the job is still
     * queued and drains on its own once the missing model is installed. Only [block] sets
     * this; every other transition, and [reset], clears it.
     */
    var blocked by mutableStateOf(false)
        private set

    /** The format being shaped, so phase numbers map to the right step. */
    @Volatile
    private var stepFormat = NoteFormat.NOTE

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun transcribing() {
        onMain {
            stage = PipelineStage.TRANSCRIBING
            detail = ""
            step = null
            blocked = false
        }
    }

    fun transforming(format: NoteFormat = NoteFormat.NOTE) {
        stepFormat = format
        onMain {
            stage = PipelineStage.TRANSFORMING
            detail = ""
            step = stepFor(format, 1)
            blocked = false
        }
    }

    /**
     * Moves [step] for the phase the native side just reached. Unknown numbers are ignored, so a
     * native change can never blank the step.
     */
    fun transformingStep(phase: Int) {
        val next = stepFor(stepFormat, phase)
        onMain {
            stage = PipelineStage.TRANSFORMING
            next?.let { step = it }
        }
    }

    fun done(title: String, noteId: Long? = null) {
        onMain {
            stage = PipelineStage.DONE
            lastTitle = title
            lastNoteId = noteId
            detail = ""
            step = null
            blocked = false
        }
    }

    fun failed(reason: String) {
        onMain {
            stage = PipelineStage.FAILED
            detail = reason
            step = null
            blocked = false
        }
    }

    /**
     * A failure that is really a hold: nothing was lost, the burst waits for a model.
     * Unlike [failed], the UI clears this state on its own once both models are installed.
     * The reason is already localised by the caller, which has a Context.
     */
    fun block(reason: String) {
        onMain {
            stage = PipelineStage.FAILED
            detail = reason
            step = null
            blocked = true
        }
    }

    fun reset() {
        onMain {
            stage = PipelineStage.IDLE
            detail = ""
            lastTitle = ""
            lastNoteId = null
            step = null
            blocked = false
        }
    }

    /**
     * Phase numbers come from `LlamaBridge.TransformProgress`: 1 is the start of generation, then
     * the grammar-pinned field keys (`title`, `one_line` or `items`, `why_it_matters`, `tags`).
     */
    private fun stepFor(format: NoteFormat, phase: Int): PipelineStep? = when (phase) {
        1 -> when (format) {
            NoteFormat.LIST, NoteFormat.STEPS -> PipelineStep.DICTATION
            NoteFormat.PLAIN -> PipelineStep.SAVING
            else -> PipelineStep.INTENT
        }
        2 -> PipelineStep.TITLE
        3 -> when (format) {
            NoteFormat.LIST -> PipelineStep.ITEMS
            NoteFormat.STEPS -> PipelineStep.STEPS
            else -> PipelineStep.SUMMARY
        }
        4 -> PipelineStep.INSIGHTS
        5 -> PipelineStep.TAGS
        else -> null
    }
}
