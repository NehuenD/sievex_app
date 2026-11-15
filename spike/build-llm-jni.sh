#!/usr/bin/env bash
# Build the llama.cpp JNI shim for Android arm64.
# Requires: NDK, headers in spike/llama-headers, and the prebuilt
# libllama/libggml*.so already present in app/src/main/jniLibs/arm64-v8a.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="$HOME/AppData/Local/Android/Sdk"
NDK="$SDK/ndk/28.2.13676358"
CXX="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang++"
OUT="app/src/main/jniLibs/arm64-v8a"

for lib in libllama.so libggml.so libggml-cpu.so libggml-base.so; do
  if [ ! -f "$OUT/$lib" ]; then
    echo "missing $OUT/$lib - cannot link" >&2
    exit 1
  fi
done

"$CXX" -shared -fPIC -O2 -std=c++17 \
  -I spike/llama-headers -I spike/llama-headers/ggml \
  app/src/main/cpp/llama_jni.cpp \
  -L "$OUT" -lllama -lggml -lggml-cpu -lggml-base \
  -llog -landroid -static-libstdc++ \
  -o "$OUT/libsievex_llm.so"

ls -lh "$OUT/libsievex_llm.so"
