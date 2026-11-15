# Sievex — ASR & LLM inference performance audit

**Date:** 2026-11-14  
**Scope:** everything between "burst ends" and "note filed", plus first-burst cold start.  
**Method:** static audit of source + disassembly of the shipped `arm64-v8a` libraries, run as five
parallel specialist lanes with an adversarial skeptic pass on every load-bearing claim.  
**Constraint:** no Android device was attached (`adb devices` empty) and no model or llama.cpp source
is present on this machine. **Every timing number below is an estimate**, labelled as such, derived
from code, token counts and instruction-level evidence — not from a device profile. Section 7 is the
plan to replace those estimates with measurements.

---

## 1. TL;DR

The app is not slow because of ASR. It is slow because of **how much text the LLM is asked to
generate, and how that text is generated**.

Three facts do most of the damage:

1. **The JSON schema asks the 1.7B model to echo the transcript back in `verbatim`** (and the token
   cap, 360, is roughly the natural length of the whole object for a 60-second burst). Long bursts
   truncate → `JSONObject` fails → `Transform` immediately pays for a **second full generation**.
   Generating less text is a bigger lever than any kernel change.
2. **The LLM runtime is compiled for baseline `armv8-a`.** The shipped `libggml-cpu.so` contains
   **zero** `udot`/`sdot`/`smmla` instructions and `ggml_cpu_has_dotprod()` is a compile-time
   `return 0`. Every Q4_K matmul runs the slow widening-NEON kernel. The ASR runtime in the same APK
   *is* armv8.2-optimised (4,218 `sdot`, 480 `smmla`), so only the LLM half is leaving performance on
   the table.
3. **A fresh `llama_context` (and KV cache) is built and destroyed on every call**, then the same
   ~200–300-token system prompt is re-prefilled for every burst, even though `LlmGate` already
   guarantees one caller at a time.

Ranked by expected wall-clock saved per note (details in §3):

| # | Change | Est. saving | Rebuild? |
|---|--------|-------------|----------|
| 1 | Stop regenerating `verbatim`; size `maxTokens` to the schema; stop at the closing brace | **2–3× of the decode phase**; removes the retry | Kotlin only |
| 2 | Build ggml for `armv8.2-a+dotprod+i8mm` (+KleidiAI) | prefill 2–3×, decode 1.2–1.6× | llama.cpp rebuild |
| 3 | Grammar-constrained JSON + structural early stop | removes ~1 wasted generation on truncating bursts | shim rebuild |
| 4 | Cache context + sampler, reuse the system-prompt KV prefix | 1 KV alloc + ~200–300 prefill tokens per call | shim rebuild |
| 5 | ASR `numThreads` 2 → 4 (sweep 2/3/4/6) | ASR −30–45% | Kotlin one-liner |
| 6 | Warm both models when arming; unload when the queue empties | −3–10 s on the first note of each process | Kotlin only |
| 7 | VAD trim + burst duration cap + don't retry blank audio | removes 5–10% of ASR and the pathological 60–120 s stalls | Kotlin + 1 model file |

**Expected end state per 60 s burst: ~30–70 s today → ~12–20 s (estimate), before any model swap.**
That is roughly a 3× improvement with no quality loss, and a further 1.5–2× available by dropping to
a smaller quantisation or model for structured extraction.

---

## 2. Where the time actually goes (per 60 s burst, warm process)

| Phase | Estimate | Why it costs that |
|---|---|---|
| ASR decode | 6–12 s | Parakeet int8 encoder on **2** threads, whole burst including edge silence |
| LLM prefill | 4–9 s | ~300–500 prompt tokens on **generic NEON** kernels, 4 threads |
| LLM decode | **20–45 s** | 250–360 tokens at ~8–12 tok/s; `verbatim` echo dominates the token count |
| Per-call context/KV setup | 0.05–0.25 s | `llama_init_from_model` + KV alloc + scheduler reserve, every call |
| Cold start (first burst only) | +3–10 s | 652 MB encoder + 1.1 GB GGUF loaded inside the first job |

The decode phase is the dominant term, and two thirds of the decoded tokens are a field the app
already has for free (the ASR transcript).

---

## 3. Findings

Each finding carries the evidence the skeptics could confirm, the mechanism, the expected gain, the
risk, and how to prove it on hardware. Where the skeptic corrected the first pass, the correction is
stated.

### Tier 1 — Kotlin only, no native rebuild

#### T1.1 — `verbatim` costs most of the LLM budget and is already known

