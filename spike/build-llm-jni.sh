#!/usr/bin/env bash
# Build the llama.cpp JNI shim for Android arm64.
# Requires: NDK, headers in spike/llama-headers, and the prebuilt
# libllama/libggml*.so already present in LIBS_DIR.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${SDK:-$HOME/AppData/Local/Android/Sdk}"
NDK="${NDK:-}"
LIBS_DIR="${LIBS_DIR:-app/src/main/jniLibs/arm64-v8a}"
OUT="${OUT:-$LIBS_DIR}"
HEADERS="spike/llama-headers"
SRC="spike/llama.cpp"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

if [ -z "$NDK" ]; then
  if [ -d "$SDK/ndk/28.2.13676358" ]; then
    NDK="$SDK/ndk/28.2.13676358"
  else
    for cand in "$SDK"/ndk/*; do
      [ -d "$cand/toolchains/llvm/prebuilt" ] && NDK="$cand"
    done
  fi
fi
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] \
  || die "no NDK found (set NDK=/path/to/ndk); looked under $SDK/ndk"

find_ndk_tool() {
  tool_base="$1"
  host_prebuilt=""
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_prebuilt=windows-x86_64 ;;
    Linux) host_prebuilt=linux-x86_64 ;;
    Darwin) host_prebuilt=darwin-x86_64 ;;
  esac
  for prebuilt in ${host_prebuilt:+$host_prebuilt} windows-x86_64 linux-x86_64 darwin-x86_64; do
    for ext in .cmd .exe ""; do
      cand="$NDK/toolchains/llvm/prebuilt/$prebuilt/bin/${tool_base}${ext}"
      if [ -x "$cand" ]; then
        printf '%s\n' "$cand"
        return 0
      fi
    done
  done
  return 1
}

CXX=$(find_ndk_tool "aarch64-linux-android30-clang++") \
  || die "aarch64-linux-android30-clang++ not found under $NDK"
STRIP=$(find_ndk_tool "llvm-strip") \
  || die "llvm-strip not found under $NDK"

if [ -f "$HEADERS/UPSTREAM_COMMIT" ] && [ -d "$SRC/.git" ] && command -v git >/dev/null 2>&1; then
  PIN_REV=$(tr -d ' \t\r\n' < "$HEADERS/UPSTREAM_COMMIT")
  SRC_REV=$(git -C "$SRC" rev-parse HEAD)
  if [ "$SRC_REV" != "$PIN_REV" ]; then
    die "header/source mismatch: $SRC is at $SRC_REV, $HEADERS/UPSTREAM_COMMIT pins $PIN_REV"
  fi
fi

if [ -f "$SRC/CMakeLists.txt" ]; then
  for h in "$SRC"/include/*.h; do
    base=$(basename "$h")
    [ -f "$HEADERS/$base" ] && cmp -s "$h" "$HEADERS/$base" \
      || die "header drift: $HEADERS/$base differs from $SRC/include/$base"
  done
  for h in "$SRC"/ggml/include/*.h; do
    base=$(basename "$h")
    [ -f "$HEADERS/ggml/$base" ] && cmp -s "$h" "$HEADERS/ggml/$base" \
      || die "header drift: $HEADERS/ggml/$base differs from $SRC/ggml/include/$base"
  done
fi

for lib in libllama.so libggml.so libggml-base.so; do
  [ -f "$LIBS_DIR/$lib" ] || die "missing $LIBS_DIR/$lib - cannot link"
done

EXTRA_LIBS=""
if [ -f "$LIBS_DIR/libggml-cpu.so" ]; then
  EXTRA_LIBS="-lggml-cpu"
fi

mkdir -p "$OUT"
# shellcheck disable=SC2086
"$CXX" -shared -fPIC -O2 -std=c++17 \
  -I "$HEADERS" -I "$HEADERS/ggml" \
  app/src/main/cpp/llama_jni.cpp \
  -L "$LIBS_DIR" -lllama -lggml $EXTRA_LIBS -lggml-base \
  -llog -landroid -static-libstdc++ \
  -o "$OUT/libsievex_llm.so"

"$STRIP" --strip-unneeded "$OUT/libsievex_llm.so"
ls -lh "$OUT/libsievex_llm.so"
