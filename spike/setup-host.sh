#!/usr/bin/env bash
# Fetch the official NeMo-Speech.cpp Windows CPU build, verify it, and probe the CLI.
# Everything lands under spike/ and nothing outside this project is touched.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WT="$ROOT/nemo-win"
mkdir -p "$WT"
cd "$WT"

SHA=4ed9740e361e4653f56c3dce7d1e81883a1c8b831c1bd7d01567fff1130b5b05
URL="https://github.com/NVIDIA/NeMo-Speech.cpp/releases/download/v0.3.0/nemo-speech-0.3.0-windows-x86_64-cpu.zip"

if [ ! -f ns.zip ]; then
  echo "downloading runtime..."
  curl -fL --retry 3 --connect-timeout 20 --progress-bar -o ns.zip "$URL"
fi

echo "$SHA  ns.zip" | sha256sum -c -

if [ ! -e nemo-speech.exe ]; then
  echo "extracting..."
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o ns.zip
  else
    powershell.exe -NoProfile -Command \
      "Expand-Archive -Force -Path '$(cygpath -w ns.zip)' -DestinationPath '$(cygpath -w .)'"
  fi
fi

echo "--- layout ---"
ls -la

EXE="$(find . -maxdepth 4 -name 'nemo-speech.exe' -print -quit)"
echo "EXE=$EXE"

if [ -n "${EXE:-}" ]; then
  echo "--- version ---"
  "$EXE" --version 2>&1 | head -5 || true
  echo "--- help ---"
  "$EXE" --help 2>&1 | head -120 || true
fi
