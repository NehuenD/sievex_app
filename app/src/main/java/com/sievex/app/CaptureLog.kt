package com.sievex.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer mirrored to logcat, so the UI can show what the phone actually saw. */
object CaptureLog {
    private const val TAG = "Sievex"
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > MAX) lines.removeFirst()
        Log.i(TAG, msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
