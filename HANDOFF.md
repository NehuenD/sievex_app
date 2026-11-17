# Handoff — Sievex

Repo: https://github.com/NehuenD/sievex_app (public) · branch `main` · clean and in sync
Last commit: `51d4efa` — onboarding trigger copy + de-branded model setup
App version: `versionCode 16` / `versionName "0.16"` (not bumped since 0.16 was shipped)

## What this is

Personal, fully offline Android app. It records 20–60 s voice bursts (triggered by the podcast's
pause through a notification listener, or the record button), transcribes them with sherpa-onnx
Parakeet TDT 0.6B v3 int8, shapes each into a note with a local LLM (LFM3‑1.7B Q4_K_M through a
hand-written llama.cpp JNI shim), and organises notes into spaces with generated "lens" reports.
UI and model output are English or Spanish, switched in Settings.

## Build & install

- JDK 21, Android SDK 36, NDK `28.2.13676358` (only to rebuild the JNI shim), arm64-v8a only.
- No CMake step in Gradle: native libraries are prebuilt in `app/src/main/jniLibs/arm64-v8a/`.
- `./gradlew.bat --offline assembleDebug` / `assembleRelease`
- `bash spike/build-llm-jni.sh` rebuilds `libsievex_llm.so` from `app/src/main/cpp/llama_jni.cpp`
  (needs the prebuilt `libllama/libggml*.so`; `build-app.sh` calls it when the NDK is present).
- Release APK: `app/build/outputs/apk/release/app-release.apk` (~60 MB, signed with the local
  keystore; `keystore.properties` and `*.jks` are gitignored, so a fresh clone only makes debug).
- Current built APKs are from 2026‑11‑15 21:03 (release) and 20:46 (debug) and include `51d4efa`.

## Source map (`app/src/main/java/com/sievex/app/`)

| File | Role |
|---|---|
| `CaptureService.kt` | Armed foreground service: mic closed until a burst; queue worker; model warm/unload; blank-audio quarantine; burst format snapshot. |
| `BurstRecorder.kt` / `WavWriter.kt` | 16 kHz mono capture, 90 s cap, WAV on disk first. |
| `JobQueue.kt` | Durable JSON queue (`jobs.json`), carries `spaceId` + `format`. |
| `Pipeline.kt` | UI-visible stage + `PipelineStep` enum (labels live in the UI, not here). |
| `asr/AsrEngine.kt` | sherpa-onnx recognizer singleton (threads = cores, max 4). |
| `asr/ModelInstaller.kt` | Parakeet download, SHA‑256 pinned, resumable. |
| `llm/Transform.kt` | Four formats (Note/List/Steps/Plain), ChatML prompt, GBNF grammar, JSON parse, Spanish clause. |
| `llm/Lens.kt` | Space report generation (markdown), language clause, citation stripping. |
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
| `app/src/main/cpp/llama_jni.cpp` | Persistent context, longest-common-prefix KV reuse, grammar sampler, brace stop, phase callbacks, thread split, perf log. |

Strings: `res/values/strings_{common,system,ui}.xml` + `res/values-es/…` (254 keys, parity verified;
keep both files in sync). `docs/performance-audit.md` is the performance record.

## Implemented features (as of `51d4efa`)

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
- Links: `http(s)`/`www` in note text, list items and reports are tappable (opens default handler)
  and hold-to-copy; list items get a Copy button.
- English/Spanish switch (UI + model output language); localized modes/formats/lens names/steps.
- First-run onboarding; model setup cards de-branded ("transcription model", "language model (LLM)").

## Performance state

Done: prompt/token reduction, grammar, context reuse, `n_outputs_max=1`, context ceiling, UTF‑8
safety, ASR threads, warm/unload, 90 s cap, benchmark harness (`spike/bench.sh` + `bench.py`).

**Open:** the LLM native libs were built with `GGML_NATIVE=OFF` and no ARM arch flags → generic
NEON only (zero `dotprod`/`i8mm`/`smmla` instructions in `libggml-cpu.so`). The ASR runtime *is*
armv8.2-optimized. Rebuilding ggml with `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm` (+ optional
KleidiAI) is expected to give ~2–3× prefill / 1.2–1.6× decode. **Blocked:** `spike/llama.cpp`
(the fork source the shipped libs came from) is not in the repo; the headers expose a non-upstream
API (`llama_process`, `llama_batch_ext_*`). `spike/build-llama-android.sh` already has the flags.

## In-flight design: Spaces become media containers

User asks: screenshot a chart while walking, file it into a space without detouring through the
gallery; notes and images related; space becomes the context bucket. **Nothing implemented yet.**

Decisions already made:

