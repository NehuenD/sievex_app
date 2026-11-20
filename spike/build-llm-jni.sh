#!/usr/bin/env bash
# Build the llama.cpp JNI shim for Android arm64.
# Requires: NDK, headers in spike/llama-headers, and the prebuilt
# libllama/libggml*.so already present in app/src/main/jniLibs/arm64-v8a.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${SDK:-$HOME/AppData/Local/Android/Sdk}"
NDK="${NDK:-$SDK/ndk/28.2.13676358}"
CXX="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang++"
LIBS_DIR="${LIBS_DIR:-app/src/main/jniLibs/arm64-v8a}"
OUT="${OUT:-$LIBS_DIR}"

if [ -f "spike/llama-headers/UPSTREAM_COMMIT" ] && [ -d "spike/llama.cpp/.git" ] && command -v git >/dev/null 2>&1; then
  PIN_REV=$(tr -d ' \t\r\n' < "spike/llama-headers/UPSTREAM_COMMIT")
  SRC_REV=$(git -C "spike/llama.cpp" rev-parse HEAD)
  if [ "$SRC_REV" != "$PIN_REV" ]; then
    echo "header/source mismatch: spike/llama.cpp is at $SRC_REV, spike/llama-headers/UPSTREAM_COMMIT pins $PIN_REV" >&2
    exit 1
  fi
fi

for lib in libllama.so libggml.so libggml-cpu.so libggml-base.so; do
  if [ ! -f "$LIBS_DIR/$lib" ]; then
    echo "missing $LIBS_DIR/$lib - cannot link" >&2
    exit 1
  fi
done

"$CXX" -shared -fPIC -O2 -std=c++17 \
  -I spike/llama-headers -I spike/llama-headers/ggml \
  app/src/main/cpp/llama_jni.cpp \
  -L "$LIBS_DIR" -lllama -lggml -lggml-cpu -lggml-base \
  -llog -landroid -static-libstdc++ \
  -o "$OUT/libsievex_llm.so"

ls -lh "$OUT/libsievex_llm.so"
