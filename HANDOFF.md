# Handoff — Sievex

Repo: https://github.com/NehuenD/sievex_app (public)
Branch: **`llm-rebase`** (cut from `main` @ `fe23a2c`) · head `a04becc`
App version: `versionCode 16` / `versionName "0.16"` (unchanged)
Written: 2026‑11‑20

> **This branch is not merged and not device-verified.** It contains a native-library
> re-base described below. `main` is untouched and still ships the old libs.

## What this is

Personal, fully offline Android app. It records 20–60 s voice bursts (triggered by the podcast's
pause through a notification listener, or the record button), transcribes them with sherpa-onnx
Parakeet TDT 0.6B v3 int8, shapes each into a note with a local LLM (LFM3‑1.7B Q4_K_M through a
hand-written llama.cpp JNI shim), and organises notes into spaces with generated "lens" reports.
UI and model output are English or Spanish, switched in Settings.

## Current state of the tree

| | |
|---|---|
| `main` | untouched at `fe23a2c`, clean |
| `llm-rebase` head | `a04becc` "Correct the re-base premise: headers are upstream, JNI needs no changes" |
| uncommitted | 5 binaries in `app/src/main/jniLibs/arm64-v8a/` + `spike/llama-headers/llama.h` + this file |
| APK (new, parity build) | `app/build/outputs/apk/debug/app-debug.apk` — 71.4 MB, 2026‑11‑17 13:51 |
| APK (old, pre-re-base) | `app/build/outputs/apk/release/app-release.apk` — 60.0 MB, 2026‑11‑15 21:03 |

