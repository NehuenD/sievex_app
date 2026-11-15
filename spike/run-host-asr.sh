#!/usr/bin/env bash
# Host-side smoke test: pull Parakeet TDT v3, transcribe one English sample, report timing + JSON.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BIN="$ROOT/nemo-win/nemo-speech-0.3.0-windows-x86_64-cpu/bin/nemo-speech.exe"
mkdir -p "$ROOT/audio" "$ROOT/out"

SAMPLE="$ROOT/audio/sample.wav"
if [ ! -s "$SAMPLE" ]; then
  echo "== fetching sample audio =="
  curl -fL --retry 3 --progress-bar -o "$SAMPLE" \
    https://dldata-public.s3.us-east-2.amazonaws.com/2086-149220-0033.wav
fi
ls -lh "$SAMPLE"

echo "== pulling parakeet-tdt =="
"$BIN" pull parakeet-tdt 2>&1 | tail -20

echo "== transcribing (json) =="
T0=$(date +%s%N)
"$BIN" transcribe "$SAMPLE" -m parakeet-tdt -f json --device cpu \
  -o "$ROOT/out/sample.json" 2>&1 | tail -20
T1=$(date +%s%N)
echo "elapsed_ms=$(( (T1 - T0) / 1000000 ))"

echo "== output =="
cat "$ROOT/out/sample.json" 2>/dev/null | head -80
