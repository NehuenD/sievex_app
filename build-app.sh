#!/usr/bin/env bash
# Generate the Gradle wrapper (if missing) and assemble a debug APK.
set -euo pipefail
cd "$(dirname "$0")"

GRADLE=spike/tools/gradle-8.14.3/bin/gradle

if [ ! -f gradlew ]; then
  echo "== generating wrapper =="
  "$GRADLE" wrapper --gradle-version 8.14.3 --distribution-type bin --no-daemon
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
NDK="$SDK/ndk/28.2.13676358"
SHIM=app/src/main/jniLibs/arm64-v8a/libsievex_llm.so
if [ -d "$NDK" ] && [ -f spike/build-llm-jni.sh ]; then
  echo "== rebuilding JNI shim =="
  # The shim is built outside Gradle. Rebuilding it here means a stale libsievex_llm.so can never
  # be packaged alongside newer Kotlin code; without the pinned NDK the existing .so is used as-is.
  bash spike/build-llm-jni.sh
elif [ app/src/main/cpp/llama_jni.cpp -nt "$SHIM" ]; then
  echo "WARNING: llama_jni.cpp is newer than $SHIM and the NDK is missing; the APK ships a stale shim" >&2
fi

echo "== assembling debug =="
./gradlew --no-daemon assembleDebug

echo "== artifacts =="
ls -lh app/build/outputs/apk/debug/ 2>/dev/null || true
