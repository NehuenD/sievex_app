package com.sievex.app.llm

import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises every call into [LlamaBridge].
 *
 * The native handle keeps one loaded model and one KV cache, so two inference passes at once
 * (a lens over a space while a new note is being transformed, say) would corrupt each other.
 * Both [Transform] and [Lens] run their whole load-and-complete sequence inside [exclusive];
 * nothing else may touch those natives.
 */
object LlmGate {

    private val lock = ReentrantLock()

    fun <T> exclusive(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