Rollback of everything on this branch: `git checkout main` (the old libs live in `main`'s history).

## What was done this session

The task was narrowly scoped: *will the existing features keep working if we re-base the LLM stack?*
Sizing it turned into doing the first two of the four planned steps.

### 1. The premise in the old handoff was wrong

The old text (and `spike/build-llama-android.sh` lines 5–12) claimed the shipped libraries came from
a **fork** exposing a non-upstream API (`llama_process`, `llama_batch_ext_*`) that upstream lacks,
and that `spike/llama.cpp` had to be restored before anything could be rebuilt.

That is false. `spike/llama-headers/` is an **upstream snapshot**, and the API the shim calls is the
one current upstream *recommends* — upstream now marks the older calls deprecated in favour of ours:

```
llama_batch_init  -> "use llama_batch_ext_init instead"
llama_decode      -> "use llama_process with LLAMA_PROCESS_TYPE_DECODE instead"
```

Consequence: the re-base needs **no C++ changes**. The parallel-JNI plan (`llama_jni_v2.cpp`,
`libsievex_llm_v2.so`, runtime library switch) was sized against a fork that does not exist and was
dropped.

### 2. Evidence for that claim

Pinned comparison target: `ggml-org/llama.cpp` @ `ad9d9e8f50e6ff9a6bfbbdd3aef6d74a8428cf4e`
(2026‑11‑18).

| Check | Result |
|---|---|
| `llama.h` changed lines vs repo snapshot | 42, all additive (DFlash enum, `llama_model_meta_arr_*`, `llama_model_dflash_variant`) plus deprecation wrappers |
| `ggml/ggml.h`, `ggml-backend.h`, `ggml-cpu.h`, `ggml-alloc.h` | **0 changed lines** — byte-identical |
| `llama_process`, `llama_batch_ext_init/add_token/set_pos/set_output_logits`, `llama_memory_seq_rm` | exported by both shipped and new `libllama.so` |
| `n_outputs_max`, `n_outputs_max_per_seq`, `n_reused`, `n_reallocs_*` | all present upstream — the logits-memory guard and `log_perf` survive |
| `llama_jni.cpp` compiled against pinned headers | **exit 0, no source edits** |

### 3. Parity build (baseline kernels)

Built at the pinned commit with `GGML_NATIVE=OFF` and **no** `GGML_CPU_ARM_ARCH`, into
`spike/llama.cpp/build-android-arm64/`. Verified: ELF64 AArch64; same exported API; and
`sdot`/`udot`/`smmla`/`ummla` count in `libggml-cpu.so` = **0**, matching the shipped libs exactly.
That makes it a true parity build: any later speed difference is attributable to `-march` alone.

### 4. Arch build (the point of the exercise)

Same commit, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm`, into
`spike/llama.cpp/build-android-arm64-dotprod/`. `libggml-cpu.so` grows 4.8 → 5.3 MB and contains
**1,393** `sdot`/`udot`/`smmla`/`ummla` instructions where the parity build has 0. The kernels are
demonstrably compiled in.

### 5. Swap, relink, package

- Four libs copied from the parity build into `jniLibs`, then `llvm-strip --strip-unneeded`.
- `spike/llama-headers/` mirrored from the pinned commit (`include/*.h` → `spike/llama-headers/`,
  `ggml/include/*.h` → `spike/llama-headers/ggml/`). `llama.h` now matches upstream exactly;
  the ggml headers were already identical.
- `libsievex_llm.so` relinked **without touching `llama_jni.cpp`**; all four
  `Java_com_sievex_app_llm_LlamaBridge_*` entry points exported; 47 KB.
- `./gradlew.bat --offline assembleDebug` → **BUILD SUCCESSFUL**.

Size effect (repo only, see correction below): the five LLM libs went 61 MiB → 6.6 MiB
(`libllama.so` 47.8 MB → 4.5 MB; `libggml-base` 9.2 → 1.1; `libggml-cpu` 5.0 → 0.9; `libggml` 1.8 →
0.12).

### 6. Correction worth carrying forward

The committed `.so` files are unstripped, but **AGP strips native libs at package time**
(`stripDebugDebugSymbols`). Measured: the 2026‑11‑15 release APK carries a **4.4 MB** `libllama.so`;
the new debug APK carries **4.5 MB**. So the 55 MB was repo/clone weight, **never** install size.
Stripping the committed copies is still correct — it just buys no APK reduction.

### 7. Docs corrected

`HANDOFF.md` (this file) and the header comment + `die` message in `spike/build-llama-android.sh`
were corrected. Committed as `a04becc`. Detailed evidence lives in the gitignored working note
`docs/llm-rebase-brief.md`.

## Reproducing the native build

`spike/llama.cpp` is gitignored; recreate it with a shallow upstream clone, then configure and build
**explicit targets** (see the build failure below for why).

```powershell
$sdk = Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"
$bin = Join-Path $sdk "cmake\4.1.2\bin"          # ships cmake + ninja
$ndk = Join-Path $sdk "ndk\28.2.13676358"

git clone --depth 1 https://github.com/ggml-org/llama.cpp spike/llama.cpp

& "$bin\cmake.exe" -S spike/llama.cpp -B spike/llama.cpp/build-android-arm64 -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release `
  -DBUILD_SHARED_LIBS=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF `
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF

& "$bin\cmake.exe" --build spike/llama.cpp/build-android-arm64 `
  --target llama ggml ggml-base ggml-cpu -j 8
```

For the arch variant use `-B spike/llama.cpp/build-android-arm64-dotprod` and add
`-DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm"`.

Then mirror headers and relink the shim:

```bash
cp spike/llama.cpp/include/*.h        spike/llama-headers/
cp spike/llama.cpp/ggml/include/*.h   spike/llama-headers/ggml/
# then, exactly as spike/build-llm-jni.sh does:
#   aarch64-linux-android30-clang++ -shared -fPIC -O2 -std=c++17 \
#     -I spike/llama-headers -I spike/llama-headers/ggml \
#     app/src/main/cpp/llama_jni.cpp \
#     -L app/src/main/jniLibs/arm64-v8a -lllama -lggml -lggml-cpu -lggml-base \
#     -llog -landroid -static-libstdc++ -o app/src/main/jniLibs/arm64-v8a/libsievex_llm.so
```

On Windows the NDK wrapper is `aarch64-linux-android30-clang++.cmd` (the extensionless file is a
POSIX script PowerShell will not run).

## What is still missing

Ordered by risk. **Nothing below has been verified at runtime — there is no device attached to this
environment and none has been during any of this work.**

1. **On-device A/B of the parity build.** Install `app-debug.apk`, run a fixed prompt set through
   Note / List / Steps plus two lens reports, and compare *parsed fields and assembled markdown* —
   not bytes. Greedy sampling makes it nearly deterministic, but this is newer upstream and a
   different compiler, so expect a few field-level differences. Also watch for `SIGILL`, missing
   symbols at `System.loadLibrary`, and first-token latency.
2. **`spike/bench.sh` before/after.** No pp/tg tok/s numbers exist for either build; the logcat
   `SievexLLM` lines (`pp … tok/s | tg … tok/s`) and `spike/bench.py` are the instruments.
3. **Arch libs are not in the app.** They exist only in `build-android-arm64-dotprod/`. Promotion
   needs (a) the parity build to pass on device first, (b) a CPU-feature guard so `+i8mm` cannot
   `SIGILL` on a core without it (`android_getCpuFeatures` / `cpufeatures`), or a single-target build.
4. **No output-regression harness.** Nothing pins expected model output, so "it still works" is
   currently a human judgement. A recorded prompt/expected-JSON fixture set would make future
   library bumps testable.
5. **The full build still fails** on `bin/llama`: upstream's unified CLI app links
   `llama-server-impl` / `llama-cli-impl`, which `-DLLAMA_BUILD_SERVER=OFF` removes. Two options:
   build explicit targets (what was done here), or stop disabling the server. `spike/build-llama-android.sh`
   still runs a bare `cmake --build`, so **as written it will fail** — it needs the `--target` fix.
6. **`spike/build-llama-android.sh` still copies straight into `jniLibs`** and does not distinguish
   parity from arch builds, so the two variants cannot coexist. Give each its own output directory
   (or an `ARM_ARCH`-derived one).
7. **No headers↔libs consistency guard.** The JNI compiles against `spike/llama-headers/` and links
   against `jniLibs/`. If the two drift, it is an ABI mismatch at runtime, not a build error. Worth a
   script check pinning the upstream commit in one place.
8. **Binaries are uncommitted** on this branch on purpose; decide the policy (commit the parity libs
   now, or hold until device verification).
9. **Release build + version bump** not done; `dist/` is still stale at 0.16.
10. **Media work (P1/P2) untouched** — separate track, pure Kotlin, no interaction with this branch.
11. **Vision is now less blocked than this file used to say** — see below.

## Vision: revised status

The pinned upstream commit builds `tools/mtmd` and `llama-llava-cli`, `llama-gemma3-cli`,
`llama-minicpmv-cli`, `llama-qwen2vl-cli`. So upstream **does** now carry vision (`mtmd`), which the
shipped libs did not. The gate is therefore app-side work plus a memory strategy, not a matmul-kernel
rebuild:

- **MiniCPM‑V 4.7 1B** (openbmb, Apache‑2.0, official GGUF). Footprint is **not** 700 MB: Q4_K_M
  672 MB + required `mmproj-model-f16` 1,110 MB ≈ **1.6–1.8 GB**.
- `LiquidAI/LFM3-VL-2B-GGUF` exists (same family as the current text model) and is probably the more
  consistent candidate; `LFM3-Omni-2B-GGUF` (audio+vision) is a curiosity.
- Co-residency does not work: ASR 0.65 GB + LLM 1.1 GB + VLM ~1.7 GB. Needs on-demand load/unload
  and shipping the `mtmd` library alongside the four the build script currently copies.

## In-flight design: Spaces become media containers

Unchanged and **not implemented**. User asks: screenshot a chart while walking, file it into a space
without detouring through the gallery; notes and images related; space becomes the context bucket.

Decisions already made:

1. **Deleting a space deletes its images** (rows, originals, thumbnails) after a dialog that also
   says its notes will be kept.
2. Relationship direction: **go toward C** — a mixed chronological feed of notes and images in the
   space ("Context" + existing "Insights" tabs). Manual linking optional, many-to-many.
3. **Save & record** exists in the share sheet; the user also wants a short typed **context/caption**
   field when saving.
4. Captions (not OCR) are the bridge to the text-only LLM; OCR is not the answer for charts.
5. Share sheet emphasis: **Save** primary (never start the mic by accident), **Save & record**
   prominent secondary.
6. Vision is **not** in the media scope.

Proposed build order:

- **P1 data + capture:** add `createdAt: Long` to `StoredNote` (migration: synthetic descending
  timestamps from current newest-first order for existing notes; real timestamps after); a
  `MediaStore` (`filesDir/media/originals` + `thumbs` + `media.json`, `MediaItem(id, spaceId,
  noteIds, file, thumb, caption, addedAt, width, height, bytes, sha256)`; dedupe by SHA‑256;
  thumbnails via `ImageDecoder` at ~320 px); share target `ACTION_SEND`/`ACTION_SEND_MULTIPLE`
  image/* (dedicated translucent activity); file sheet (preview, space defaulting to current,
  caption, Save / Save & record); Save & record sends `START_BURST` with a pending `mediaId` and
  the service links the new note after `NoteStore.add`; space-delete cascade.
- **P2 C UI:** mixed Context feed (note cards unchanged; image cards with thumb/caption/link count);
  viewer with pinch-zoom; link-to-notes checklist; image strip on note detail; Photo Picker for
  in-app add.
- **P3:** captions in lens prompts; optional OCR later; vision afterwards.

Constraints: images must be copied into app-private storage (never hold a gallery URI); the app has
no image loader dependency (Coil vs a small `ImageDecoder` cache — undecided); sharing images out
needs a `FileProvider`; `SpaceStore.delete` currently calls `NoteStore.clearSpace` and removes
artifacts — the media cascade goes there.

## Source map (`app/src/main/java/com/sievex/app/`)

| File | Role |
|---|---|
| `CaptureService.kt` | Armed foreground service: mic closed until a burst; queue worker; model warm/unload; blank-audio quarantine; burst format snapshot. |
| `BurstRecorder.kt` / `WavWriter.kt` | 16 kHz mono capture, 90 s cap, WAV on disk first. |
| `JobQueue.kt` | Durable JSON queue (`jobs.json`), carries `spaceId` + `format`. |
| `Pipeline.kt` | UI-visible stage + `PipelineStep` enum (labels live in the UI, not here). |
| `asr/AsrEngine.kt` | sherpa-onnx recognizer singleton (threads = cores, max 4). |
| `asr/ModelInstaller.kt` | Parakeet download, SHA‑256 pinned, resumable. |
| `llm/Transform.kt` | Four formats (Note/List/Steps/Plain), ChatML prompt, GBNF grammar, JSON parse, Spanish clause, one retry with a larger cap. |
| `llm/Lens.kt` | Space report generation (markdown), free-form sampling, `##` assistant prefill, heading repair, citation stripping. |
| `llm/LlamaBridge.kt` | JNI contract: `nativeLoad`, `nativeComplete(prompt, maxTokens, threads, batchThreads, grammar, progress)`, `nativeReset`, `nativeUnload`; UTF‑8 byte arrays. |
| `llm/TransformModel.kt` | LFM3 GGUF download, SHA‑256 pinned. |
| `llm/LlmGate.kt` | Single-caller gate for the native handle + lens/transform. |
| `LensService.kt` | `dataSync` foreground service for lens reports; notifications + deep link to space/lens. |
| `NoteStore.kt` / `SpaceStore.kt` / `LensStore.kt` | JSON stores with atomic writes and quarantine of unreadable files. |
| `NoteFormat.kt` | Format enum + persisted `Formats.next` selection. |
| `AppLocale.kt` | Language choice (en/es), context wrapping, activity recreate. |
| `MainActivity.kt` | Hosts Compose; deep-link extras; `attachBaseContext` locale; activity kick. |
| `MediaWatcher.kt` | NotificationListenerService detecting podcast pause/resume. |
| `ui/AppUi.kt` | The whole UI (~3,900 lines): record, notes, spaces/lenses, actions, settings, editors, dialogs, linkified text. |
| `ui/Onboarding.kt` | Three-page first-run flow + prefs. |
| `ui/Theme.kt` | Colors/typography. |
| `app/src/main/cpp/llama_jni.cpp` | Persistent context, LCP KV reuse, grammar sampler, brace stop, phase callbacks, thread split, perf log. **Unchanged by the re-base.** |

Strings: `res/values/strings_{common,system,ui}.xml` + `res/values-es/…` (254 keys, parity verified;
keep both in sync). `docs/performance-audit.md` is the performance record.

## Implemented features (unchanged by this branch)

- Earbud/podcast-pause trigger via notification access + on-screen record; 90 s cap; durable queue.
- On-device ASR; four note formats (Note, List, Steps, Plain — Plain needs no LLM); per-capture
  format picker, persisted and snapshotted at burst start.
- Grammar-constrained JSON, no transcript echo, persistent LLM context with KV prefix reuse,
  warm models at arm, blank-audio quarantine, action-labelled progress (phases from the native
  decode loop).
- Notes: manual New note composer (typed text, optional AI shaping, space, format); editor with
  items; Reanalyze with any format (never overwrites a concurrent edit; does not stamp "edited").
- Spaces: notes + 6 built-in lenses + custom lenses; reports in a foreground service with
  completion notifications and deep link to space+lens.
- Links: `http(s)`/`www` in note text, list items and reports are tappable (hold-to-copy; list items
  get a Copy button).
- English/Spanish switch (UI + model output language); localized modes/formats/lens names/steps.
- First-run onboarding; model setup cards de-branded.

Failure behaviour worth knowing: if the LLM fails, `Transform.apply` returns null and
`CaptureService` still files the note — raw transcript, derived 9-word title, `mode = "Note"`,
format forced to `PLAIN`. A broken LLM costs shaping, never the capture.

## Performance state

Done: prompt/token reduction, grammar, context reuse, `n_outputs_max=1`, context ceiling, UTF‑8
safety, ASR threads, warm/unload, 90 s cap, benchmark harness (`spike/bench.sh` + `bench.py`).

Open: the app now ships the **parity** build (still generic NEON, 0 `dotprod`). The arch build exists
but is not shipped. Expected gain from `armv8.2-a+dotprod+i8mm` remains the estimate ~2–3× prefill /
1.2–1.6× decode — **unmeasured**, no device.

## Environment gotchas

- **No device is attached.** No `adb`, no on-device numbers, no runtime verification of any kind.
- This session had **no `ops_bash` tool** despite the workspace note; `pwsh` worked for everything.
  Bash syntax in the repo's `spike/*.sh` is still correct for a machine with bash.
- `cmake -B <dir>` without `-S <src>` defaults the source to the cwd — pass `-S`.
- Ninja is not on `PATH`; it ships in `$SDK/cmake/4.1.2/bin`, so prepend that directory.
- The NDK clang wrapper on Windows is `aarch64-linux-android30-clang++.cmd`.
- Gradle works offline; `lintDebug` fails on a missing `kotlin-stdlib-jdk8:1.8.20` androidTest
  dependency, not on app code.
- No test suite. Verification is compile + `adb`/logcat + `spike/bench.sh`.
- `dist/` is gitignored and stale (0.16). `docs/*-brief.md` are gitignored working notes;
  `CONTEXT.md`, `SPEC.md`, `README.md`, `docs/performance-audit.md` are tracked.

## Suggested next actions

1. **Plug in the phone** and do items 1–2 of "What is still missing": parity output comparison and
   tok/s. This is the gate for everything else; without it the re-base is unproven.
2. If parity passes, add the CPU-feature guard, point `jniLibs` at the arch build, and repeat the
   same comparison for the speed win.
3. Fix `spike/build-llama-android.sh` (`--target`, per-variant output directory, headers↔libs pin).
4. Commit the binaries once (2) passes; merge `llm-rebase` → `main`; rebuild the release APK and
   bump the version.
5. Then media P1/P2 (independent, pure Kotlin), and vision last — now only blocked on app-side
   `mtmd` plumbing and the memory strategy, not on the runtime.
