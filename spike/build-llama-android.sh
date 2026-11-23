#!/usr/bin/env bash
# Cross-compile llama.cpp for Android arm64-v8a with tuned ggml CPU kernels, copy the four
# libraries the app links against into app/src/main/jniLibs/arm64-v8a, and strip them there.
#
# PREREQUISITES - read this before running:
#   1. The llama.cpp source tree is not checked in; put any recent upstream checkout at
#      spike/llama.cpp (gitignored):
#        git clone --depth 1 https://github.com/ggml-org/llama.cpp spike/llama.cpp
#      Any recent upstream commit works. The shipped libraries were built from an upstream
#      snapshot, and the API llama_jni.cpp calls (llama_process / llama_batch_ext_*) is current
#      upstream API, not a fork extension: upstream has since deprecated llama_decode /
#      llama_batch_init in favour of exactly these calls.
#      Keep spike/llama-headers/ in step with whatever commit is built here - the JNI is
#      compiled against those headers and linked against these libraries, so a mismatch is an
#      ABI mismatch, not a build error.
#   2. The Android NDK plus a CMake/Ninja install are required.
#
# WHY THE ARCH FLAG IS THE POINT:
#   GGML_NATIVE=OFF keeps the build reproducible, but on its own it leaves ggml on the baseline
#   armv8-a target: no dotprod, no i8mm. The build still succeeds and libggml-cpu.so still loads,
#   but it quietly runs the scalar kernels on a phone that has both. GGML_CPU_ARM_ARCH pins the
#   exact -march, so the shipped object carries the sdot/smmla kernels the device can actually
#   use. There is no runtime error when this is wrong, only a slower benchmark.
#
# ENV:
#   BACKEND_DL       set to 1 (default) to build dynamic CPU backend variants (armv8.0..armv9.2),
#                    or 0 for a single static-CPU libggml-cpu.so controlled by ARM_ARCH
#   ARM_ARCH         when BACKEND_DL=0: -march for ggml's CPU backend, or "baseline" / ""
#   OUT              output directory for stripped .so files (default: spike/prebuilt/<variant>)
#   INSTALL_JNILIBS  set to 1 to also copy stripped libs into app/src/main/jniLibs/arm64-v8a
#   SYNC_HEADERS     set to 1 to update spike/llama-headers/ and UPSTREAM_COMMIT from spike/llama.cpp
#   KLEIDIAI         set to 1 to add -DGGML_CPU_KLEIDIAI=ON (Arm's KleidiAI kernels; see below)
#   SDK, NDK         override the auto-detected Android SDK / NDK roots
#   JOBS             build parallelism (default: 8)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/llama.cpp"
HEADERS="$HERE/llama-headers"
JNILIBS="$HERE/../app/src/main/jniLibs/arm64-v8a"

SDK=${SDK:-"$HOME/AppData/Local/Android/Sdk"}
NDK=${NDK:-}
BACKEND_DL=${BACKEND_DL:-1}
ARM_ARCH=${ARM_ARCH:-baseline}
INSTALL_JNILIBS=${INSTALL_JNILIBS:-0}
SYNC_HEADERS=${SYNC_HEADERS:-0}
KLEIDIAI=${KLEIDIAI:-1}
JOBS=${JOBS:-8}

if [ "$BACKEND_DL" = "1" ]; then
  VARIANT="dl"
  BUILD="$SRC/build-android-arm64-dl"
elif [ -z "$ARM_ARCH" ] || [ "$ARM_ARCH" = "baseline" ]; then
  ARM_ARCH=""
  VARIANT="baseline"
  BUILD="$SRC/build-android-arm64"
elif [ "$ARM_ARCH" = "armv8.2-a+dotprod+i8mm" ]; then
  VARIANT="dotprod"
  BUILD="$SRC/build-android-arm64-dotprod"
else
  VARIANT=$(printf '%s' "$ARM_ARCH" | tr '+/: ' '----')
  BUILD="$SRC/build-android-arm64-$VARIANT"
fi

OUT=${OUT:-"$HERE/prebuilt/$VARIANT"}

# The shared libraries shipped into the APK.
if [ "$BACKEND_DL" = "1" ]; then
  LIBS="libllama.so libggml.so libggml-base.so libggml-cpu-android_armv8.0_1.so libggml-cpu-android_armv8.2_1.so libggml-cpu-android_armv8.2_2.so libggml-cpu-android_armv8.6_1.so libggml-cpu-android_armv9.0_1.so libggml-cpu-android_armv9.2_1.so libggml-cpu-android_armv9.2_2.so"
else
  LIBS="libllama.so libggml.so libggml-base.so libggml-cpu.so"
