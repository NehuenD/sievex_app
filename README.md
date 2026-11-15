# Sievex

A personal Android app for capturing a thought by voice while your hands are busy: one earbud tap
(which the podcast app sees as its own pause), a 20–60 second burst, then a fully on-device pass
that turns the audio into a structured Markdown note filed into a vault.

No cloud, no accounts, no network permission. The only network use is the one-time, checksum-pinned
model download.

## Pipeline

```
earbud tap → CaptureService (armed foreground service, mic closed until a burst)
           → BurstRecorder (16 kHz mono PCM WAV, 90 s cap)
           → JobQueue (durable JSON, survives process death)
           → AsrEngine (sherpa-onnx, Parakeet TDT 0.6B v3 int8, greedy)
           → Transform (llama.cpp via JNI, LFM3-1.7B Q4_K_M, grammar-constrained JSON)
           → NoteStore → Markdown in a SAF folder
```

Everything runs on one queue worker, in capture order. ASR and the LLM are serialised on purpose:
a phone that keeps both resident and runs them at once has no thermal headroom left.

## Stack

- Kotlin + Jetpack Compose, `minSdk 30`, `targetSdk 35`, **arm64-v8a only**
- sherpa-onnx (prebuilt) for ASR; llama.cpp (prebuilt, custom JNI shim in `app/src/main/cpp`) for the LLM
- Prebuilt `.so` files live in `app/src/main/jniLibs/arm64-v8a/`; there is no CMake step in Gradle

## Build

Requirements: JDK 21, Android SDK 36, and NDK `28.2.13676358` (only needed to rebuild the JNI shim).

```bash
./gradlew assembleDebug      # or: ./build-app.sh (rebuilds the shim first when the NDK is present)
```

Rebuilding the native shim by hand:

```bash
bash spike/build-llm-jni.sh   # app/src/main/cpp/llama_jni.cpp → libsievex_llm.so
```

## Models (not in this repository)

| Model | Size | Source |
|---|---|---|
| Parakeet TDT 0.6B v3 int8 (encoder/decoder/joiner/tokens) | ~640 MB | downloaded on first run, pinned by SHA-256 in `ModelInstaller.kt` |
| LFM3-1.7B Q4_K_M GGUF | ~1.1 GB | downloaded on first run, pinned by SHA-256 in `TransformModel.kt` |

Both are downloaded by the app itself and verified before install; after that the app is fully
offline. The model files are gitignored.

## Performance

The ASR/LLM audit and the changes it produced are in
[docs/performance-audit.md](docs/performance-audit.md): token-budget reduction, grammar-constrained
JSON, persistent LLM context with KV-prefix reuse, warm models on arm, ASR thread count, and a
90 s burst cap.

On-device benchmarking (no app edits, uses the durable queue's orphan recovery):

```bash
spike/bench.sh <dir-with-16k-mono-wavs> 3
```

One known gap: `spike/llama.cpp` (the fork the shipped libs were built from) is not in this repo,
so the tuned ggml build (`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm`, optional KleidiAI) cannot be
reproduced here yet. `spike/build-llama-android.sh` is ready for the day that source is restored.

## Repository hygiene

`local.properties`, `keystore.properties`, `keystore.jks`, build output, `dist/`, model files and
fetched native sources are gitignored — see [.gitignore](.gitignore). Release signing needs your
own keystore.

## License

Personal project. No license is granted; all rights reserved.
