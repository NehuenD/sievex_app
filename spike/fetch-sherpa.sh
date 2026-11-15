#!/usr/bin/env bash
# Fetch sherpa-onnx prebuilt Android libs + the Kotlin API sources for the newest version tag.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p sherpa
cd sherpa

TAG=$(curl -s "https://api.github.com/repos/k2-fsa/sherpa-onnx/tags?per_page=100" \
  | grep -oE '"name": *"v[0-9][^"]*"' | sed -E 's/.*"(v[^"]*)"/\1/' | sort -V | tail -1)
echo "version tag: $TAG"
if [ -z "${TAG:-}" ]; then
  echo "could not discover a version tag" >&2
  exit 1
fi

if [ ! -f android.tar.bz2 ]; then
  curl -fL --retry 3 -o android.tar.bz2 \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/$TAG/sherpa-onnx-$TAG-android.tar.bz2"
fi

if [ ! -f src.tar.gz ]; then
  curl -fL --retry 3 -o src.tar.gz \
    "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/$TAG.tar.gz"
fi

python - <<'PY'
import tarfile, os
os.makedirs("android", exist_ok=True)
with tarfile.open("android.tar.bz2") as t:
    t.extractall("android")
os.makedirs("src", exist_ok=True)
with tarfile.open("src.tar.gz") as t:
    keep = [m for m in t.getmembers()
            if "/kotlin-api/" in m.name or "/com/k2fsa/sherpa/onnx/" in m.name]
    t.extractall("src", members=keep)
print("extracted", len(keep), "source members")
PY

echo "=== shared libs ==="
find android -name '*.so' | head -20
echo "=== kotlin api ==="
find src -name '*.kt' | head -40
echo "count: $(find src -name '*.kt' | wc -l)"
