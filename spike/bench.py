#!/usr/bin/env python3
"""Summarise the rep logs written by spike/bench.sh.

The app logs two tags. "Sievex" is the Kotlin side (CaptureLog -> Log.i), "SievexLLM" is the
native llama.cpp shim. The shapes this parser understands, exactly as the app writes them:

    asr: 58.4s audio -> 9123 ms (rtf 0.16)
    asr: model loaded in 812 ms
    pp 431 tok (kept 202/431) 5120 ms 84.2 tok/s | tg 118 tok 9020 ms 13.1 tok/s | reused 1 realloc 0/0
    llm: 412 chars in 14140 ms
    model loaded in 1340 ms
    recovered orphan burst-20261114-192233.wav      (or)  queued burst-20261114-192233.wav
    transcribing burst-20261114-192233.wav
    note filed

logcat prefixes every line with a timestamp, either "MM-DD HH:MM:SS.mmm" (threadtime) or
"HH:MM:SS.mmm". Both are accepted. Only differences matter, so the missing year is irrelevant,
and a midnight rollover is compensated per log.

End-to-end latency pairs the k-th burst to reach the queue with the k-th "note filed". The queue
is a strict FIFO drained by a single worker thread, so that ordering is the only pairing the app
logs support: "note filed" carries no burst name.

Standard library only; runs on Windows with `python` or `py -3`.
"""

import argparse
import glob
import os
import re
import sys

DAY = 86400.0
APP_TAGS = ("Sievex", "SievexLLM")

# --- line structure --------------------------------------------------------

# "11-14 19:20:39.123  1234  1250 I Sievex  : asr: ..."  or  "19:20:39.123  asr: ..."
_TIME = re.compile(
    r"^(?:(?P<mon>\d{2})-(?P<day>\d{2})\s+)?"
    r"(?P<h>\d{2}):(?P<mi>\d{2}):(?P<s>\d{2})\.(?P<ms>\d{3})"
)
# threadtime / time logger: "<pid> <tid> I Tag  : body"
_HEAD = re.compile(
    r"^\s*(?:\d+\s+){0,2}(?P<level>[VDIWEF])\s+(?P<tag>[A-Za-z0-9_.\-]+)\s*:"
)
# brief logger: "I/Tag( 1234): body"
_BRIEF = re.compile(r"^(?P<level>[VDIWEF])/(?P<tag>[A-Za-z0-9_.\-]+)\s*\(\s*\d+\s*\)\s*:")

# --- app messages ----------------------------------------------------------

_ASR = re.compile(
    r"\basr:\s*(?P<audio>\d+(?:\.\d+)?)s audio -> (?P<ms>\d+) ms "
    r"\(rtf (?P<rtf>\d+(?:\.\d+)?)\)"
)
_ASR_LOAD = re.compile(r"\basr: model loaded in (?P<ms>\d+) ms")
_LLM = re.compile(
    r"\bpp (?P<pp>\d+) tok \(kept (?P<keep>\d+)/(?P<prompt>\d+)\) "
    r"(?P<pms>[\d.]+) ms (?P<pps>[\d.]+) tok/s \| "
    r"tg (?P<tg>\d+) tok (?P<tms>[\d.]+) ms (?P<tps>[\d.]+) tok/s"
    r"(?: \| reused (?P<reused>\d+) realloc (?P<ra>\d+)/(?P<rb>\d+))?"
)
_LLM_KOTLIN = re.compile(r"\bllm: (?P<chars>\d+) chars in (?P<ms>\d+) ms\b")
_MODEL_LOAD = re.compile(r"\bmodel loaded in (?P<ms>\d+) ms\b")
_TRANSCRIBE = re.compile(r"\btranscribing (?P<name>\S+\.wav)\b")
_NOTE_FILED = re.compile(r"\bnote filed\b")
# "queued X.wav" (live recording) and "recovered orphan X.wav" (startup recovery) are the two
# ways a burst reaches the queue. "already queued X.wav" is a duplicate notice, not an enqueue.
_ENQUEUE = re.compile(r"\brecovered orphan (?P<name>\S+\.wav)\b")
_ENQUEUE2 = re.compile(r"(?<!already )\bqueued (?P<name>\S+\.wav)\b")

