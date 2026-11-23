#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#define LOG_TAG "SievexLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * One model, one persistent context, cached system-turn state, and big-core threadpools.
 *
 * Hybrid/recurrent models (such as LFM3 shortconv + ISWA) refuse partial KV suffix trims via
 * `llama_memory_seq_rm`, so naive LCP prefix reuse falls back to clearing the entire cache and
 * re-prefilling the system prompt on every call. Instead, we snapshot sequence 0 after prefilling
 * the ChatML system turn (`llama_state_seq_get_data`) and restore it (`llama_state_seq_set_data`)
 * before prefilling only the user turn.
 */

namespace {

constexpr int32_t kCtxInitial = 2560;  // covers Lens (~1500 prompt + 500 output) with headroom
constexpr int32_t kCtxMax = 4096;      // hard ceiling: KV growth must never be able to OOM the app
constexpr int32_t kCtxStep = 512;
constexpr int32_t kBatch = 2048;
constexpr int32_t kUBatch = 512;
constexpr int32_t kMaxDecodeThreads = 4;
constexpr int32_t kMaxBatchThreads = 6;
constexpr size_t kMaxPrefixEntries = 8;

using ThreadpoolNewFn = ggml_threadpool_t (*)(struct ggml_threadpool_params *);
using ThreadpoolFreeFn = void (*)(ggml_threadpool_t);

struct PrefixEntry {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
    uint64_t last_used = 0;
};

std::mutex g_mutex;
bool g_backends_loaded = false;
std::string g_native_lib_dir;
std::string g_last_error;

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
llama_batch_ext * g_batch = nullptr;
ggml_threadpool_t g_tp_decode = nullptr;
ggml_threadpool_t g_tp_batch = nullptr;
ThreadpoolNewFn g_tp_new = nullptr;
ThreadpoolFreeFn g_tp_free = nullptr;

std::string g_model_path;
std::vector<llama_token> g_cache_tokens;  // tokens resident in KV, positions 0..size-1
int32_t g_n_ctx = 0;
std::unordered_map<std::string, PrefixEntry> g_prefix_cache;
uint64_t g_prefix_tick = 0;
std::unordered_map<std::string, llama_sampler *> g_sampler_cache;

struct BigCoreInfo {
    bool probed = false;
    bool mask[GGML_MAX_N_THREADS] = {};
    int count = 0;
};

BigCoreInfo g_big_cores;

void set_error(const std::string & msg) {
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

void probe_big_cores() {
    if (g_big_cores.probed) return;
    g_big_cores.probed = true;

    long freqs[64] = {};
    int n_cpus = 0;
    long min_freq = -1;
    long max_freq = -1;

    for (int cpu = 0; cpu < 64; cpu++) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream f(path);
        if (!f.is_open()) {
            if (cpu > 0) break;
            continue;
        }
        long freq = 0;
        f >> freq;
        if (freq <= 0) continue;
        freqs[cpu] = freq;
        n_cpus = cpu + 1;
        if (min_freq < 0 || freq < min_freq) min_freq = freq;
        if (freq > max_freq) max_freq = freq;
    }

    if (n_cpus > 0 && max_freq > min_freq) {
        for (int cpu = 0; cpu < n_cpus && cpu < GGML_MAX_N_THREADS; cpu++) {
            if (freqs[cpu] > min_freq) {
                g_big_cores.mask[cpu] = true;
                g_big_cores.count++;
            }
        }
        LOGI("big-core mask: %d/%d cores above %ld kHz (max %ld kHz)",
             g_big_cores.count, n_cpus, min_freq, max_freq);
    }
}

bool ensure_backends_loaded() {
    if (g_backends_loaded && ggml_backend_reg_by_name("CPU") != nullptr) {
        return true;
    }
    if (!g_native_lib_dir.empty()) {
        LOGI("loading ggml backends from %s", g_native_lib_dir.c_str());
        ggml_backend_load_all_from_path(g_native_lib_dir.c_str());
    } else {
        LOGI("loading ggml backends from default search path");
        ggml_backend_load_all();
    }

    const size_t n_reg = ggml_backend_reg_count();
    const size_t n_dev = ggml_backend_dev_count();
    ggml_backend_reg_t cpu_reg = ggml_backend_reg_by_name("CPU");
    if (cpu_reg != nullptr && n_dev > 0) {
        g_backends_loaded = true;
        g_last_error.clear();
        LOGI("ggml backends ready: regs=%zu devs=%zu cpu=%s",
             n_reg, n_dev, ggml_backend_reg_name(cpu_reg));
        return true;
    }

    set_error("no ggml CPU backend loaded (dir=" +
              (g_native_lib_dir.empty() ? std::string("<empty>") : g_native_lib_dir) +
              ", regs=" + std::to_string(n_reg) + ", devs=" + std::to_string(n_dev) + ")");
    return false;
}

void resolve_threadpool_api() {
    if (g_tp_new != nullptr && g_tp_free != nullptr) return;
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    if (reg == nullptr) {
        LOGI("CPU backend reg not found; using default threadpools");
        return;
    }
    g_tp_new = reinterpret_cast<ThreadpoolNewFn>(
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
    g_tp_free = reinterpret_cast<ThreadpoolFreeFn>(
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
}

ggml_threadpool_t make_threadpool(int32_t n_threads) {
    if (g_tp_new == nullptr || g_tp_free == nullptr) return nullptr;
    struct ggml_threadpool_params params = ggml_threadpool_params_default(n_threads);
    // Keep normal scheduling policy: GGML_SCHED_PRIO_HIGH asks for SCHED_FIFO which always
    // fails with EPERM on unrooted Android.
    params.prio = GGML_SCHED_PRIO_NORMAL;
    if (g_big_cores.count >= n_threads) {
        std::memcpy(params.cpumask, g_big_cores.mask, sizeof(params.cpumask));
        params.strict_cpu = true;
    }
    return g_tp_new(&params);
}

void clear_samplers() {
    for (auto & kv : g_sampler_cache) {
        if (kv.second != nullptr) {
            llama_sampler_free(kv.second);
        }
    }
    g_sampler_cache.clear();
}

void destroy_threadpools() {
    if (g_tp_free != nullptr) {
        if (g_tp_decode != nullptr) {
            g_tp_free(g_tp_decode);
            g_tp_decode = nullptr;
        }
        if (g_tp_batch != nullptr) {
            g_tp_free(g_tp_batch);
            g_tp_batch = nullptr;
        }
    }
}

void destroy_context() {
    if (g_batch != nullptr) {
        llama_batch_ext_free(g_batch);
        g_batch = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_detach_threadpool(g_ctx);
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    destroy_threadpools();
    g_cache_tokens.clear();
    g_prefix_cache.clear();
    g_n_ctx = 0;
}

bool create_context(int32_t n_ctx) {
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = kBatch;
    cparams.n_ubatch = kUBatch;
    cparams.n_threads = kMaxDecodeThreads;
    cparams.n_threads_batch = kMaxBatchThreads;
    cparams.n_outputs_max = 1;
    // Always single-sequence: cap SWA allocation and halve KV cache memory/bandwidth.
    cparams.swa_full = false;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    bool used_q8_kv = true;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGI("Q8_0 KV context init failed (n_ctx=%d); retrying with F16 KV defaults", n_ctx);
        cparams.swa_full = true;
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        used_q8_kv = false;
        g_ctx = llama_init_from_model(g_model, cparams);
    }
    if (g_ctx == nullptr) {
        set_error("context init failed (n_ctx=" + std::to_string(n_ctx) + ")");
        return false;
    }
    g_batch = llama_batch_ext_init(g_ctx);
    if (g_batch == nullptr) {
        set_error("batch init failed");
        llama_free(g_ctx);
        g_ctx = nullptr;
        return false;
    }

    probe_big_cores();
    resolve_threadpool_api();
    g_tp_decode = make_threadpool(kMaxDecodeThreads);
    g_tp_batch = make_threadpool(kMaxBatchThreads);
    if (g_tp_decode != nullptr || g_tp_batch != nullptr) {
        llama_attach_threadpool(g_ctx, g_tp_decode, g_tp_batch);
    }

    g_n_ctx = n_ctx;
    g_cache_tokens.clear();
    g_prefix_cache.clear();
    LOGI("context ready: n_ctx=%d n_batch=%d n_ubatch=%d kv=%s pools=%d/%d (custom=%d)",
         n_ctx, kBatch, kUBatch, used_q8_kv ? "Q8_0" : "F16",
         kMaxDecodeThreads, kMaxBatchThreads,
         g_tp_decode != nullptr ? 1 : 0);
    return true;
}

bool ensure_capacity(int32_t needed) {
    if (needed <= g_n_ctx) return true;
    int32_t next = ((needed + kCtxStep - 1) / kCtxStep) * kCtxStep;
    if (next < kCtxInitial) next = kCtxInitial;
    if (next > kCtxMax) {
        LOGE("prompt needs n_ctx=%d, above the %d ceiling", next, kCtxMax);
        return false;
    }
    LOGI("growing context %d -> %d", g_n_ctx, next);
    destroy_context();
    if (create_context(next)) return true;
    LOGE("context growth to %d failed; restoring baseline %d", next, kCtxInitial);
    create_context(kCtxInitial);
    return false;
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

/** Feeds tokens [begin, end) into the context; requests logits on the last token if asked. */
bool process_range(
        const std::vector<llama_token> & tokens,
        int32_t begin,
        int32_t end,
        bool request_logits = true) {
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
        const bool last_chunk = request_logits && (chunk_end == end);
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
    } else if (n < 0) {
        const int32_t need = -n;
        std::string tmp(static_cast<size_t>(need), '\0');
        const int32_t m = llama_token_to_piece(vocab, id, tmp.data(), need, 0, false);
        if (m > 0) out.append(tmp.data(), static_cast<size_t>(m));
    }
}

jbyteArray to_byte_array(JNIEnv * env, const std::string & text) {
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

std::string get_bytes(JNIEnv * env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize n = env->GetArrayLength(value);
    if (n <= 0) return {};
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

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

void evict_oldest_prefix_if_needed() {
    while (g_prefix_cache.size() >= kMaxPrefixEntries) {
        auto oldest = g_prefix_cache.begin();
        for (auto it = g_prefix_cache.begin(); it != g_prefix_cache.end(); ++it) {
            if (it->second.last_used < oldest->second.last_used) {
                oldest = it;
            }
        }
        g_prefix_cache.erase(oldest);
    }
}

llama_sampler * get_or_create_sampler(const llama_vocab * vocab, const std::string & grammar) {
    auto it = g_sampler_cache.find(grammar);
    if (it != g_sampler_cache.end()) {
        llama_sampler_reset(it->second);
        return it->second;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (smpl == nullptr) return nullptr;

    if (!grammar.empty()) {
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grammar_sampler != nullptr) {
            llama_sampler_chain_add(smpl, grammar_sampler);
        } else {
            LOGE("grammar rejected; sampling unconstrained");
        }
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    g_sampler_cache.emplace(grammar, smpl);
    return smpl;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeInitBackends(JNIEnv * env, jobject, jstring jlibdir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string dir = get_string(env, jlibdir);
    if (!dir.empty()) {
        g_native_lib_dir = dir;
    }
    return ensure_backends_loaded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeLastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

static jboolean load_locked(JNIEnv * env, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_mutex);

    const std::string path = get_string(env, jpath);
    if (path.empty()) {
        set_error("model path empty");
        return JNI_FALSE;
    }

    if (g_model != nullptr && g_model_path != path) {
        destroy_context();
        clear_samplers();
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
    }
    if (g_model != nullptr) {
        if (g_ctx != nullptr) {
            g_last_error.clear();
            return JNI_TRUE;
        }
        LOGI("model present but context missing; rebuilding");
        return create_context(kCtxInitial) ? JNI_TRUE : JNI_FALSE;
    }

    if (!ensure_backends_loaded()) {
        return JNI_FALSE;
    }
    llama_backend_init();

    auto started = std::chrono::steady_clock::now();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), params);
    if (g_model == nullptr) {
        set_error("model load failed: " + path);
        return JNI_FALSE;
    }
    g_model_path = path;
    const double load_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    LOGI("model loaded in %.0f ms", load_ms);

    if (!create_context(kCtxInitial)) {
        clear_samplers();
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
        return JNI_FALSE;
    }
    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring jpath) {
    try {
        return load_locked(env, jpath);
    } catch (const std::exception & e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error(std::string("load exception: ") + e.what());
        return JNI_FALSE;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error("load failed: unknown exception");
        return JNI_FALSE;
    }
}

static jbyteArray complete_locked(
        JNIEnv * env, jbyteArray jprompt, jint maxTokens, jint nThreads,
        jint nThreadsBatch, jbyteArray jgrammar, jobject jprogress) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr) {
        set_error("complete called without a loaded model");
        return empty_bytes(env);
    }
    if (g_ctx == nullptr && !create_context(kCtxInitial)) {
        return empty_bytes(env);
    }
    if (maxTokens <= 0) return empty_bytes(env);
    g_last_error.clear();

    const std::string prompt = get_bytes(env, jprompt);
    const std::string grammar = get_bytes(env, jgrammar);
    if (prompt.empty()) return empty_bytes(env);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens = tokenize(vocab, prompt);
    if (tokens.empty()) {
        set_error("prompt tokenized to nothing");
        return empty_bytes(env);
    }
    const int32_t n_prompt = static_cast<int32_t>(tokens.size());

    if (n_prompt > kBatch) {
        LOGI("prompt %d tokens exceeds batch %d; prefill will be chunked", n_prompt, kBatch);
    }
    if (!ensure_capacity(n_prompt + maxTokens + 8)) {
        set_error("prompt exceeded context ceiling (" + std::to_string(n_prompt) + " tok)");
        return empty_bytes(env);
    }

    const int32_t clamped_decode = std::clamp<int32_t>(nThreads, 1, kMaxDecodeThreads);
    const int32_t clamped_batch = std::clamp<int32_t>(nThreadsBatch, 1, kMaxBatchThreads);
    llama_set_n_threads(g_ctx, clamped_decode, clamped_batch);

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_perf_context_reset(g_ctx);

    // ---- prefix state caching across ChatML system turns -----------------------------------
    int32_t n_keep = 0;
    const std::string user_marker = "<|im_start|>user\n";
    const size_t marker_pos = prompt.find(user_marker);
    bool handled_by_prefix_cache = false;

    if (marker_pos != std::string::npos) {
        const std::string sys_prompt = prompt.substr(0, marker_pos + user_marker.size());
        auto cached_it = g_prefix_cache.find(sys_prompt);

        if (cached_it != g_prefix_cache.end()) {
            const auto & sys_tokens = cached_it->second.tokens;
            const int32_t n_sys = static_cast<int32_t>(sys_tokens.size());
            if (n_sys > 0 && n_sys < n_prompt &&
                std::equal(sys_tokens.begin(), sys_tokens.end(), tokens.begin())) {
                llama_memory_clear(mem, false);
                const size_t restored = llama_state_seq_set_data(
                        g_ctx, cached_it->second.state.data(), cached_it->second.state.size(), 0);
                if (restored > 0) {
                    n_keep = n_sys;
                    cached_it->second.last_used = ++g_prefix_tick;
                    g_cache_tokens = sys_tokens;
                    handled_by_prefix_cache = true;
                } else {
                    LOGE("state_seq_set_data failed; dropping cached prefix");
                    g_prefix_cache.erase(cached_it);
                }
            }
        }

        if (!handled_by_prefix_cache) {
            std::vector<llama_token> sys_tokens = tokenize(vocab, sys_prompt);
            const int32_t n_sys = static_cast<int32_t>(sys_tokens.size());
            if (n_sys > 0 && n_sys < n_prompt &&
                std::equal(sys_tokens.begin(), sys_tokens.end(), tokens.begin())) {
                llama_memory_clear(mem, false);
                g_cache_tokens.clear();
                if (!process_range(tokens, 0, n_sys, false)) {
                    set_error("system prefix prefill failed (0.." + std::to_string(n_sys) + ")");
                    llama_memory_clear(mem, false);
                    return empty_bytes(env);
                }
                const size_t state_size = llama_state_seq_get_size(g_ctx, 0);
                if (state_size > 0) {
                    std::vector<uint8_t> state_buf(state_size);
                    const size_t copied = llama_state_seq_get_data(g_ctx, state_buf.data(), state_size, 0);
                    if (copied > 0) {
                        state_buf.resize(copied);
                        evict_oldest_prefix_if_needed();
                        PrefixEntry entry;
                        entry.tokens = sys_tokens;
                        entry.state = std::move(state_buf);
                        entry.last_used = ++g_prefix_tick;
                        g_prefix_cache[sys_prompt] = std::move(entry);
                        LOGI("cached system prefix: %d tok (%zu KB)", n_sys, copied / 1024);
                    }
                }
                n_keep = n_sys;
                g_cache_tokens = std::move(sys_tokens);
                handled_by_prefix_cache = true;
            }
        }
    }

    if (!handled_by_prefix_cache) {
        int32_t lcp = 0;
        const int32_t cached = static_cast<int32_t>(g_cache_tokens.size());
        const int32_t limit = n_prompt < cached ? n_prompt : cached;
        while (lcp < limit && g_cache_tokens[static_cast<size_t>(lcp)] == tokens[static_cast<size_t>(lcp)]) {
            lcp++;
        }
        n_keep = lcp;
        if (n_keep > n_prompt - 1) n_keep = n_prompt - 1;
        if (n_keep < 0) n_keep = 0;

        if (n_keep > 0) {
            if (!llama_memory_seq_rm(mem, 0, n_keep, -1)) {
                llama_memory_clear(mem, false);
                n_keep = 0;
            }
        } else {
            llama_memory_clear(mem, false);
        }
        g_cache_tokens.resize(static_cast<size_t>(n_keep));
    }

    // ---- prefill the tail -------------------------------------------------------------------
    if (!process_range(tokens, n_keep, n_prompt, true)) {
        set_error("prefill failed on [" + std::to_string(n_keep) + "," + std::to_string(n_prompt) + ")");
        llama_memory_clear(mem, false);
        g_cache_tokens.clear();
        return empty_bytes(env);
    }
    for (int32_t i = n_keep; i < n_prompt; i++) {
        g_cache_tokens.push_back(tokens[static_cast<size_t>(i)]);
    }

    // ---- sample -----------------------------------------------------------------------------
    llama_sampler * smpl = get_or_create_sampler(vocab, grammar);
    if (smpl == nullptr) {
        set_error("sampler init failed");
        return empty_bytes(env);
    }
    const bool stop_on_json_object = !grammar.empty();

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
        if (stop_on_json_object) {
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
                    if (depth > 0) depth--;
                }
            }
        }
        n_generated++;

        if (progress.method != nullptr) {
            if (progress.last < 2 && out.find("\"title\"") != std::string::npos) progress.emit(2);
            if (progress.last < 3 && (out.find("\"one_line\"") != std::string::npos ||
                                      out.find("\"items\"") != std::string::npos)) progress.emit(3);
            if (progress.last < 4 && out.find("\"why_it_matters\"") != std::string::npos) progress.emit(4);
            if (progress.last < 5 && out.find("\"tags\"") != std::string::npos) progress.emit(5);
        }

        if (stop_on_json_object && opened && depth <= 0) break;

        if (!process_one(id, static_cast<int32_t>(g_cache_tokens.size()))) {
            llama_memory_clear(mem, false);
            g_cache_tokens.clear();
            break;
        }
        g_cache_tokens.push_back(id);
    }

    log_perf(n_prompt, n_keep, n_generated);
    llama_sampler_reset(smpl);
    return to_byte_array(env, out);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeComplete(
        JNIEnv * env, jobject, jbyteArray jprompt, jint maxTokens, jint nThreads,
        jint nThreadsBatch, jbyteArray jgrammar, jobject jprogress) {
    try {
        return complete_locked(env, jprompt, maxTokens, nThreads, nThreadsBatch, jgrammar, jprogress);
    } catch (const std::exception & e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error(std::string("complete exception: ") + e.what());
        return empty_bytes(env);
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error("complete failed: unknown exception");
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
    g_prefix_cache.clear();
    LOGI("cache reset");
}

extern "C" JNIEXPORT void JNICALL
Java_com_sievex_app_llm_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    destroy_context();
    clear_samplers();
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGI("model unloaded");
    }
    g_model_path.clear();
}
