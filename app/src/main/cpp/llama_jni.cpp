#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "SievexLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * One model, one persistent context, one KV cache.
 *
 * Every call used to build and destroy a llama_context, which meant a fresh KV cache, a fresh
 * scheduler reservation and a full re-prefill of the ChatML system prompt on every burst. Because
 * LlmGate serialises all callers, exactly one context is safe to keep, and the KV cache can be
 * trimmed to the longest common prefix between the previous prompt and the new one. For a
 * Transform the prefix is the system turn (~200 tokens); for a Lens it is the whole instruction
 * plus the space header.
 *
 * The cache is only ever mutated under g_mutex, so g_cache_tokens always describes what the KV
 * actually holds (positions 0 .. n-1, one sequence).
 */

namespace {

constexpr int32_t kCtxInitial = 2560;  // covers Lens (~1500 prompt + 500 output) with headroom
constexpr int32_t kCtxMax = 4096;      // hard ceiling: KV growth must never be able to OOM the app
constexpr int32_t kCtxStep = 512;
constexpr int32_t kBatch = 2048;
constexpr int32_t kUBatch = 512;
constexpr int32_t kMaxDecodeThreads = 4;
constexpr int32_t kMaxBatchThreads = 6;

std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
llama_batch_ext * g_batch = nullptr;
std::string g_model_path;
std::vector<llama_token> g_cache_tokens;  // tokens resident in KV, positions 0..size-1
int32_t g_n_ctx = 0;

void destroy_context() {
    if (g_batch != nullptr) {
        llama_batch_ext_free(g_batch);
        g_batch = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    g_cache_tokens.clear();
    g_n_ctx = 0;
}

bool create_context(int32_t n_ctx) {
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = kBatch;
    cparams.n_ubatch = kUBatch;
    // The CPU backend sizes its thread pools here; llama_set_n_threads can only lower them later.
    // Create the largest pools the app might ask for, then dial per phase.
    cparams.n_threads = kMaxDecodeThreads;
    cparams.n_threads_batch = kMaxBatchThreads;
    // Only ever one token carries logits (see process_range / process_one). Left at 0 this means
    // n_batch outputs, i.e. a logits buffer of 2048 x vocab floats — roughly a gigabyte on a
    // 128k-vocab model, and now resident for the whole process instead of per call.
    cparams.n_outputs_max = 1;
    // flash_attn_type is deliberately left at its default (AUTO): the first audit pass claimed it
    // was disabled, which the header contradicts, and forcing ENABLED changes numerics. It is a
    // measured A/B, not a free win.

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("context init failed (n_ctx=%d)", n_ctx);
        return false;
    }
    g_batch = llama_batch_ext_init(g_ctx);
    if (g_batch == nullptr) {
        LOGE("batch init failed");
        llama_free(g_ctx);
        g_ctx = nullptr;
        return false;
    }
    g_n_ctx = n_ctx;
    g_cache_tokens.clear();
    LOGI("context ready: n_ctx=%d n_batch=%d n_ubatch=%d pools=%d/%d",
         n_ctx, kBatch, kUBatch, kMaxDecodeThreads, kMaxBatchThreads);
    return true;
}

bool ensure_capacity(int32_t needed) {
    if (needed <= g_n_ctx) return true;
    int32_t next = ((needed + kCtxStep - 1) / kCtxStep) * kCtxStep;
    if (next < kCtxInitial) next = kCtxInitial;
    if (next > kCtxMax) {
        // Better one failed transform (the note falls back to its raw transcript) than a KV that
        // grows until the phone kills the process mid-walk.
        LOGE("prompt needs n_ctx=%d, above the %d ceiling", next, kCtxMax);
        return false;
    }
    LOGI("growing context %d -> %d", g_n_ctx, next);
    destroy_context();
    return create_context(next);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    if (text.empty()) return {};
    int32_t needed = llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (needed == 0) return {};
    const int32_t cap = needed < 0 ? -needed : needed;
    std::vector<llama_token> tokens(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()),
            tokens.data(), cap, true, true);
    if (n < 0) {
        LOGE("tokenize failed: %d", n);
        return {};
    }
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

/** Feeds tokens [begin, end) into the context, logits only on the very last one. */
bool process_range(const std::vector<llama_token> & tokens, int32_t begin, int32_t end) {
    for (int32_t chunk = begin; chunk < end; chunk += kBatch) {
        const int32_t chunk_end = (chunk + kBatch < end) ? chunk + kBatch : end;
        llama_batch_ext_clear(g_batch);
        for (int32_t i = chunk; i < chunk_end; i++) {
            const int32_t idx = llama_batch_ext_add_token(g_batch, 0, tokens[static_cast<size_t>(i)]);
            if (idx < 0) {
                LOGE("batch add failed at %d (token %d)", i, tokens[static_cast<size_t>(i)]);
                return false;
            }
            llama_pos pos = i;
            llama_batch_ext_set_pos(g_batch, idx, &pos);
        }
        const bool last_chunk = (chunk_end == end);
        llama_batch_ext_set_output_logits(g_batch, chunk_end - chunk - 1, last_chunk);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch) != 0) {
            LOGE("process failed on [%d,%d)", chunk, chunk_end);
            return false;
        }
    }
    return true;
}

