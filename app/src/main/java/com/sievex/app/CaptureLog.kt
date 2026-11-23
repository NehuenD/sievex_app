package com.sievex.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer mirrored to logcat, so the UI can show what the phone actually saw. */
object CaptureLog {
    private const val TAG = "Sievex"
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > MAX) lines.removeFirst()
        _version.value = _version.value + 1L
        Log.i(TAG, msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        _version.value = _version.value + 1L
    }
}