- **Evidence:** `app/src/main/java/com/sievex/app/llm/Transform.kt:27` asks for "the speaker's words";
  `:52` caps generation at 360 tokens; `:49-58` retries once on parse failure; `:107-114` cannot
  repair a truncated object (`lastIndexOf('}')` on a string with no `}`).
- **Mechanism:** the model re-emits the entire transcript it was just given. For a 60 s burst
  (~150–200 words ≈ 200–260 tokens), `verbatim` alone is ~60–70% of the output. Total object length
  ≈ 330–420 tokens against a 360 cap, so the longer half of bursts truncates, `JSONObject` throws,
  and the retry re-runs prefill + up to 360 more decode tokens with an even *longer* prompt
  (`:62-63`). `CaptureService.kt:401-418` then files a raw transcript anyway.
- **Fix:** take `verbatim` from the ASR text (Parakeet already emits punctuation and capitalisation
  per SPEC §5) and ask the model only for `mode`, `mode_confidence`, `title`, `one_line`,
  `why_it_matters`, `open_question`, `tags`. Drop the unused `mode_confidence` from the parse or keep
  it — it is 5 tokens. Expected output: ~90–130 tokens.
- **Saving:** decode drops to ~1/3; the truncation-retry path disappears. Estimate **2–3× on the
  transform**.
- **Risk:** `verbatim` is user-visible and editable (`ui/AppUi.kt:2441`, `:2533-2616`). Raw ASR text
  keeps disfluencies the model was cleaning up. Mitigate by keeping the *cleaned* variant only if a
  short post-pass is cheap (e.g. only strip fillers), or by making this a setting.
- **Effort:** S. **Verify:** log output token count (`n_eval`), then compare 20 bursts on
  `note-store` output and by eye.

#### T1.2 — ASR runs the 652 MB encoder on 2 threads

- **Evidence:** `app/src/main/java/com/sievex/app/asr/AsrEngine.kt:68` (`numThreads = 2`), `:69`
  (`provider = "cpu"`). No thread priority or affinity is set anywhere in `app/src/main/java`.
  The decode runs on the single daemon worker (`CaptureService.kt:557-559`) while the podcast is
  paused, so there is no competing UI work.
- **Fix:** `numThreads = 4` (sweep 2/3/4/6 — the optimum is SoC-specific; 6 can regress when threads
  land on little cores). Optionally raise the worker thread priority for the decode window.
- **Saving:** estimate **ASR −30–45%** (2–5 s per burst).
- **Risk:** thermal/battery on long bursts; playback resuming mid-decode. A/B is required.
- **Verify:** the existing `CaptureLog` line `"asr: %.1fs audio -> %d ms (rtf %.2f)"`
  (`AsrEngine.kt:104-106`) is already enough — no code change needed to measure this.

#### T1.3 — Nothing warms the models, and nothing ever unloads them

- **Evidence:** `AsrEngine.ensureLoaded` is first called inside `runJob` (`CaptureService.kt:361`),
  i.e. after the burst; `arm()` (`:114-120`) only raises the notification. `AsrEngine.unload()` has
  one caller — the post-download hook (`asr/ModelInstaller.kt:128`). `LlamaBridge.nativeUnload` has
  **no caller at all** (`llm/LlamaBridge.kt:14`; implementation `cpp/llama_jni.cpp:144-151`).
- **Mechanism:** the first note after every process start pays both loads inside the user's wait
  (~652 MB encoder + 1.1 GB GGUF, estimate 3–10 s). Both models then stay resident for the process
  lifetime, contradicting SPEC §5 ("never both loaded", R8). LMK kills between bursts are likely on
  smaller devices, and every kill re-pays the cold start plus the orphan scan
  (`CaptureService.kt:63-81`, `:497-525`).
- **Fix:** warm both on the worker from `arm()` (swallow failures — a failed preload must degrade to
  lazy load, not block capture); call `AsrEngine.unload()` when the queue empties
  (`onQueueEmpty`, `:306-314`) and `nativeUnload()` when the service stops; reload on next kick.
- **Saving:** removes 3–10 s from the first note of each process; removes second-model memory
  pressure.
- **Risk:** preloading pins ~650 MB for an armed-but-idle session; unloading after every burst would
  re-pay the load — only unload when the queue is empty and the service is going away.
- **Note:** this is the SPEC's own requirement, not a new idea — the code just never implemented it.

#### T1.4 — A blank transcript re-decodes the same audio up to three times and blocks the queue

