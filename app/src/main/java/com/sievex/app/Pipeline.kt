package com.sievex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PipelineStage { IDLE, TRANSCRIBING, TRANSFORMING, DONE, FAILED }

/**
 * Observable state of the background pipeline. The service writes, the UI reads.
 * Kept in memory only: it describes the current burst, not history.
 */
object Pipeline {

    var stage by mutableStateOf(PipelineStage.IDLE)
        private set

    var detail by mutableStateOf("")
        private set

    var lastTitle by mutableStateOf("")
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
        blocked = false
    }

    fun transforming() {
        stage = PipelineStage.TRANSFORMING
        detail = ""
        blocked = false
    }

    fun done(title: String) {
        stage = PipelineStage.DONE
        lastTitle = title
        detail = ""
        blocked = false
    }

    fun failed(reason: String) {
        stage = PipelineStage.FAILED
        detail = reason
        blocked = false
    }

    /**
     * A failure that is really a hold: nothing was lost, the burst waits for a model.
     * Unlike [failed], the UI clears this state on its own once both models are installed.
     */
    fun block(reason: String) {
        stage = PipelineStage.FAILED
        detail = reason
        blocked = true
    }

    fun reset() {
        stage = PipelineStage.IDLE
        detail = ""
        lastTitle = ""
        blocked = false
    }
}