/** Feeds exactly one token at [pos], requesting its logits. */
bool process_one(llama_token token, int32_t pos) {
    llama_batch_ext_clear(g_batch);
    const int32_t idx = llama_batch_ext_add_token(g_batch, 0, token);
    if (idx < 0) return false;
    llama_pos p = pos;
    llama_batch_ext_set_pos(g_batch, idx, &p);
    llama_batch_ext_set_output_logits(g_batch, idx, true);
    return llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch) == 0;
}

void append_piece(std::string & out, const llama_vocab * vocab, llama_token id) {
    char buf[512];
    int32_t n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
    if (n > 0) {
        out.append(buf, static_cast<size_t>(n));
        return;
    }
    if (n < 0) {
        // Buffer too small: the API reports the required size as -n.
        const int32_t need = -n;
        std::string tmp(static_cast<size_t>(need), '\0');
        const int32_t m = llama_token_to_piece(vocab, id, tmp.data(), need, 0, false);
        if (m > 0) out.append(tmp.data(), static_cast<size_t>(m));
    }
}

jbyteArray to_byte_array(JNIEnv * env, const std::string & text) {
    // Token pieces are raw bytes and may split a UTF-8 sequence (or contain a 4-byte scalar that
    // Modified UTF-8 cannot carry). Returning bytes and decoding with the platform UTF-8 decoder
    // in Kotlin replaces malformed input instead of handing ART invalid Modified UTF-8.
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(text.size()));
    if (arr == nullptr) return nullptr;
    if (!text.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte *>(text.data()));
    }
    return arr;
}

jbyteArray empty_bytes(JNIEnv * env) {
    return env->NewByteArray(0);
}

std::string get_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

