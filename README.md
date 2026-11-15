# Sievex

Personal Android app that turns a spoken thought into a structured note, entirely on-device: it
listens for a podcast pause, records a 20–60 s burst, transcribes it, and shapes it into a note with
a mode, title, one line, the verbatim text, an inferred *why it matters*, an open question and tags.

No cloud, no accounts. The network is used once, for the checksum-pinned model download. The
interface and the notes it writes are available in English and Spanish; Settings switches both at
once.

## What it does today

**Capture** — an armed foreground service keeps the microphone closed until a burst starts. The
trigger is the podcast's own pause (notification-listener media session) or the on-screen button.
Bursts stop on resume or a second tap and are capped at 90 s. Audio is written to a WAV first and
then queued in a durable JSON queue, so a process death or reboot never loses a burst.

**Transcribe** — sherpa-onnx with Parakeet TDT 0.6B v3 int8 on the CPU, greedy decoding, up to four
threads.

**Shape** — LFM3-1.7B Q4_K_M through a small llama.cpp JNI shim. The reply is grammar-constrained
JSON, so it either parses or cannot be produced. Four formats decide the shape: **Note** (mode,
title, one line, why it matters, open question, tags), **List** and **Steps** (title plus short
items or ordered steps, no insight fields), and **Plain** (your words and a derived title, no model
at all). `verbatim` is the ASR text, never regenerated. The recording status names actions
(transcribing audio → understanding intent → crafting title → creating insights), not models.
The model writes note fields and space reports in the app's language; the transcript is never
translated.

**Write and reshape** — the Notes tab has a *New note* composer: type instead of dictating,
optionally shape it with AI, pick a space and a format. Any note can be reprocessed later with a
format of your choice from its detail screen; only the model's fields are replaced, never your text,
and reprocessing does not mark the note as hand-edited.

**Organise** — notes and spaces are stored on-device (`notes.json`, `spaces.json`; audio as WAV under
`bursts/`). A space can run lenses — Insight, Brainstorm, Reading, Actions, Tensions, Timeline, plus
your own. A lens report is built in a `dataSync` foreground service and announced with a
notification that opens that space and lens; leaving the screen does not cancel it.

**Review** — a Notes list, an Actions view (notes whose mode is Action), and Settings for model
status/downloads, notification access and the event log.

## Not built yet

- Markdown vault export into a SAF folder (the SPEC's filing step). Today notes are app-private
  JSON, not `.md` files.
- Cloud sync, accounts, iOS, live transcript, multi-speaker capture.

## Stack

Kotlin + Jetpack Compose · `minSdk 30`, `targetSdk 35`, arm64-v8a only · prebuilt sherpa-onnx and
llama.cpp under `app/src/main/jniLibs` (no CMake step in Gradle).

## Build

Needs JDK 21, Android SDK 36, and NDK `28.2.13676358` (only to rebuild the JNI shim).

```bash
./gradlew assembleDebug        # or ./build-app.sh, which also rebuilds the shim when the NDK exists
bash spike/build-llm-jni.sh    # app/src/main/cpp/llama_jni.cpp → libsievex_llm.so
```

## Models (downloaded on first run, not in the repo)

| Model | Size | Pinned by |
|---|---|---|
| Parakeet TDT 0.6B v3 int8, four files | ~640 MiB | SHA-256 in `ModelInstaller` |
| LFM3-1.7B Q4_K_M GGUF | ~1.1 GB | SHA-256 in `TransformModel` |

After the download the app runs fully offline.

## Performance

See [docs/performance-audit.md](docs/performance-audit.md). In short: fewer generated tokens,
grammar-constrained JSON, one persistent LLM context with KV-prefix reuse, models warmed at arm
time, ASR threads from the core count, a 90 s burst cap. Benchmarks: `spike/bench.sh <dir-of-wavs> 3`.

Known gap: the llama.cpp fork the shipped libraries were built from is not in this repo, so the
tuned ggml build (dotprod/i8mm, optional KleidiAI) cannot be reproduced here yet;
`spike/build-llama-android.sh` is ready for the day that source is restored.

## Repository notes

`.gitignore` keeps signing material (`keystore.properties`, `*.jks`), `local.properties`, build
output, `dist/`, model files and fetched native sources out of Git; release signing needs your own
keystore. Personal project, no license granted.
