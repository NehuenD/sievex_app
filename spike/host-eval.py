#!/usr/bin/env python3
"""Host-side ASR evaluation: clean vs long vs noisy, with WER and real-time factor.

Generates test audio from the one public sample we already have, then measures
Parakeet TDT v3 through the official NeMo-Speech.cpp build.
"""
import array
import json
import math
import random
import re
import subprocess
import time
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BIN = ROOT / "nemo-win" / "nemo-speech-0.3.0-windows-x86_64-cpu" / "bin" / "nemo-speech.exe"
AUDIO = ROOT / "audio"
OUT = ROOT / "out"
AUDIO.mkdir(exist_ok=True)
OUT.mkdir(exist_ok=True)

REF = ("Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. "
       "It is certainly very like the old portrait.")


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        ch, sw, sr = w.getnchannels(), w.getsampwidth(), w.getframerate()
        raw = w.readframes(w.getnframes())
    a = array.array("h")
    a.frombytes(raw)
    return sr, ch, sw, a


def write_wav(path, sr, samples):
    a = array.array("h", [max(-32768, min(32767, int(round(s)))) for s in samples])
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(a.tobytes())


def speech_rms(samples, sr):
    n = max(1, int(0.02 * sr))
    levels = []
    for i in range(0, len(samples) - n, n):
        chunk = samples[i:i + n]
        levels.append(math.sqrt(sum(x * x for x in chunk) / n))
    if not levels:
        return 1.0
    peak = max(levels)
    active = [v for v in levels if v > 0.1 * peak]
    return (sum(active) / len(active)) if active else peak


def add_noise(samples, sr, snr_db, seed=7):
    rnd = random.Random(seed)
    noise_rms = speech_rms(samples, sr) / (10 ** (snr_db / 20.0))
    return [x + rnd.gauss(0.0, noise_rms) for x in samples]


def tokens(s):
    return re.findall(r"[a-z0-9']+", s.lower())


def edit_distance(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i in range(1, len(ref) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(hyp) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
            prev = cur
    return d[-1]


def transcribe(name, path, ref):
    out_json = OUT / f"{name}.json"
    t0 = time.perf_counter()
    proc = subprocess.run(
        [str(BIN), "transcribe", str(path), "-m", "parakeet-tdt",
         "-f", "json", "-o", str(out_json), "--device", "cpu"],
        capture_output=True, text=True,
    )
    dt = time.perf_counter() - t0
    if proc.returncode != 0:
        return {"name": name, "error": (proc.stderr or proc.stdout)[-400:]}
    data = json.loads(out_json.read_text(encoding="utf-8"))
    text = data.get("text", "")
    dur = data.get("duration") or 0.0
    ref_t, hyp_t = tokens(ref), tokens(text)
    errs = edit_distance(ref_t, hyp_t)
    return {
        "name": name,
        "dur": dur,
        "elapsed": dt,
        "rtf": (dur / dt) if dt else 0.0,
        "wer": (errs / len(ref_t)) if ref_t else 0.0,
        "langs": data.get("languages"),
        "text": text,
    }


def main():
    sr, ch, sw, base = read_wav(AUDIO / "sample.wav")
    print(f"sample: {len(base)/sr:.2f}s sr={sr} ch={ch} width={sw}")

    tiled = array.array("h", list(base) * 8)
    cases = []

    p = AUDIO / "eval_short.wav"
    write_wav(p, sr, base)
    cases.append(("short_clean_8s", p, REF))

    p = AUDIO / "eval_long.wav"
    write_wav(p, sr, tiled)
    cases.append(("long_clean_60s", p, REF * 8))

    for snr in (10, 5):
        p = AUDIO / f"eval_noisy_{snr}db.wav"
        write_wav(p, sr, add_noise(tiled, sr, snr))
        cases.append((f"long_noisy_{snr}db", p, REF * 8))

    rows = []
    for name, path, ref in cases:
        print(f"running {name} ...", flush=True)
        rows.append(transcribe(name, path, ref))

    print()
    print(f"{'case':<18}{'audio_s':>9}{'wall_s':>9}{'xRT':>7}{'WER':>8}  languages")
    for r in rows:
        if "error" in r:
            print(f"{r['name']:<18}ERROR {r['error']}")
            continue
        print(f"{r['name']:<18}{r['dur']:>9.2f}{r['elapsed']:>9.2f}"
              f"{r['rtf']:>7.1f}{r['wer']*100:>7.1f}%  {r['langs']}")

    print()
    for r in rows:
        if "error" not in r:
            print(f"--- {r['name']} ---")
            print(r["text"][:600])
            print()


if __name__ == "__main__":
    main()
