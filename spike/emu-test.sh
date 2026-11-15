#!/usr/bin/env bash
# Boot a clean emulator and test whether our APKs install at all.
set -uo pipefail
cd "$(dirname "$0")/.."

SDK="$HOME/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
EMU="$SDK/emulator/emulator.exe"
ADB="$SDK/platform-tools/adb.exe"

echo "== creating avd =="
"$AVDM" delete avd -n sievex-test >/dev/null 2>&1 || true
echo "no" | "$AVDM" create avd -n sievex-test \
  -k "system-images;android-36.1;google_apis_playstore;x86_64" --force || exit 1

echo "== starting emulator =="
"$EMU" -avd sievex-test -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot > spike/emu.log 2>&1 &
EMUPID=$!

"$ADB" wait-for-device
BOOT=""
for i in $(seq 1 150); do
  BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$BOOT" = "1" ]; then break; fi
  sleep 2
done
echo "boot_completed=$BOOT"
echo "device sdk=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "device abi=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"

echo "== installing probe =="
"$ADB" install -r dist/probe.apk 2>&1 | tail -5

echo "== installing sievex 0.6 =="
"$ADB" install -r dist/sievex-0.6.apk 2>&1 | tail -5

echo "== installed packages =="
"$ADB" shell pm list packages 2>/dev/null | grep -i sievex || echo "none matched"

"$ADB" emu kill >/dev/null 2>&1 || true
kill $EMUPID >/dev/null 2>&1 || true
echo "== done =="