1. **Deleting a space deletes its images** (rows, originals, thumbnails) after a dialog that also
   says its notes will be kept.
2. Relationship direction: **go toward C** — a mixed chronological feed of notes and images in the
   space ("Context" + existing "Insights" tabs). Manual linking optional, many-to-many.
3. **Save & record** exists in the share sheet; the user also wants a short typed **context/caption**
   field when saving.
4. Captions (not OCR) are the bridge to the text-only LLM; OCR is not the answer for charts.
5. Share sheet emphasis decided: **Save** primary (never start the mic by accident), **Save &
   record** prominent secondary.
6. Vision is **not** in the media scope; it waits on the native re-base.

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
- **P3:** captions in lens prompts; optional OCR later; vision after re-base.

Constraints/notes for whoever builds it: images must be copied into app-private storage (never hold
a gallery URI); app has no image loader dependency (Coil vs a small `ImageDecoder` cache — decide);
sharing images out needs a `FileProvider`; `SpaceStore.delete` currently calls
`NoteStore.clearSpace` and removes artifacts — the media cascade goes there.

## Vision: findings so far

- **MiniCPM‑V 4.7 1B** is real (openbmb, Oct 2026, Apache‑2.0) with an official llama.cpp GGUF.
  Text backbone is Qwen3.5; vision is SigLIP2. Footprint is **not** 700 MB: Q4_K_M model 672 MB +
  required `mmproj-model-f16` **1,110 MB** (or 4× mmproj 901 MB) ≈ **1.6–1.8 GB**.
  `LiquidAI/LFM3-VL-2B-GGUF` also exists (same family as the current model, tagged llama.cpp) and is
  probably the more consistent candidate; `LFM3-Omni-2B-GGUF` (audio+vision) is a curiosity.
- **Runtime gate:** the shipped `libllama.so` has no `mtmd`/vision and predates these model archs.
  Running any VLM requires re-basing on upstream llama.cpp, plus an on-demand load/unload strategy
  (ASR 0.65 GB + LLM 1.1 GB + VLM ~1.7 GB cannot be co-resident).

## Re-basing the LLM stack: assessment

Evidence: `LiquidAI/LFM3-1.7B-GGUF` is the exact model the app downloads (`architecture: lfm3`,
tagged `llama.cpp`), so upstream can load the same 1.1 GB file — no re-download, no data migration.
Re-base would also finally enable the ARM kernels above.

Blast radius: one C++ file + four `.so` files. Everything above `LlamaBridge` (capture, ASR,
storage, spaces, lenses, formats, reanalysis, localization, media) is insulated by that contract.

Safe method (not yet started): keep the current fork libs; add a parallel JNI (`llama_jni_v2.cpp`,
`libsievex_llm_v2.so`) ported to upstream (`llama_batch`/`llama_decode`; `llama_batch_ext`/
`llama_process` may or may not still exist upstream — diff `include/llama.h` first); select via a
build flavor/flag; verify with a fixed prompt set on device; only then make it default. Rollback is
copying the old libs/JNI back.

Verification items: LFM3 arch + BOS (`<|startoftext|>`) and EOS (`<|im_end|>`) behaviour; the
official chat template inserts `<think></think>` in non-thinking mode (the app builds ChatML by
hand — confirm no output regression); grammar, KV prefix reuse, phase callbacks; prefill/decode
tok/s and memory A/B; `+i8mm` SIGILL risk on old cores (owner's device is a flagship; add a guard or
single-target build).

## Environment gotchas

- **No device is attached** in this agent environment (verified repeatedly); any runtime verification
  needs `adb` with the phone plugged in. On-device profile numbers do not exist yet.
- Windows shell: use the `ops_bash` tool or `pwsh`; there is no `bash` tool.
- Gradle works offline; `lintDebug` fails on a missing `kotlin-stdlib-jdk8:1.8.20` androidTest
  dependency, not on app code.
- No test suite. Verification is compile + `adb`/logcat + `spike/bench.sh`.
- `dist/` is gitignored and stale (last 0.16 APK predates recent features).
- `docs/*-brief.md` are gitignored working notes; `CONTEXT.md`, `SPEC.md`, `README.md`,
  `docs/performance-audit.md` are tracked.

## Suggested next actions

1. Implement media P1 (data + share sheet + Save/Save & record + delete cascade), then P2 (C UI).
2. In parallel/after: start the LLM re-base as its own reversible step; first task is diffing the
   fork headers against upstream `llama.h` to size the JNI port.
3. Once re-based and kernels enabled, re-run `spike/bench.sh` before/after.
4. Only then consider an optional VLM (LFM3‑VL‑2B or MiniCPM‑V 4.7 1B), on demand with unload.