# Non-leap cumulative day-of-year table; the year never enters a difference.
_MONTH_START = (0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)


def split_line(line):
    """Return (timestamp_in_seconds | None, tag | None, message_body)."""
    match = _TIME.match(line)
    timestamp = None
    rest = line
    if match:
        rest = line[match.end():]
        if match.group("mon"):
            month = int(match.group("mon"))
            if not 1 <= month <= 12:
                return None, None, line.strip()
            day_index = _MONTH_START[month - 1] + int(match.group("day")) - 1
        else:
            day_index = 0
        timestamp = (
            day_index * DAY
            + int(match.group("h")) * 3600
            + int(match.group("mi")) * 60
            + int(match.group("s"))
            + int(match.group("ms")) / 1000.0
        )

    brief = _BRIEF.match(rest.lstrip())
    if brief:
        return timestamp, brief.group("tag"), rest.lstrip()[brief.end():].strip()
    head = _HEAD.match(rest)
    if head:
        return timestamp, head.group("tag"), rest[head.end():].strip()
    return timestamp, None, rest.strip()


def make_monotone(timestamp, previous):
    """Step a timestamp forward until it no longer regresses.

    Logcat lines carry no year, and a benchmark can run across midnight or across New Year. The
    calendar gap differs per case (one day, or a whole year when 12-31 turns into 01-01), so
    rather than guessing which one happened, advance a day at a time until the line is
    monotone. That is at most a few hundred cheap steps for a year boundary.
    """
    if previous is None:
        return timestamp
    steps = 0
    while timestamp < previous and steps < 400:
        timestamp += DAY
        steps += 1
    return timestamp


def parse_rep(path):
    """Parse one rep log into a dict of observations."""
    rep = {
        "path": path,
        "asr": [],
        "asr_loads": [],
        "llm": [],
        "llm_kotlin": [],
        "model_loads": [],
        "enqueued": [],
        "notes": [],
        "transcribed": [],
    }

    previous = None

    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if not line.strip():
                continue

            timestamp, tag, body = split_line(line)

            if timestamp is not None:
                timestamp = make_monotone(timestamp, previous)
                previous = timestamp

            # A log filtered to the app's tags never hits this; an unfiltered dump does.
            if tag is not None and tag not in APP_TAGS:
                continue

            # Order matters: "asr: model loaded in ..." also matches the plain model-load shape,
            # so the ASR-specific patterns are consumed first.
            match = _ASR.search(body)
            if match:
                audio = float(match.group("audio"))
                wall_ms = int(match.group("ms"))
                rep["asr"].append(
                    {
                        "audio": audio,
                        "wall_ms": wall_ms,
                        "rtf": (wall_ms / 1000.0 / audio) if audio > 0 else None,
                        "rtf_logged": float(match.group("rtf")),
                    }
                )
                continue

            match = _ASR_LOAD.search(body)
            if match:
                rep["asr_loads"].append(int(match.group("ms")))
                continue

            match = _LLM.search(body)
            if match:
                rep["llm"].append(
                    {
                        "pp": int(match.group("pp")),
                        "keep": int(match.group("keep")),
                        "prompt": int(match.group("prompt")),
                        "prefill_ms": float(match.group("pms")),
                        "pp_tps": float(match.group("pps")),
                        "tg": int(match.group("tg")),
                        "decode_ms": float(match.group("tms")),
                        "tg_tps": float(match.group("tps")),
                        "reused": match.group("reused"),
                        "realloc": (
                            "%s/%s" % (match.group("ra"), match.group("rb"))
                            if match.group("ra") is not None
                            else None
                        ),
                    }
                )
                continue

            match = _LLM_KOTLIN.search(body)
            if match:
                rep["llm_kotlin"].append(
                    {"chars": int(match.group("chars")), "ms": int(match.group("ms"))}
                )
                continue

            match = _MODEL_LOAD.search(body)
            if match:
                rep["model_loads"].append(int(match.group("ms")))
                continue

            match = _TRANSCRIBE.search(body)
            if match:
                rep["transcribed"].append(match.group("name"))
                continue

            if _NOTE_FILED.search(body):
                if timestamp is not None:
                    rep["notes"].append(timestamp)
                continue

            match = _ENQUEUE.search(body) or _ENQUEUE2.search(body)
            if match:
                if timestamp is not None:
                    rep["enqueued"].append((match.group("name"), timestamp))
                continue

    # A WAV can be enqueued more than once across a run (recovery plus a direct queue add); the
    # first sighting is when the burst actually became work.
    seen = set()
    unique = []
    for name, timestamp in rep["enqueued"]:
        if name in seen:
            continue
        seen.add(name)
        unique.append((name, timestamp))
    rep["enqueued"] = unique

    # FIFO order: the k-th enqueue is the k-th filed note.
    rep["latency"] = []
    for index, (name, timestamp) in enumerate(rep["enqueued"]):
        if index < len(rep["notes"]):
            delta = (rep["notes"][index] - timestamp) * 1000.0
            # After the monotone repair a negative delta can only come from a hand-edited log.
            if delta >= 0:
                rep["latency"].append((name, delta))

    return rep


