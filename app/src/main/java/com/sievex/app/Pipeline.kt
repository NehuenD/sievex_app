package com.sievex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PipelineStage { IDLE, TRANSCRIBING, TRANSFORMING, DONE, FAILED }

/**
 * Observable state of the background pipeline. The service writes, the UI reads.
 *
 * [step] is the action inside the transform, reported by the native decode loop as the JSON
 * fields appear, so the status card can name what the phone is doing instead of naming a model.
 * Kept in memory only: it describes the current burst, not history.
 */
object Pipeline {

    var stage by mutableStateOf(PipelineStage.IDLE)
        private set

    var detail by mutableStateOf("")
        private set

    var lastTitle by mutableStateOf("")
        private set

    /** One action label inside [PipelineStage.TRANSFORMING]; blank when not transforming. */
    var step by mutableStateOf("")
        private set

    /**
     * True when the current failure is a hold rather than a lost burst: the job is still
     * queued and drains on its own once the missing model is installed. Only [block] sets
     * this; every other transition, and [reset], clears it.
     */
    var blocked by mutableStateOf(false)
        private set

    fun transcribing() {
        stage = PipelineStage.TRANSCRIBING
        detail = ""
        step = ""
        blocked = false
    }

    fun transforming() {
        stage = PipelineStage.TRANSFORMING
        detail = ""
        step = "Understanding intent"
        blocked = false
    }

    /**
     * Moves [step] for the phase the native side just reached. Unknown numbers are ignored, so a
     * native change can never blank the label.
     */
    fun transformingStep(phase: Int) {
        stage = PipelineStage.TRANSFORMING
        STEP_LABELS[phase]?.let { step = it }
    }

    fun done(title: String) {
        stage = PipelineStage.DONE
        lastTitle = title
        detail = ""
        step = ""
        blocked = false
    }

    fun failed(reason: String) {
        stage = PipelineStage.FAILED
        detail = reason
        step = ""
        blocked = false
    }

    /**
     * A failure that is really a hold: nothing was lost, the burst waits for a model.
     * Unlike [failed], the UI clears this state on its own once both models are installed.
     */
    fun block(reason: String) {
        stage = PipelineStage.FAILED
        detail = reason
        step = ""
        blocked = true
    }

    fun reset() {
        stage = PipelineStage.IDLE
        detail = ""
        lastTitle = ""
        step = ""
        blocked = false
    }

    /**
     * Phase numbers come from `LlamaBridge.TransformProgress`: 1 is the start of generation,
     * then the grammar-pinned field keys (`title`, `one_line`, `why_it_matters`, `tags`).
     */
    private val STEP_LABELS = mapOf(
        1 to "Understanding intent",
        2 to "Crafting title",
        3 to "Writing the summary",
        4 to "Creating insights",
        5 to "Adding tags"
    )
}