- **Evidence:** `CaptureService.kt:371-376` → `retryOrDrop` (`:429-436`) keeps the job;
  `JobQueue.peek()` is `firstOrNull()` (`JobQueue.kt:42`); `MAX_ATTEMPTS = 3` (`JobQueue.kt:22`);
  retry timer 60 s (`CaptureService.kt:548`, `:299-303`). Nothing about the input changes between
  attempts, so the same silence is decoded identically three times.
- **Saving:** removes ~2 full decodes plus ~120 s of head-of-line blocking per silent burst — a
  pathological stall, not a constant cost, but it is the kind of thing that makes the app "feel
  broken" rather than "feel slow".
- **Fix:** treat a blank result as a VAD/quality outcome, not a failure: quarantine after the first
  blank (or after one retry), and add the VAD gate in T1.5 so silence never reaches the encoder.
- **Risk:** dropping genuine very-quiet speech. The VAD gate makes this safe.

#### T1.5 — No VAD trim, and no cap on burst length

- **Evidence:** `AsrEngine.kt:87-112` hands the whole WAV to `acceptWaveform`. `BurstRecorder.kt:70`
  loops `while (running)` with no duration cap and no silence timeout; a burst stops only on media
  resume / tap / disarm / service destroy (`CaptureService.kt:93`, `:103`, `:124`, `:190-199`,
  `:610`). No `silero_vad.onnx` is shipped (`asr/ModelInstaller.kt:30-47` — four files only), but the
  VAD binding is already present and unused (`app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt`), and
  the shipped native lib contains the VAD code ("Errors in config.vad is nullptr.").
- **Mechanism:** encoder cost scales with frames, so 1–3 s of edge silence is 5–10% of the decode;
  a forgotten pause is unbounded (a 5-minute WAV is a 5–10× larger decode that also blocks the
  queue).
- **Fix:** add Silero VAD (2 MB, one more pinned download) for `start`/`end` trimming with ~200 ms
  padding, plus a hard 90 s cap and a 10 s silence stop (both already in SPEC §3/§4).
- **Saving:** ~5–10% of ASR, plus the pathological case.
- **Risk:** VAD false-negative truncates real words; keep padding and require min-speech duration.

#### T1.6 — JSON repair/validation is brittle in small ways that each cost a full retry

- **Evidence:** `Transform.kt:83` returns `null` when `title` is blank, discarding an otherwise valid
  object (then a retry costs a full generation). `:109-110` `removePrefix("```json")` never re-trims,
  so a fenced reply would become `"{\n{..."`. The prefill makes fencing unlikely; the blank-title
  path is the real one.
- **Fix:** parse leniently (accept blank title, derive from `one_line`), and only retry when the
  object is genuinely unparseable.

#### T1.7 — The 60 s retry ladder leaves a stale stage on screen

- **Evidence:** `CaptureService.kt:299-303` posts a main-handler retry; `retryOrDrop` (`:429`) never
  touches `Pipeline`, so the UI stays on `TRANSCRIBING`/`TRANSFORMING` for the whole wait, and the
  timer dies with the process.
- **Saving:** none in CPU, but it is the difference between "slow" and "hung". Add a `RETRYING` stage
  and make the timer durable (or drain on the next kick).

### Tier 2 — native shim rebuild (`libsievex_llm.so` only)

Everything in this tier is reachable with the headers already in `spike/llama-headers/` and the
`.so` files in `jniLibs`; the NDK (28.2.13676358) and `spike/build-llm-jni.sh` are present. A symbol
scan confirms `libllama.so` exports every API needed: `llama_sampler_init_grammar`,
`llama_memory_seq_rm`, `llama_set_n_threads`, `llama_get_memory`, `llama_perf_context`.

#### T2.1 — Grammar-constrained JSON + structural early stop

- **Evidence:** `cpp/llama_jni.cpp:82-84` builds a sampler chain with greedy only; the loop
  (`:109-133`) exits only on EOG or `maxTokens`. No stop strings, no brace tracking. `llama.h:1528`
  has `llama_sampler_init_grammar`.
- **Fix:** add a GBNF grammar for the note object, and stop as soon as brace depth returns to 0.
  Prime the grammar with the already-prefilled `{` via `llama_sampler_accept` (`llama.h:1440`), or
  drop the prefill.
- **Saving:** removes the truncation-retry path entirely and trims trailing tokens; estimate
  **one whole attempt avoided** on bursts that currently truncate (20–45 s each).
- **Risk:** grammar prunes the distribution — optional fields can come out empty where free-form
  output filled them. A grammar only constrains shape, not quality.

#### T2.2 — Cache the context, sampler and KV; reuse the system prefix