# --- statistics and formatting --------------------------------------------


def median(values):
    values = [value for value in values if value is not None]
    if not values:
        return None
    ordered = sorted(values)
    count = len(ordered)
    if count % 2:
        return ordered[count // 2]
    return (ordered[count // 2 - 1] + ordered[count // 2]) / 2.0


def num(value, digits=1):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def medians(rep):
    return {
        "asr_s": median([row["audio"] for row in rep["asr"]]),
        "asr_ms": median([row["wall_ms"] for row in rep["asr"]]),
        "rtf": median([row["rtf"] for row in rep["asr"]]),
        "prefill_ms": median([row["prefill_ms"] for row in rep["llm"]]),
        "pp_tps": median([row["pp_tps"] for row in rep["llm"]]),
        "decode_ms": median([row["decode_ms"] for row in rep["llm"]]),
        "tg_tps": median([row["tg_tps"] for row in rep["llm"]]),
        "tg_tok": median([row["tg"] for row in rep["llm"]]),
        "e2e_ms": median([value for _, value in rep["latency"]]),
        "asr_load_ms": median(rep["asr_loads"]),
        "llm_load_ms": median(rep["model_loads"]),
    }


def pooled_medians(reps):
    merged = {"asr": [], "llm": [], "latency": [], "asr_loads": [], "model_loads": []}
    for rep in reps:
        merged["asr"].extend(rep["asr"])
        merged["llm"].extend(rep["llm"])
        merged["latency"].extend(rep["latency"])
        merged["asr_loads"].extend(rep["asr_loads"])
        merged["model_loads"].extend(rep["model_loads"])
    return medians(
        {
            "asr": merged["asr"],
            "llm": merged["llm"],
            "latency": merged["latency"],
            "asr_loads": merged["asr_loads"],
            "model_loads": merged["model_loads"],
        }
    )


def print_table(headers, rows, align, indent="    "):
    """Fixed-width table: align is a string of 'l' and 'r', one per column."""
    widths = [len(str(headers[i])) for i in range(len(headers))]
    for row in rows:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(str(cell)))
    for index, row in enumerate([headers] + rows):
        cells = []
        for i, cell in enumerate(row):
            text = str(cell)
            cells.append(text.rjust(widths[i]) if align[i] == "r" else text.ljust(widths[i]))
        print(indent + "  ".join(cells).rstrip())


def rep_meta(rep, wavs):
    """Counters plus a list of warnings for one rep."""
    enqueued = len(rep["enqueued"])
    notes = len(rep["notes"])
    warnings = []
    if wavs is not None and enqueued != wavs:
        warnings.append(
            f"{wavs} wav(s) pushed but {enqueued} burst(s) enqueued "
            f"(difference {enqueued - wavs:+d})"
        )
    if notes != enqueued:
        warnings.append(
            f"{enqueued} burst(s) enqueued but {notes} note(s) filed "
            f"(difference {notes - enqueued:+d})"
        )
    if wavs is not None and notes != wavs and enqueued == wavs:
        warnings.append(f"{wavs} wav(s) pushed but {notes} note(s) filed")
    return enqueued, notes, warnings


def describe_loads(label, values):
    """One line per rep: a single load, or the median when a model was reloaded mid-rep."""
    if len(values) == 1:
        return f"{label} {int(values[0])} ms"
    return f"{label} median {int(median(values))} ms ({len(values)} loads)"


def report_rep(rep, label, wavs):
    stats = medians(rep)
    enqueued, notes, warnings = rep_meta(rep, wavs)

    print("=" * 78)
    print(f"rep {label}  {rep['path']}")
    print(
        f"  wavs pushed {wavs if wavs is not None else '?'} | "
        f"bursts enqueued {enqueued} | notes filed {notes}"
    )

    loads = []
    if rep["asr_loads"]:
        loads.append(describe_loads("asr", rep["asr_loads"]))
    if rep["model_loads"]:
        loads.append(describe_loads("llm", rep["model_loads"]))
    if loads:
        print("  model load: " + " | ".join(loads))

    if rep["asr"]:
        print("  asr")
        rows = []
        for index, row in enumerate(rep["asr"], 1):
            rows.append(
                [
                    index,
                    num(row["audio"], 1),
                    int(row["wall_ms"]),
                    num(row["rtf"], 2),
                    num(row["rtf_logged"], 2),
                ]
            )
        print_table(
            ["#", "audio_s", "wall_ms", "rtf", "rtf_logged"], rows, "rrrrr"
        )
    else:
        print("  asr: no 'asr: <n>s audio -> <n> ms (rtf x)' line found")

    if rep["llm"]:
        print("  llm native")
        rows = []
        for index, row in enumerate(rep["llm"], 1):
            rows.append(
                [
                    index,
                    row["pp"],
                    f"{row['keep']}/{row['prompt']}",
                    int(row["prefill_ms"]),
                    num(row["pp_tps"], 1),
                    row["tg"],
                    int(row["decode_ms"]),
                    num(row["tg_tps"], 1),
                    row["reused"] if row["reused"] is not None else "-",
                    row["realloc"] if row["realloc"] is not None else "-",
                ]
            )
        print_table(
            [
                "#",
                "pp_tok",
                "kept",
                "prefill_ms",
                "pp_tok/s",
                "tg_tok",
                "decode_ms",
                "tg_tok/s",
                "reused",
                "realloc",
            ],
            rows,
            "rrrrrrrrrr",
        )
    else:
        print("  llm native: no 'pp ... tok ... | tg ... tok ...' line found")

    if rep["llm_kotlin"]:
        print("  llm kotlin")
        rows = [
            [index, row["chars"], row["ms"]]
            for index, row in enumerate(rep["llm_kotlin"], 1)
        ]
        print_table(["#", "chars", "total_ms"], rows, "rrr")

    if rep["latency"]:
        # The pipeline is serial, so a burst that waited behind two others shows a larger number:
        # this is queue wait + ASR + LLM, not this burst's own compute.
        print("  end to end (enqueued -> 'note filed', includes queue wait)")
        rows = [[name, int(round(value))] for name, value in rep["latency"]]
        print_table(["burst", "ms"], rows, "lr")
    elif enqueued:
        print("  end to end: no note was filed for any queued burst")

    print(
        "  median: "
        f"rtf {num(stats['rtf'], 2)}  asr {num(stats['asr_ms'], 0)} ms  "
        f"pp {num(stats['pp_tps'], 1)} tok/s  tg {num(stats['tg_tps'], 1)} tok/s  "
        f"e2e {num(stats['e2e_ms'], 0)} ms"
    )

    for warning in warnings:
        print(f"  WARNING: {warning}")

    return stats, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Summarise spike/out/bench-rep-N.log files from spike/bench.sh.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  py -3 spike/bench.py --wavs 3 --dir spike/out\n"
            "  py -3 spike/bench.py --wavs 3 spike/out/bench-rep-1.log\n"
        ),
    )
    parser.add_argument("logs", nargs="*", help="log files (default: --dir)")
    parser.add_argument(
        "--dir", dest="log_dir", help="directory to scan for bench-rep-*.log"
    )
    parser.add_argument(
        "--wavs", type=int, default=None, help="number of WAVs pushed per rep"
    )
    parser.add_argument(
        "--reps",
        type=int,
        default=None,
        help="only use reps 1..N (keeps logs from a longer earlier run out of the summary)",
    )
    args = parser.parse_args(argv)

    paths = [os.path.normpath(path) for path in args.logs]
    if args.log_dir:
        paths.extend(
            os.path.normpath(path)
            for path in sorted(glob.glob(os.path.join(args.log_dir, "bench-rep-*.log")))
        )

    if not paths:
        parser.error("no logs given: pass files or --dir <spike/out>")

    missing = [path for path in paths if not os.path.isfile(path)]
    if missing:
        for path in missing:
            print(f"error: no such log file: {path}", file=sys.stderr)
        return 2

    def rep_number(path):
        match = re.search(r"bench-rep-(\d+)\.log$", os.path.basename(path))
        return (0, int(match.group(1))) if match else (1, 0)

    if args.reps is not None:
        if args.reps < 1:
            parser.error("--reps must be >= 1")
        wanted = set(range(1, args.reps + 1))
        paths = [path for path in paths if rep_number(path)[0] == 0 and rep_number(path)[1] in wanted]
        found = {rep_number(path)[1] for path in paths}
        for rep in sorted(wanted - found):
            print(f"warning: no log for rep {rep}", file=sys.stderr)
        if not paths:
            parser.error("no logs left after --reps filtering")

    paths.sort(key=rep_number)

    reps = []
    for path in paths:
        rep = parse_rep(path)
        reps.append(rep)

    print(f"parsed {len(reps)} rep log(s)"
          + (f", expecting {args.wavs} wav(s) per rep" if args.wavs is not None else ""))

    all_warnings = []
    per_rep = []
    for index, rep in enumerate(reps, 1):
        label = rep_number(rep["path"])[1] or index
        stats, warnings = report_rep(rep, label, args.wavs)
        per_rep.append((label, rep, stats))
        all_warnings.extend((label, warning) for warning in warnings)

    # --- aggregate table ---
    print("=" * 78)
    print("summary: medians per rep; ALL pools every sample (it is not a median of medians)")
    rows = []
    for label, rep, stats in per_rep:
        enqueued, notes, _ = rep_meta(rep, args.wavs)
        rows.append(
            [
                label,
                args.wavs if args.wavs is not None else "-",
                enqueued,
                notes,
                num(stats["asr_s"], 1),
                num(stats["asr_ms"], 0),
                num(stats["rtf"], 2),
                num(stats["prefill_ms"], 0),
                num(stats["pp_tps"], 1),
                num(stats["decode_ms"], 0),
                num(stats["tg_tps"], 1),
                num(stats["tg_tok"], 0),
                num(stats["e2e_ms"], 0),
            ]
        )
    overall = pooled_medians([rep for _, rep, _ in per_rep])
    rows.append(
        [
            "ALL",
            "-",
            sum(len(rep["enqueued"]) for _, rep, _ in per_rep),
            sum(len(rep["notes"]) for _, rep, _ in per_rep),
            num(overall["asr_s"], 1),
            num(overall["asr_ms"], 0),
            num(overall["rtf"], 2),
            num(overall["prefill_ms"], 0),
            num(overall["pp_tps"], 1),
            num(overall["decode_ms"], 0),
            num(overall["tg_tps"], 1),
            num(overall["tg_tok"], 0),
            num(overall["e2e_ms"], 0),
        ]
    )
    print_table(
        [
            "rep",
            "wavs",
            "enq",
            "notes",
            "asr_s",
            "asr_ms",
            "rtf",
            "prefill_ms",
            "pp_tok/s",
            "decode_ms",
            "tg_tok/s",
            "tg_tok",
            "e2e_ms",
        ],
        rows,
        "rrrrrrrrrrrrr",
        indent="  ",
    )

    if all_warnings:
        print("")
        print("warnings (counts disagree - do not compare these reps as if they were equal)")
        for label, warning in all_warnings:
            print(f"  rep {label}: {warning}")
    else:
        print("")
        print("all reps parsed cleanly: wav, burst and note counts agree")

    return 0


if __name__ == "__main__":
    sys.exit(main())