/** Reads a UTF-8 byte array. Prompts arrive this way so non-BMP text is not CESU-8 mangled. */
std::string get_bytes(JNIEnv * env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize n = env->GetArrayLength(value);
    if (n <= 0) return {};
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

/**
 * Reports coarse generation phases to Kotlin while the call is still blocking.
 *
 * The grammar pins the field order, so the keys appearing in the output are honest checkpoints:
 * mode, then title, then one_line, then why_it_matters, then tags. The UI turns them into
 * "Understanding intent", "Crafting title", "Creating insights" without inventing progress.
 */
struct PhaseReporter {
    JNIEnv * env = nullptr;
    jobject target = nullptr;
    jmethodID method = nullptr;
    int last = 0;

    PhaseReporter(JNIEnv * e, jobject t) : env(e), target(t) {
        if (target == nullptr) return;
        jclass cls = env->GetObjectClass(target);
        if (cls == nullptr) {
            env->ExceptionClear();
            return;
        }
        method = env->GetMethodID(cls, "onPhase", "(I)V");
        if (method == nullptr) {
            LOGE("phase listener has no onPhase(I)V; progress will stay on the first label");
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
    }

    void emit(int phase) {
        if (method == nullptr || target == nullptr || phase <= last) return;
        last = phase;
        env->CallVoidMethod(target, method, phase);
        if (env->ExceptionCheck()) {
            // A listener that throws must not poison the JNI call or leak into the next one.
            env->ExceptionClear();
            method = nullptr;
        }
    }
};

void log_perf(int32_t n_prompt, int32_t n_keep, int32_t n_generated) {
    const llama_perf_context_data perf = llama_perf_context(g_ctx);
    const double pp_s = perf.t_p_eval_ms > 0.0 ? perf.t_p_eval_ms / 1000.0 : 0.0;
    const double tg_s = perf.t_eval_ms > 0.0 ? perf.t_eval_ms / 1000.0 : 0.0;
    LOGI("pp %d tok (kept %d/%d) %.0f ms %.1f tok/s | tg %d tok %.0f ms %.1f tok/s | "
         "reused %d realloc %d/%d",
         perf.n_p_eval, n_keep, n_prompt, perf.t_p_eval_ms,
         pp_s > 0.0 ? perf.n_p_eval / pp_s : 0.0,
         perf.n_eval, perf.t_eval_ms,
         tg_s > 0.0 ? perf.n_eval / tg_s : 0.0,
         perf.n_reused, perf.n_reallocs_same, perf.n_reallocs_diff);
    (void) n_generated;
}

}  // namespace

// A C++ exception must never cross the JNI boundary: an allocation failure while a 1.1 GB model is
// resident would otherwise abort ART instead of failing one transform.
static jboolean load_locked(JNIEnv * env, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_mutex);

    const std::string path = get_string(env, jpath);
    if (path.empty()) {
        LOGE("model path empty");
        return JNI_FALSE;
    }

    // A different path must never silently reuse the previous model.
    if (g_model != nullptr && g_model_path != path) {
        destroy_context();
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
    }
    if (g_model != nullptr) {
        if (g_ctx != nullptr) return JNI_TRUE;
        // A previous capacity growth failed on a low-memory device. Reporting "loaded" here would
        // leave every later transform silently returning nothing, so rebuild instead.
        LOGI("model present but context missing; rebuilding");
        return create_context(kCtxInitial) ? JNI_TRUE : JNI_FALSE;
    }

    llama_backend_init();

    auto started = std::chrono::steady_clock::now();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), params);
    if (g_model == nullptr) {
        LOGE("model load failed: %s", path.c_str());
        return JNI_FALSE;
    }
    g_model_path = path;
    const double load_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    LOGI("model loaded in %.0f ms", load_ms);

    if (!create_context(kCtxInitial)) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring jpath) {
    try {
        return load_locked(env, jpath);
    } catch (const std::exception & e) {
        LOGE("load failed: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("load failed: unknown exception");
        return JNI_FALSE;
    }
}

static jbyteArray complete_locked(
        JNIEnv * env, jbyteArray jprompt, jint maxTokens, jint nThreads,
        jint nThreadsBatch, jbyteArray jgrammar, jobject jprogress) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr) return empty_bytes(env);
    if (g_ctx == nullptr) {
        LOGE("complete without a context; nativeLoad must succeed first");
        return empty_bytes(env);
    }
    if (maxTokens <= 0) return empty_bytes(env);

    const std::string prompt = get_bytes(env, jprompt);
    const std::string grammar = get_bytes(env, jgrammar);
    if (prompt.empty()) return empty_bytes(env);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens = tokenize(vocab, prompt);
    if (tokens.empty()) {
        LOGE("prompt tokenized to nothing");
        return empty_bytes(env);
    }
    const int32_t n_prompt = static_cast<int32_t>(tokens.size());

    if (n_prompt > kBatch) {
        LOGI("prompt %d tokens exceeds batch %d; prefill will be chunked", n_prompt, kBatch);
    }
    if (!ensure_capacity(n_prompt + maxTokens + 8)) {
        return empty_bytes(env);
    }

    llama_memory_t mem = llama_get_memory(g_ctx);

    // ---- reuse the longest common prefix that is already in the KV cache -------------------
    int32_t lcp = 0;
    const int32_t cached = static_cast<int32_t>(g_cache_tokens.size());
    const int32_t limit = n_prompt < cached ? n_prompt : cached;
    while (lcp < limit && g_cache_tokens[static_cast<size_t>(lcp)] == tokens[static_cast<size_t>(lcp)]) {
        lcp++;
    }
    // The last prompt token must always be evaluated to produce logits for the first sample.
    int32_t n_keep = lcp;
    if (n_keep > n_prompt - 1) n_keep = n_prompt - 1;
    if (n_keep < 0) n_keep = 0;

    if (n_keep > 0) {
        if (!llama_memory_seq_rm(mem, 0, n_keep, -1)) {
            // Hybrid / recurrent caches cannot drop a suffix; fall back to a clean cache.
            LOGI("partial KV trim refused; clearing cache");
            llama_memory_clear(mem, false);
            n_keep = 0;
        }
    } else {
        llama_memory_clear(mem, false);
    }
    g_cache_tokens.resize(static_cast<size_t>(n_keep));

    llama_perf_context_reset(g_ctx);

    // ---- prefill the tail -------------------------------------------------------------------
    // Batch work uses the batch pool; asking the decode pool for kMaxBatchThreads threads would
    // exceed the pool created at init.
    llama_set_n_threads(g_ctx, nThreads, nThreadsBatch);
    if (!process_range(tokens, n_keep, n_prompt)) {
        llama_memory_clear(mem, false);
        g_cache_tokens.clear();
        return empty_bytes(env);
    }
    for (int32_t i = n_keep; i < n_prompt; i++) {
        g_cache_tokens.push_back(tokens[static_cast<size_t>(i)]);
    }

    // ---- sample -----------------------------------------------------------------------------
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (smpl == nullptr) {
        return empty_bytes(env);
    }
    if (!grammar.empty()) {
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grammar_sampler != nullptr) {
            llama_sampler_chain_add(smpl, grammar_sampler);
        } else {
            LOGE("grammar rejected; sampling unconstrained");
        }
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_set_n_threads(g_ctx, nThreads, nThreadsBatch);

    std::string out;
    int32_t depth = 0;
    bool opened = false;
    bool in_string = false;
    bool escaped = false;
    int32_t n_generated = 0;
    PhaseReporter progress(env, jprogress);
    progress.emit(1);
    for (int32_t i = 0; i < maxTokens; i++) {
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (id < 0) break;
        if (llama_vocab_is_eog(vocab, id)) break;

        const size_t piece_begin = out.size();
        append_piece(out, vocab, id);
        // Brace depth only counts outside JSON strings, so a '}' inside a title or one_line
        // cannot be mistaken for the end of the object.
        for (size_t c = piece_begin; c < out.size(); c++) {
            const char ch = out[c];
            if (in_string) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    in_string = false;
                }
            } else if (ch == '"') {
                in_string = true;
            } else if (ch == '{') {
                depth++;
                opened = true;
            } else if (ch == '}') {
                // A stray '}' before the object opens (possible on an unconstrained retry) must
                // not drive the counter negative and truncate at the next '}{'.
                if (depth > 0) depth--;
            }
        }
        n_generated++;

        // Field order is fixed by the grammar, so the keys are honest checkpoints.
        if (out.find("\"title\"") != std::string::npos) progress.emit(2);
        if (out.find("\"one_line\"") != std::string::npos) progress.emit(3);
        if (out.find("\"why_it_matters\"") != std::string::npos) progress.emit(4);
        if (out.find("\"tags\"") != std::string::npos) progress.emit(5);

        // A complete top-level object ends the generation; the token is not fed back, so it must
        // not enter the KV-cache bookkeeping either.
        if (opened && depth <= 0) break;

        if (!process_one(id, static_cast<int32_t>(g_cache_tokens.size()))) {
            llama_memory_clear(mem, false);
            g_cache_tokens.clear();
            break;
        }
        g_cache_tokens.push_back(id);
    }

    log_perf(n_prompt, n_keep, n_generated);

    llama_sampler_free(smpl);
    return to_byte_array(env, out);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeComplete(
        JNIEnv * env, jobject, jbyteArray jprompt, jint maxTokens, jint nThreads,
        jint nThreadsBatch, jbyteArray jgrammar, jobject jprogress) {
    try {
        return complete_locked(env, jprompt, maxTokens, nThreads, nThreadsBatch, jgrammar, jprogress);
    } catch (const std::exception & e) {
        LOGE("complete failed: %s", e.what());
        return empty_bytes(env);
    } catch (...) {
        LOGE("complete failed: unknown exception");
        return empty_bytes(env);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeReset(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), false);
    }
    g_cache_tokens.clear();
    LOGI("cache reset");
}

extern "C" JNIEXPORT void JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    destroy_context();
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGI("model unloaded");
    }
    g_model_path.clear();
}