- **Evidence:** `llama_jni.cpp:70-80` creates the context per call (`n_ctx = n_prompt + maxTokens + 8`,
  `n_batch = n_prompt`) and frees it at `:138`. The system prompt is a compile-time constant emitted
  first (`Transform.kt:20-32`, `:65-68`), so ~200–300 tokens are re-prefilled on every burst.
  `LlmGate` (`llm/LlmGate.kt:15-24`) plus `@Synchronized` on `Lens.generate` already guarantees one
  caller at a time, so reuse is safe. Note: `LlmGate.kt:8-9`'s comment claims "one KV cache" — that
  comment is false today; fix it when implementing.
- **Fix:** hold one context sized once (2560 tokens covers both call sites), size `n_batch` sanely
  (2048 with an explicit `n_ubatch`), clear instead of free (`llama_memory_clear`), and keep the
  system prefix with `llama_memory_seq_rm(mem, 0, n_prefix, -1)`. Verify the tokenised prefix is
  byte-stable against the merged prompt and back off at the first mismatch.
- **Saving:** removes per-call KV alloc/reserve (0.05–0.25 s) **and** ~200–300 prefill tokens
  (~1–4 s) per burst; also speeds the retry path.
- **Risk:** a fixed `n_ctx` reserves KV memory permanently (~30–90 MB, architecture-dependent);
  guard prompts longer than the window and reset to empty.
- **Dependency:** T2.1's grammar state must be reset with the context.

#### T2.3 — Split decode and prefill thread counts

- **Evidence:** `llama_jni.cpp:73-74` sets `n_threads = n_threads_batch = 4` (from `Transform.kt:52`
  / `Lens.kt:27`), frozen at init. `llama.h:1077-1080` documents the split intent and provides
  `llama_set_n_threads(ctx, n, n_batch)` for post-init retuning.
- **Fix:** decode threads ≈ number of big cores (2–4), batch threads higher (4–6);
  `llama_set_n_threads` after the prefill.
- **Saving:** estimate decode +10–20%, prefill +5–15%. **Low confidence** — this is a sweep, not a
  constant; the right values are SoC-specific and there is no device here.

#### T2.4 — Flash Attention

- **Evidence:** `flash_attn_type` exists (`llama.h:193-197`, `:382`) and is left at default.
- **Reality check:** the header defines `LLAMA_FLASH_ATTN_TYPE_AUTO = -1` and upstream defaults to
  AUTO, so FA may already be active; the first pass's "disabled" claim is **not** established. Treat
  this as an A/B: set `LLAMA_FLASH_ATTN_TYPE_ENABLED` explicitly and compare `t_p_eval_ms` at
  800–2000 context. Expect prefill +5–15%, decode ~0, and a numerical path change (output tokens can
  flip). Low priority.

#### T2.5 — Make the result UTF-8-safe and expose perf counters

- **Evidence:** `llama_jni.cpp:116-118` appends raw `llama_token_to_piece` bytes and `:141` hands
  them to `NewStringUTF`, which requires Modified UTF-8. A token that splits a multi-byte sequence
  (byte-fallback vocabularies) or a 4-byte emoji is not valid; a `maxTokens` cut can also end
  mid-sequence. In a debuggable build this can abort under CheckJNI.
- **Fix:** return a `jbyteArray` and decode with `CharsetDecoder` REPLACE, or repair at the boundary;
  also return `n_prompt`/`n_generated` and `llama_perf_context` timings (see §7).

### Tier 3 — llama.cpp rebuild (the big kernel lever)

#### T3.1 — The LLM stack is compiled for baseline `armv8-a`

- **Evidence (measured on this machine):** full `llvm-objdump -d` of
  `app/src/main/jniLibs/arm64-v8a/libggml-cpu.so` contains **0** `udot`, **0** `sdot`, **0** `smmla`,
  **0** `bfmmla` (178,125 disassembled instructions; no `.word` holes). Same zeros for
  `libggml-base.so`, `libggml.so`, `libllama.so`. `ggml_cpu_has_dotprod` at `0x3e858` is literally
  `mov w0, wzr; ret`, and `ggml_cpu_has_matmul_int8` likewise. The only Q4_K kernels are
  `ggml_vec_dot_q4_K_q8_K` / `..._generic` and `ggml_gemm_q4_K_{8x4,8x8}_q8_K_generic` (widening
  `smull/smlal`, the slow path). The i8mm repack table exists but every entry is dead.
  Positive control, same method, same folder: `libonnxruntime.so` has **4,218** `sdot`, **480**
  `smmla`, **320** `bfmmla`, **149** `udot`.
