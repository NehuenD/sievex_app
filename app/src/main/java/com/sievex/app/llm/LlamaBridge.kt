package com.sievex.app.llm

import java.nio.charset.StandardCharsets

/**
 * Thin JNI surface over llama.cpp.
 *
 * The native side keeps one model, one context and one KV cache for the process lifetime.
 * [LlmGate] guarantees a single caller at a time, which is what makes the cache safe to share.
 * Every call reuses the longest common prefix between the new prompt and the previous one, so the
 * ChatML system turn is paid for once instead of on every burst.
 */
object LlamaBridge {

    init {
        System.loadLibrary("sievex_llm")
    }

    /** Loads the model and its context once. Safe and cheap to call on every job. */
    external fun nativeLoad(path: String): Boolean

    /**
     * Generates at most [maxTokens] tokens after [prompt].
     *
     * Both the prompt and the grammar cross as UTF-8 bytes, not `jstring`: `GetStringUTFChars`
     * produces Modified UTF-8, which encodes characters outside the BMP as CESU-8 and would make
     * the tokenizer see an emoji in a note as surrogate halves.
     *
     * @param threads worker pool for single-token generation.
     * @param threadsBatch worker pool for prompt processing (prefill), which scales further.
     * @param grammar GBNF grammar for the reply, as UTF-8; empty means unconstrained.
     * @return raw token bytes. The caller must decode them as UTF-8.
     */
    external fun nativeComplete(
        prompt: ByteArray,
        maxTokens: Int,
        threads: Int,
        threadsBatch: Int,
        grammar: ByteArray
    ): ByteArray?

    /** Drops cached KV state but keeps the model loaded. */
    external fun nativeReset()

    /** Frees the model, the context and the cache. */
    external fun nativeUnload()

    /**
     * Generates and decodes the reply.
     *
     * Token pieces are raw bytes and may split a multi-byte UTF-8 sequence, so the reply is decoded
     * here with the platform decoder, which substitutes U+FFFD for malformed input instead of
     * handing ART invalid Modified UTF-8 through `NewStringUTF`.
     */
    fun complete(
        prompt: String,
        maxTokens: Int,
        threads: Int = decodeThreads(),
        threadsBatch: Int = batchThreads(),
        grammar: String = ""
    ): String {
        val promptBytes = prompt.toByteArray(StandardCharsets.UTF_8)
        val grammarBytes = if (grammar.isEmpty()) EMPTY else grammar.toByteArray(StandardCharsets.UTF_8)
        val bytes = runCatching {
            nativeComplete(promptBytes, maxTokens, threads, threadsBatch, grammarBytes)
        }.getOrNull() ?: return ""
        return String(bytes, StandardCharsets.UTF_8)
    }

    private val EMPTY = ByteArray(0)

    /**
     * Decode is bandwidth-bound and stops scaling early; prefill is matmul-bound and does not.
     * These are starting points for the on-device sweep, clamped to the core count so a small
     * device never oversubscribes.
     */
    fun decodeThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    fun batchThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
}