fi

log() { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- toolchain discovery ---------------------------------------------------

if [ -z "$NDK" ]; then
  # Pin the version the other spike scripts use, but fall back to the newest installed NDK
  # rather than failing outright on a version bump.
  if [ -f "$SDK/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" ]; then
    NDK="$SDK/ndk/28.2.13676358"
  else
    for cand in "$SDK"/ndk/*; do
      [ -f "$cand/build/cmake/android.toolchain.cmake" ] && NDK="$cand"
    done
  fi
fi
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] \
  || die "no NDK found (set NDK=/path/to/ndk); looked under $SDK/ndk"

# The SDK's bundled CMake/Ninja are what the rest of spike/ uses; prefer them when present.
if [ -d "$SDK/cmake/4.1.2/bin" ]; then
  PATH="$SDK/cmake/4.1.2/bin:$PATH"
fi
command -v cmake >/dev/null 2>&1 || die "cmake not found on PATH"

# llvm-strip lives in the NDK's host prebuilt directory; the host name differs per OS, and
# Windows tool names carry .exe.
find_llvm_strip() {
  host_prebuilt=""
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_prebuilt=windows-x86_64 ;;
    Linux) host_prebuilt=linux-x86_64 ;;
    Darwin) host_prebuilt=darwin-x86_64 ;;
  esac
  for prebuilt in ${host_prebuilt:+$host_prebuilt} windows-x86_64 linux-x86_64 darwin-x86_64; do
    for tool in llvm-strip llvm-strip.exe; do
      candidate="$NDK/toolchains/llvm/prebuilt/$prebuilt/bin/$tool"
      if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
  done
  return 1
}

STRIP=$(find_llvm_strip) || die "llvm-strip not found under $NDK/toolchains/llvm/prebuilt/*/bin"

[ -f "$SRC/CMakeLists.txt" ] \
  || die "no llama.cpp source at $SRC - clone upstream there (see the top of this script)"

# --- headers / upstream commit consistency guard ---------------------------

SRC_REV=""
if command -v git >/dev/null 2>&1 && [ -d "$SRC/.git" ]; then
  SRC_REV=$(git -C "$SRC" rev-parse HEAD)
fi

if [ "$SYNC_HEADERS" = "1" ]; then
  log "== syncing headers -> $HEADERS =="
  mkdir -p "$HEADERS/ggml"
  cp -f "$SRC"/include/*.h "$HEADERS/"
  cp -f "$SRC"/ggml/include/*.h "$HEADERS/ggml/"
  if [ -n "$SRC_REV" ]; then
    printf '%s\n' "$SRC_REV" > "$HEADERS/UPSTREAM_COMMIT"
  fi
else
  if [ -f "$HEADERS/UPSTREAM_COMMIT" ] && [ -n "$SRC_REV" ]; then
    PIN_REV=$(tr -d ' \t\r\n' < "$HEADERS/UPSTREAM_COMMIT")
    if [ "$SRC_REV" != "$PIN_REV" ]; then
      die "llama.cpp is at $SRC_REV but $HEADERS/UPSTREAM_COMMIT pins $PIN_REV (set SYNC_HEADERS=1 to update headers)"
    fi
  fi
  for h in "$SRC"/include/*.h; do
    base=$(basename "$h")
    if [ ! -f "$HEADERS/$base" ] || ! cmp -s "$h" "$HEADERS/$base"; then
      die "header drift: $HEADERS/$base differs from $SRC/include/$base (set SYNC_HEADERS=1 to update)"
    fi
  done
  for h in "$SRC"/ggml/include/*.h; do
    base=$(basename "$h")
    if [ ! -f "$HEADERS/ggml/$base" ] || ! cmp -s "$h" "$HEADERS/ggml/$base"; then
      die "header drift: $HEADERS/ggml/$base differs from $SRC/ggml/include/$base (set SYNC_HEADERS=1 to update)"
    fi
  done
fi

# --- configure -------------------------------------------------------------

log "== configuring =="
log "   VARIANT  : $VARIANT"
log "   NDK      : $NDK"
log "   ARM_ARCH : ${ARM_ARCH:-<baseline>}"
log "   KLEIDIAI : $KLEIDIAI"
log "   OUT      : $OUT"

# Expanded unquoted on purpose: it is a list of flags and the value contains no spaces.
EXTRA_ARGS=""
if [ "$BACKEND_DL" = "1" ]; then
  EXTRA_ARGS="-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON"
elif [ -n "$ARM_ARCH" ]; then
  EXTRA_ARGS="-DGGML_CPU_ARM_ARCH=$ARM_ARCH"
fi
if [ "$KLEIDIAI" = "1" ]; then
  # Needs network access at configure time: ggml's CMake FetchContent clones KleidiAI then.
  EXTRA_ARGS="$EXTRA_ARGS -DGGML_CPU_KLEIDIAI=ON"
else
  EXTRA_ARGS="$EXTRA_ARGS -DGGML_CPU_KLEIDIAI=OFF"
fi

# Configure on every run. The arch flag is the entire reason to run this script, and a cached
# CMakeCache.txt from an earlier baseline build would otherwise keep the old -march silently.
# shellcheck disable=SC2086
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_CURL=OFF \
  $EXTRA_ARGS

# A ggml too old to know GGML_CPU_ARM_ARCH ignores it with only a CMake notice, and the build
# would silently stay on baseline armv8-a. Say so loudly instead of shipping slow kernels.
if [ -f "$BUILD/CMakeCache.txt" ]; then
  if [ -n "$ARM_ARCH" ]; then
    grep -q '^GGML_CPU_ARM_ARCH:' "$BUILD/CMakeCache.txt" \
      || warn "this llama.cpp/ggml has no GGML_CPU_ARM_ARCH option; the build is baseline armv8-a"
  fi
  if [ "$KLEIDIAI" = "1" ]; then
    grep -q '^GGML_CPU_KLEIDIAI:' "$BUILD/CMakeCache.txt" \
      || warn "this ggml has no GGML_CPU_KLEIDIAI option; KleidiAI kernels were not built"
  fi
fi

# --- build -----------------------------------------------------------------

log "== building (-j $JOBS) =="
# Build only the shared libraries llama_jni.cpp and ggml dynamic loading use; a bare build also
# tries to link bin/llama, which fails when -DLLAMA_BUILD_SERVER=OFF disables llama-server-impl.
if [ "$BACKEND_DL" = "1" ]; then
  cmake --build "$BUILD" --target llama ggml ggml-base \
    ggml-cpu-android_armv8.0_1 ggml-cpu-android_armv8.2_1 ggml-cpu-android_armv8.2_2 \
    ggml-cpu-android_armv8.6_1 ggml-cpu-android_armv9.0_1 ggml-cpu-android_armv9.2_1 \
    ggml-cpu-android_armv9.2_2 -j "$JOBS"
else
  cmake --build "$BUILD" --target llama ggml ggml-base ggml-cpu -j "$JOBS"
fi

# --- copy into the APK's jniLibs ------------------------------------------

mkdir -p "$OUT"
log "== copying into $OUT =="
# $LIBS is a plain space-separated list (library names never contain spaces). Find output is fed
# through a here-doc instead: this repo's path itself contains a space, so splitting `$(find ...)`
# on whitespace would corrupt every path.
for lib in $LIBS; do
  found=""
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    found="$candidate"
    break
  done <<EOF
$(find "$BUILD" -type f -name "$lib" | sort)
EOF
  [ -n "$found" ] || die "build did not produce $lib (looked under $BUILD)"
  cp -f "$found" "$OUT/$lib"
  log "   $lib <- $found"
done

# Anything else the build emitted is reported but not shipped, so a surprise Vulkan/RPC library
# cannot silently grow the APK.
while IFS= read -r extra; do
  [ -n "$extra" ] || continue
  name=$(basename "$extra")
  case " $LIBS " in
    *" $name "*) ;;
    *) warn "not shipped (not needed by llama_jni.cpp): $name" ;;
  esac
done <<EOF
$(find "$BUILD" -type f -name '*.so' | sort)
EOF

# --- strip the shipped copies ---------------------------------------------

# AGP strips at package time, but the files sitting in jniLibs/ are what git, backups and the
# on-disk repo see: unstripped they are ~47 MB for libllama.so alone. Strip the copies, not the
# build tree, so a crash can still be debugged against build-android-arm64.
log "== stripping (debug info is not shipped) =="
for lib in $LIBS; do
  before=$(wc -c < "$OUT/$lib" | tr -d ' ')
  "$STRIP" --strip-unneeded "$OUT/$lib"
  after=$(wc -c < "$OUT/$lib" | tr -d ' ')
  printf '   %-18s %6s KB -> %6s KB\n' "$lib" "$((before / 1024))" "$((after / 1024))"
done

if [ "$INSTALL_JNILIBS" = "1" ] && [ "$OUT" != "$JNILIBS" ]; then
  log "== installing into $JNILIBS =="
  mkdir -p "$JNILIBS"
  for lib in $LIBS; do
    cp -f "$OUT/$lib" "$JNILIBS/$lib"
  done
fi

log "== done: $OUT =="
ls -lh "$OUT"