- **Cause:** `spike/build-llama-android.sh:19` sets `-DGGML_NATIVE=OFF` and never sets
  `GGML_CPU_ARM_ARCH`.
- **Fix:** rebuild with `-DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm"` (and optionally
  `-DGGML_CPU_KLEIDIAI=ON` for the microkernels). Expected: **prefill 2–3×, decode 1.2–1.6×**
  (decode is weight-bandwidth-bound at 1.1 GB/token, so it gains less than prefill).
- **Corrected risk (skeptic):** the first pass warned that such a build would SIGILL on pre-armv8.2
  cores. That is true of the *future* build, not this artifact — this artifact contains no such
  instructions, so it is safe today. Because `minSdk = 30` still admits Cortex-A53-class devices,
  the safe shape is: build one baseline `ggml-cpu` plus one `+dotprod+i8mm` variant and pick at
  load time (`libggml.so` already contains the dynamic backend loader), or gate on
  `/proc/cpuinfo` features (`asimddp`, `i8mm`) before loading the fast variant. For the owner's
  single flagship, a single `+dotprod+i8mm` build is acceptable if the device is confirmed
  armv8.6-capable.
- **Blocker:** `spike/llama.cpp` is **not in the repo** (only headers remain) and the headers expose a
  non-upstream API (`llama_process`, `llama_batch_ext_*`), so this cannot be rebuilt from upstream
  master as-is. Either recover the fork, or re-port the ~150-line shim to upstream llama.cpp
  (`llama_decode` + `llama_batch`) and pin a commit — the shim is small enough that re-porting is
  probably cleaner than tracking a fork.

#### T3.2 — Which batch geometry

- `n_batch = n_prompt` with `n_ubatch` unset means the declared logical batch is the whole prompt
  while llama.cpp still tiles physical ubatches. Once the context is cached (T2.2), set `n_batch`
  (2048) and `n_ubatch` (512) explicitly. Expect no measurable win on its own; it prevents
  pathological reservations on long Lens prompts.

### Tier 4 — model and quantisation

- `LFM3-1.7B-Q4_K_M` is 1.1 GB and is used for both extraction and lens reports
  (`llm/TransformModel.kt:22-26`, pinned by size + SHA-256).
- **Options, in increasing risk:** (a) `Q4_0` or `Q4_K_S` of the same model — simpler dequant, often
  10–20% faster on CPU, small quality cost; (b) a smaller model for extraction only (0.6–1.2B class)
  while keeping the 1.7B for lens reports — roughly 1.5–2× on the dominant path; (c) speculative
  decoding with a tiny draft model — unnecessary once output length is fixed.
- **Not recommended:** swapping to a remote/cloud model (violates the product's whole premise).
- **Effort:** M — a model swap touches `TransformModel.kt` (URL, byte count, SHA-256), the readiness
  checks, and the `MODEL` string recorded in lens artifacts.

### Tier 5 — ruled out (do not spend time here)

| Idea | Why not |
|---|---|
| Stripping `libllama.so` | AGP already strips: the APK entry is 4,623,608 B vs 47,827,736 B on disk. Measured. |
| GPU (Vulkan/OpenCL) offload | No backend `.so` is shipped; adding one is a new build, new driver risk, and Q4_K matmul on mobile GPUs often loses to CPU at batch 1. Possible later, not now. |
| NNAPI for ASR | `provider = "nnapi"` is reachable (ORT exports `OrtSessionOptionsAppendExecutionProvider_Nnapi`; sherpa parses the string). But the encoder is dynamically-quantised int8, which NNAPI largely does not support — most nodes would fall back with partition/layout overhead. A/B it (one string, no rebuild) and expect 0 or worse. |
| XNNPACK for ASR | No XNNPACK EP wired into the shipped sherpa JNI; needs a C++ rebuild. |
| `modified_beam_search` | Encoding dominates offline transducer cost; beam only multiplies the 12 MB decoder + 6 MB joiner. Slower, never faster. Keep `greedy_search`. |
| WAV write → read-back | 1.92 MB through FUSE per 60 s burst: ~0.01–0.05 s. Real but negligible next to decode. |
| Minify/R8, dex compression | Real APK-size work (dex is 22.4 MB, ORT 22.2 MB, llama stack only 6.8 MB) but zero effect on inference. |

---

## 4. Other defects found while auditing (correctness-adjacent)

- `cpp/llama_jni.cpp:18-20` — `nativeLoad` returns `JNI_TRUE` whenever a model is already loaded,
  **ignoring the requested path**. Harmless today (one model), silently wrong the day a second
  model is added.
- `WavWriter.kt:23`/`:37` — the RIFF header is written with `dataSize = 0` and patched on close. A
  kill mid-burst leaves a 0-sample WAV that always decodes blank, which then enters the retry ladder
  (T1.4) and is re-decoded three times.
- `AsrEngine.kt:93` — `WaveReader.readWave` materialises the whole WAV as a `FloatArray`
  (7.7 MB for 60 s); with no duration cap (T1.5) the allocation is unbounded.
- `BurstRecorder.kt:74` — only `n > 0` and `n < 0` are handled; a persistent `n == 0` spins with no
  timeout or log.
- `Transform.kt:83` — a blank `title` nulls an otherwise valid reply (see T1.6).
- `LlmGate.kt:8-9` — the doc comment describes a KV cache that does not exist; it will mislead the
  next person who touches the native path.
- `Lens.kt:82` uses the same model as `Transform` but a different hard-coded cap (500 vs 360) and no
  retry, so a lens gets one truncated shot.

---

## 5. What is *not* different from the SPEC, and matters

- `greedy_search` is correct for speed. Do not change it.
- 16 kHz end-to-end is correct; there is no resampling to remove. But the WAV header rate is a
  hard-coded constant that is never compared with `AudioRecord.getSampleRate()` — a route that
  returns another rate is mislabelled, not resampled.
- Store scans during a job (`NoteStore.all()`, `SpaceStore.all()`) are in-memory copies and
  millisecond-scale at this data size; `jobs.json` rewrites are similarly cheap. They are not the
  bottleneck (skeptic-confirmed).
- The UI polls stores off the main thread; the Compose path is not blocking inference.

---

## 6. Sequencing (what to do in which order)

1. **Instrument first (½ day).** Add prefill/decode split, token counts, model-load ms and `VmHWM`
   to `CaptureLog`; wire `llama_perf_context` into the shim log line (§7). Without the
   prefill-vs-decode split you cannot tell whether T3.1 or T1.1 moved the needle, and both are
   worth days of work.
2. **Tier 1 in one pass** (Kotlin only): T1.1 token budget, T1.2 threads, T1.3 warm/unload, T1.4
   blank handling, T1.6 parse leniency. Build and run the A/B. This is where most of the
   user-visible win lands and it needs no native toolchain.
3. **Tier 2 shim rebuild**: T2.1 grammar + early stop, T2.2 context/prefix reuse, T2.5 perf counters
   and UTF-8 safety. Rebuild `libsievex_llm.so` with `spike/build-llm-jni.sh` (verify the script is
   invoked before packaging — neither `build-app.sh` nor Gradle calls it today).
4. **Tier 3**: recover or re-port llama.cpp, then rebuild ggml with `+dotprod+i8mm` (+KleidiAI) and
   measure. Biggest single kernel win, biggest setup cost.
5. **Tier 4** only if 1–4 leave the note above ~15 s.

---

## 7. Measurement plan (the missing half of this audit)

Today's telemetry is two coarse lines:

```
asr: 58.4s audio -> 9123 ms (rtf 0.16)     # AsrEngine.kt:102-106
llm: 1841 chars in 34120 ms                # Transform.kt:51-54
```

They conflate WAV parsing with decode, and tokenisation with context init, prefill and decode. Add,
behind a debug flag:

- `AsrEngine`: model-load ms, trimmed-sample count, decode ms separate from `readWave`.
- `llama_jni.cpp`: `n_prompt`, `n_generated`, `t_p_eval_ms`/`n_p_eval`, `t_eval_ms`/`n_eval`,
  `n_reused`, context init ms (`llama.h:1671-1693`), returned as a small struct or a second log line.
- `CaptureService`: burst→note wall clock (already partially inferable from the log timestamps).
- `VmHWM` from `/proc/self/status` after each job.

**Harness without app changes** (works with the already-built `dist/sievex-0.16.apk`):
push prepared 16 kHz mono PCM16 WAVs into `files/bursts/`, `am force-stop`, then start
`MainActivity` — its kick (`MainActivity.kt:40`) lets `CaptureService.onCreate` fold the files in via
orphan recovery (`CaptureService.kt:497-525`) and drain. Parse `adb logcat -v time -s Sievex`. Ensure
the process is force-stopped first: `kick()` early-returns when the queue is known-empty
(`CaptureService.kt:584`), so a stale process silently benchmarks nothing. Models must be placed via
`run-as` on a debuggable build and are ~1.8 GB.

**A/B protocol:** 10 real walking bursts + 4 synthetic (clean / long / 5 dB / 10 dB), 3 reps per arm,
alternating arms, median and spread, fixed burst size per pass, battery optimisation off, device
cool between arms, first run discarded as cold. Acceptance: SPEC R4 (≥ 2× realtime ASR) and the
5-minute file target; report prefill and decode separately.

**Single most valuable measurement:** the prefill/decode split of one 60 s transform. If prefill
dominates, the answer is context reuse and kernels (T2.2, T3.1). If decode dominates — the estimate
here — the answer is generating fewer tokens (T1.1) and the kernel rebuild, in that order.

---

## 8. Confidence and what would change these conclusions

| Claim | Confidence | Basis |
|---|---|---|
| ggml lacks dotprod/i8mm; ONNX has them | **High** | Exact instruction counts from the shipped `.so`s |
| `verbatim` dominates output tokens; 360 truncates long bursts | **High** (mechanism) / medium (rate) | Code + token arithmetic; needs `n_eval` to confirm how often |
| Context/KV rebuilt per call; system prefix re-prefilled | **High** | Code, confirmed by skeptic |
| ASR on 2 threads is materially slower than 4 | High (that a gap exists) / medium (size) | No device |
| No VAD/denoise in app; no burst cap | **High** | Code + shipped file list |
| Nothing unloads; both models resident | **High** | `unload`/`nativeUnload` call-site greps |
| No runtime ISA dispatch in this build | **High** | Skeptic: no `ggml_backend_cpu_get_features`, dead `ggml_arm_arch_features`, repack branches on `__cxa_guard` only |
| Flash Attention is off | **Refuted as stated** | Header defaults to AUTO; treat as an A/B |
| SIGILL risk from a future +dotprod build | **Confirmed as a real future risk**, not present today | `minSdk 30` admits A53-class cores |

Open questions that need hardware or files not on this machine: actual ASR RTF per thread count;
whether `<|im_end|>` is in the GGUF's EOG set (if not, every transform runs to the cap); the LFM3
architecture (how much KV the attention layers actually own); the exact default `n_ubatch` in this
fork; and whether NNAPI claims any encoder nodes at all.

---

## 9. Commands used to derive the binary claims

```bash
NDK="$HOME/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin"
$NDK/llvm-objdump.exe -d app/src/main/jniLibs/arm64-v8a/libggml-cpu.so \
  | grep -c -wE 'udot|sdot|smmla|bfmmla'      # 0
$NDK/llvm-objdump.exe -d app/src/main/jniLibs/arm64-v8a/libonnxruntime.so \
  | grep -c -wE 'udot|sdot|smmla|bfmmla'      # >0 (5,167 total: 4218 sdot, 480 smmla, 320 bfmmla, 149 udot)
$NDK/llvm-objdump.exe -d --start-address=0x3e858 --stop-address=0x3e860 \
  app/src/main/jniLibs/arm64-v8a/libggml-cpu.so   # mov w0, wzr ; ret
$NDK/llvm-nm.exe -D app/src/main/jniLibs/arm64-v8a/libllama.so | grep -E \
  'llama_sampler_init_grammar|llama_memory_seq_rm|llama_set_n_threads|llama_perf_context'
python - <<'PY'   # APK entries are already stripped
import zipfile; z=zipfile.ZipFile('dist/sievex-0.16.apk')
print([(i.filename,i.file_size) for i in z.infolist() if 'libllama' in i.filename])
PY
```

---

## 10. Implementation status (added after the audit)

Everything below was implemented and compiles (`./gradlew --offline assembleDebug` is green, the
JNI shim was rebuilt against the shipped llama.cpp libs). **Nothing has been run on a device**, so
these are code-verified changes, not measured improvements. The build and the APK were checked:
`libsievex_llm.so` in `app-debug.apk` exports the new JNI surface and carries the new log lines.

Two adversarial review passes ran over the implementation (and two over the audit itself). Their
findings — a context that could stay null while `nativeLoad` reported success, a `recording` flag
set after the capture thread started, a release that could unload the models just before the
stopped burst was queued, a thread pool asked for more threads than it owned, a logits buffer sized
by `n_batch` instead of one token, and a non-atomic in-flight counter — were fixed and re-reviewed
before the final build. The remaining notes are the risks in the list below.

### Changed

| Lever | Status | Where |
|---|---|---|
| Stop regenerating `verbatim`; size the cap to the schema | Done — the model now emits six fields; `verbatim` is the ASR text, whitespace-collapsed | `llm/Transform.kt` |
| Grammar-constrained JSON | Done — GBNF on both attempts, brace-depth stop that ignores braces inside strings | `llm/Transform.kt`, `cpp/llama_jni.cpp` |
| Context, sampler and KV cache reuse | Done — one context per process, longest-common-prefix trim via `llama_memory_seq_rm` | `cpp/llama_jni.cpp` |
| Split decode/batch pools | Done — pools 4/6, tuned per phase with `llama_set_n_threads` | `cpp/llama_jni.cpp`, `llm/LlamaBridge.kt` |
| Output-buffer bloat | Fixed — `n_outputs_max = 1` (was 0 = n_batch, a ~0.5-1 GB logits buffer) | `cpp/llama_jni.cpp` |
| Unbounded KV growth | Fixed — context ceiling 4096; an oversized prompt fails that one transform instead of the process | `cpp/llama_jni.cpp` |
| UTF-8 safety | Done both ways — prompt and grammar cross as `byte[]`, reply decoded with the platform decoder | `llm/LlamaBridge.kt`, `cpp/llama_jni.cpp` |
| C++ exceptions across JNI | Guarded at both entry points | `cpp/llama_jni.cpp` |
| ASR threads | Done — `availableProcessors().coerceIn(2, 4)` | `asr/AsrEngine.kt` |
| Warm models on arm, release when idle | Done — warm on the queue worker; release on disarm / queue-empty / destroy, on a daemon thread so a Lens cannot stall the drain | `CaptureService.kt` |
| Blank transcript | Done — dropped and quarantined after one decode, no 60 s retry ladder | `CaptureService.kt` |
| Burst duration | Done — 90 s cap, with the service state repaired when the recorder stops itself | `BurstRecorder.kt`, `CaptureService.kt` |
| Instrumentation | Done — model-load ms, `pp`/`tg` tokens, kept-prefix, tok/s, graph reuse in logcat | `cpp/llama_jni.cpp`, `asr/AsrEngine.kt` |
| Benchmark harness | Done — `spike/bench.sh` + `spike/bench.py` (adb-driven, no app edits) | `spike/` |
| Tuned ggml build | Scripted, **blocked** — `spike/build-llama-android.sh` now passes `GGML_CPU_ARM_ARCH` and optional KleidiAI, but the fork source (`spike/llama.cpp`) is not in the repo | `spike/` |
| VAD trim | Not done — needs `silero_vad.onnx` as a fifth pinned download; the 90 s cap covers the worst case | — |
| Smaller model / Q4_0 | Not done — a product decision, not a mechanical change | — |
| Retry stage in the UI | Not done | `CaptureService.kt`, `Pipeline.kt` |

### New risks introduced (verify on the first device run)

1. **KV reuse may be inert.** `LFM3` is a hybrid conv/attention model; if its memory refuses a
   partial suffix trim, the code logs `partial KV trim refused; clearing cache` on every call and
   falls back to a full prefill. Correct, but the prefix saving would be zero.
   Check: `adb logcat -s SievexLLM` — the `pp … (kept N/M)` field shows N on the second burst.
2. **Grammar vs. tokens.** If `llama_sampler_init_grammar` rejects the grammar (a `LOGE` says so),
   attempt 1 becomes unconstrained; the first/last-brace extraction still recovers a JSON object.
3. **`n_outputs_max = 1`** is correct only while no call site requests more than one output per
   batch. Both the prefill chunk and the decode step request exactly one.
4. **Two models still resident while armed** (~1.75 GB) — that is deliberate (warm bursts) and is
   released when the queue empties and the service stops. It is still above the SPEC's R8 advisory.
5. **`verbatim` is now raw ASR** — disfluencies are preserved, punctuation is whatever Parakeet
   produced. Lens prompts receive slightly noisier text.
6. **A derived title can be mid-clause** when the model omits `title`; it is editable.

### First-run checklist (15 minutes on the device)

1. Install the debug APK, arm the service, record one 30 s burst.
2. `adb logcat -s Sievex SievexLLM` and confirm, in order: `warm: N model(s)`, `asr: model loaded in
   …`, `asr: … rtf …`, `pp … tok (kept K/M) … | tg …`, `note filed`.
3. Record a second burst: `kept` should be well above 0 (system turn reused) and `pp` small.
4. Run `spike/bench.sh <wavs> 3` and compare against the pre-change build if available.
5. Disarm and watch for the retained models to drop: `adb shell dumpsys meminfo com.sievex.app`
   before/after, or simply confirm a `note filed` still happens when the models reload cold.

---

*Audit performed read-only. The audit itself modified no source; the implementation described in
§10 changed the files listed there and was verified by compilation and binary inspection only.*
