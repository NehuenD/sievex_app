#!/usr/bin/env bash
# On-device pipeline benchmark. Drives the installed APK through adb only: no gradle run, no
# source changes, nothing to rebuild.
#
#   spike/bench.sh <dir-with-16k-mono-wavs> [reps]        # reps defaults to 3
#
# Per rep:
#   1. force-stop the app (see below),
#   2. push the WAVs into the app's external bursts/ directory,
#   3. clear logcat and start MainActivity once,
#   4. poll logcat until the queue has drained (10 min cap),
#   5. save the app's log lines to spike/out/bench-rep-N.log,
#   6. delete exactly the files this script pushed, never the directory.
# Afterwards spike/bench.py turns those logs into a table with medians.
#
# WHY force-stop IS MANDATORY:
#   CaptureService.kick() answers an empty queue from memory once the process has loaded
#   jobs.json ("if (queueReady && JobQueue.peek() == null) return"). A live process therefore
#   swallows the activity's onStart kick whenever it believes nothing is queued, and the pushed
#   WAVs would sit there forever. force-stop kills the process, so queueReady is false again and
#   the kick always starts the service.
#
# WHY the WAVs are pushed BEFORE the start:
#   Orphan recovery runs on service startup and queues every *.wav in bursts/ that jobs.json and
#   notes.json do not already know about. That is the only injection point available without
#   touching app code, and it is why the benchmark is a faithful end-to-end run (ASR, LLM,
#   filing) with no test hooks in the app.
#
# Assumptions that would break on a real device are listed in the report this script prints; the
# main ones are the /sdcard/Android/data path (primary user, non-work-profile) and shell access
# to that directory on Android 11+.
set -euo pipefail

PKG=com.sievex.app
ACTIVITY=com.sievex.app/.MainActivity
# getExternalFilesDir(null)/bursts for the primary user. A work profile or secondary user gets a
# different /storage/emulated/<user>/... root, so this constant would have to change there.
REMOTE_DIR=/sdcard/Android/data/com.sievex.app/files/bursts

HERE=$(cd "$(dirname "$0")" && pwd)
OUT_DIR="$HERE/out"

TIMEOUT_S=${TIMEOUT_S:-600}
POLL_S=${POLL_S:-10}

PUSHED=""
SERIAL="${SERIAL:-}"

usage() {
  cat <<'EOF'
usage: spike/bench.sh <dir-with-16k-mono-wavs> [reps]

  dir-with-16k-mono-wavs  directory of *.wav bursts to replay (one note per file)
  reps                    repetitions (default 3)

env:
  SERIAL     device serial; required only when more than one device is attached
  ADB        adb binary (default: adb on PATH, else the Android SDK copy)
  TIMEOUT_S  per-rep wait for the queue to drain (default 600)
  POLL_S     logcat poll interval (default 10)
EOF
}

die() {
  printf 'bench: %s\n' "$*" >&2
  exit 1
}

# --- arguments -------------------------------------------------------------

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  usage >&2
  exit 2
fi

WAV_DIR=$1
REPS=${2:-3}

case "$REPS" in
  ''|*[!0-9]*) die "reps must be a positive integer, got '$REPS'" ;;
esac
[ "$REPS" -ge 1 ] || die "reps must be >= 1"
[ -d "$WAV_DIR" ] || die "not a directory: $WAV_DIR"

mkdir -p "$OUT_DIR"

# --- collect the input WAVs ------------------------------------------------

# Checked before adb is touched: a typo in the input directory should not need a device to
# report itself. Newline-separated so a path with spaces stays one entry; the two globs can name
# the same file on a case-insensitive filesystem, hence the exact-match dedupe.
LIST=""
for candidate in "$WAV_DIR"/*.wav "$WAV_DIR"/*.WAV; do
  [ -f "$candidate" ] || continue
  if printf '%s' "$LIST" | grep -Fxq -- "$candidate"; then
    continue
  fi
  LIST="$LIST$candidate
"
done

WAV_COUNT=$(printf '%s' "$LIST" | sed '/^$/d' | wc -l | tr -d ' ')
[ "$WAV_COUNT" -ge 1 ] || die "no .wav files in $WAV_DIR"

# --- adb and device --------------------------------------------------------

resolve_adb() {
  if [ -n "${ADB:-}" ]; then
    printf '%s\n' "$ADB"
    return 0
  fi
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  # The SDK install the other spike/ scripts use.
  for candidate in \
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

ADB_BIN=$(resolve_adb) || die "adb not found: put it on PATH or set ADB=/path/to/adb"

# `adb devices` exits non-zero when the client or the server cannot run at all ("no
# devices/emulators found" is reported through it too), which would otherwise abort the script
# under set -e before it can say anything useful. Keep adb's own words for the error path.
if ! adb_out=$("$ADB_BIN" devices 2>&1); then
  printf 'bench: "adb devices" failed:\n' >&2
  printf '%s\n' "$adb_out" | tr -d '\r' | sed 's/^/  /' >&2
  printf 'bench: check the USB cable, the debugging prompt, and that the adb server can start.\n' >&2
  exit 1
fi

# `adb devices` output is CRLF; the header line is dropped.
device_lines=$(printf '%s\n' "$adb_out" | tr -d '\r' | sed '1d')
ready=$(printf '%s\n' "$device_lines" | awk '$2 == "device" { print $1 }' | sed '/^$/d')
# grep -c, not wc -l: printf of an empty string still emits one newline, so wc -l would report 1
# device where there are none.
ready_count=$(printf '%s\n' "$ready" | grep -c '[^[:space:]]' || true)
ready_count=${ready_count:-0}

if [ "$ready_count" -eq 0 ]; then
  printf 'bench: no device ready (adb devices must show one line ending in "device").\n' >&2
  printf '%s\n' "$device_lines" | sed '/^$/d; s/^/  /' >&2
  printf 'bench: "unauthorized" means accept the USB debugging prompt on the phone;\n' >&2
  printf 'bench: "offline" means wait a moment and retry.\n' >&2
  exit 1
fi

if [ -n "$SERIAL" ]; then
  printf '%s\n' "$ready" | grep -Fxq -- "$SERIAL" \
    || die "SERIAL=$SERIAL is not attached and ready (ready: $(printf '%s' "$ready" | tr '\n' ' '))"
else
  if [ "$ready_count" -ne 1 ]; then
    printf 'bench: expected exactly one device, found %s:\n' "$ready_count" >&2
    printf '%s\n' "$ready" | sed 's/^/  /' >&2
    die "set SERIAL=<serial> to pick one"
  fi
  SERIAL=$(printf '%s\n' "$ready" | head -n 1)
fi

adbd() { "$ADB_BIN" -s "$SERIAL" "$@"; }

DEVICE_MODEL=$(adbd shell getprop ro.product.model | tr -d '\r')
DEVICE_ANDROID=$(adbd shell getprop ro.build.version.release | tr -d '\r')

# --- helpers ---------------------------------------------------------------

# Device-side file names are sanitised: adb joins its arguments into one shell command, so a
# source file called `it's a test.wav` would otherwise break the remote command line. Keeping
# only [A-Za-z0-9._-] makes every push and every rm unambiguous.
sanitize() {
  printf '%s' "$1" | sed 's/[^A-Za-z0-9._-]/_/g'
}

# Counts *.wav in the bursts dir. `ls` writes to stderr and fails when the directory does not
# exist yet, and grep -c exits 1 on a zero count; both are normal here, so this is best-effort
# and the caller normalises an empty answer to 0.
remote_wav_count() {
  adbd shell ls -1 "$REMOTE_DIR" 2>/dev/null | tr -d '\r' | grep -Eic '\.wav$' || true
}

# Deletes the exact files this script pushed. Never a wildcard, never the directory itself.
cleanup_pushed() {
  [ -n "${PUSHED:-}" ] || return 0
  if [ -n "${SERIAL:-}" ] && [ -n "${ADB_BIN:-}" ]; then
    for name in $PUSHED; do
      adbd shell rm -f "$REMOTE_DIR/$name" >/dev/null 2>&1 || true
    done
  fi
  PUSHED=""
}

# The per-rep scratch files have deterministic names, so they are removed by name (a wildcard
# would be the one thing this script must never do) - including after an interrupted run.
cleanup_temp() {
  tmp_rep=1
  while [ "$tmp_rep" -le "$REPS" ]; do
    rm -f "$OUT_DIR/.bench-rep-$tmp_rep.poll" "$OUT_DIR/.bench-rep-$tmp_rep.start"
    tmp_rep=$((tmp_rep + 1))
  done
}

trap 'cleanup_pushed; cleanup_temp' EXIT
trap 'cleanup_pushed; cleanup_temp; exit 130' INT
trap 'cleanup_pushed; cleanup_temp; exit 143' TERM

# --- header ----------------------------------------------------------------

printf '== sievex bench ==\n'
printf '   device : %s (Android %s)\n' "${DEVICE_MODEL:-unknown}" "${DEVICE_ANDROID:-unknown}"
printf '   serial : %s\n' "$SERIAL"
printf '   wavs   : %s from %s\n' "$WAV_COUNT" "$WAV_DIR"
printf '   reps   : %s, %ss per rep, %ss poll\n' "$REPS" "$TIMEOUT_S" "$POLL_S"

# --- reps ------------------------------------------------------------------

rep=1
while [ "$rep" -le "$REPS" ]; do
  log="$OUT_DIR/bench-rep-$rep.log"
  poll_file="$OUT_DIR/.bench-rep-$rep.poll"
  start_file="$OUT_DIR/.bench-rep-$rep.start"

  printf '\n== rep %s/%s ==\n' "$rep" "$REPS"

  # Mandatory: see the header. Without it MainActivity's kick can be swallowed for a queue this
  # process already decided was empty.
  adbd shell am force-stop "$PKG"

  # Idempotent, creates nothing but the path; nothing inside is ever deleted here.
  adbd shell mkdir -p "$REMOTE_DIR"

  existing=$(remote_wav_count)
  existing=${existing:-0}
  if [ "$existing" -ne 0 ]; then
    printf 'bench: %s already holds %s .wav file(s):\n' "$REMOTE_DIR" "$existing" >&2
    adbd shell ls -1 "$REMOTE_DIR" 2>/dev/null | tr -d '\r' | sed '/^$/d; s/^/  /' >&2 || true
    die "clear that directory by hand first (this script never wildcard-deletes someone else's audio)"
  fi

  PUSHED=""
  idx=0
  while IFS= read -r src; do
    [ -n "$src" ] || continue
    idx=$((idx + 1))
    # The bench- prefix cannot collide with a real recording (burst-<stamp>.wav), and the index
    # keeps two same-named inputs apart.
    remote="bench-r$rep-$idx-$(sanitize "$(basename "$src")")"
    if ! adbd push "$src" "$REMOTE_DIR/$remote" >/dev/null; then
      printf 'bench: adb push failed for %s\n' "$src" >&2
      die "check that adb can write $REMOTE_DIR (Android 11+ scoped storage, work profile, or a full disk)"
    fi
    PUSHED="$PUSHED $remote"
  done <<EOF
$LIST
EOF

  printf '   pushed %s/%s wav(s)\n' "$idx" "$WAV_COUNT"

  adbd logcat -c

  # One start is enough: MainActivity.onStart kicks the queue, the service recovers the WAVs as
  # orphans and drains them. -W waits for the activity to be launched.
  printf '   starting %s\n' "$ACTIVITY"
  if ! adbd shell am start -W -n "$ACTIVITY" >"$start_file" 2>&1; then
    sed 's/^/  /' "$start_file" >&2
    die "am start failed"
  fi
  if grep -qi 'error' "$start_file"; then
    sed 's/^/  /' "$start_file" >&2
    die "am start reported an error (is $PKG installed for this user?)"
  fi

  started=$(date +%s)
  status=timeout
  last_lines=-1
  stale=0
  notes=0

  while :; do
    # Only the app's two tags: an unfiltered buffer on a real phone is tens of MB of noise, and
    # bench.py reads exactly these tags.
    adbd logcat -d -v threadtime -s Sievex SievexLLM >"$poll_file" 2>/dev/null || true
    lines=$(wc -l <"$poll_file" | tr -d ' ')
    notes=$(grep -c 'note filed' "$poll_file" || true)
    notes=${notes:-0}
    elapsed=$(( $(date +%s) - started ))

    if [ "$lines" -eq "$last_lines" ]; then
      stale=$((stale + 1))
    else
      stale=0
    fi
    last_lines=$lines

    drained=0
    held=0
    nothing=0
    if grep -q 'jobs: queue empty, foreground released' "$poll_file"; then drained=1; fi
    if grep -q 'jobs: queue held, waiting for a model' "$poll_file"; then held=1; fi
    if grep -q 'kick: nothing queued' "$poll_file"; then nothing=1; fi

    printf '   %4ss  lines=%-5s notes=%s/%s\n' "$elapsed" "$lines" "$notes" "$WAV_COUNT"

    # Drained is the clean finish: the service logged that the queue ran dry, and every WAV
    # produced a note.
    if [ "$drained" -eq 1 ] && [ "$notes" -ge "$WAV_COUNT" ]; then
      status=drained
      break
    fi
    # A held queue waits for a model that will not appear during a benchmark.
    if [ "$held" -eq 1 ]; then
      status=held
      break
    fi
    # The service found nothing to do: the pushed files never reached the queue.
    if [ "$nothing" -eq 1 ] && [ "$notes" -eq 0 ] && [ "$elapsed" -ge $((POLL_S * 2)) ]; then
      status=empty
      break
    fi
    if [ "$elapsed" -ge "$TIMEOUT_S" ]; then
      status=timeout
      break
    fi
    # Fallback: all notes are filed and the log has been silent for a while, but the drain
    # message never showed up (for example the service was already in the foreground).
    if [ "$notes" -ge "$WAV_COUNT" ] && [ "$stale" -ge 2 ] && [ "$elapsed" -ge 60 ]; then
      status=quiet
      break
    fi

    sleep "$POLL_S"
  done

  # Final, complete dump for bench.py.
  adbd logcat -d -v threadtime -s Sievex SievexLLM >"$log" 2>/dev/null || true
  log_lines=$(wc -l <"$log" | tr -d ' ')

  case "$status" in
    drained) printf '   rep %s done: queue drained after %ss\n' "$rep" "$elapsed" ;;
    quiet)   printf '   rep %s done: %s note(s), log quiet after %ss (no drain line seen)\n' "$rep" "$notes" "$elapsed" ;;
    held)    printf 'bench: rep %s stopped: the queue is waiting for a model\n' "$rep" >&2 ;;
    empty)   printf 'bench: rep %s stopped: the service found nothing queued\n' "$rep" >&2 ;;
    timeout) printf 'bench: rep %s timed out after %ss (raise TIMEOUT_S if the phone is slow)\n' "$rep" "$TIMEOUT_S" >&2 ;;
  esac
  printf '   %s log line(s) -> %s\n' "$log_lines" "$log"

  # Back to an empty bursts dir for the next rep: exactly our files, by name.
  cleanup_pushed
  cleanup_temp

  rep=$((rep + 1))
done

# --- summary ---------------------------------------------------------------

printf '\n== summary ==\n'
[ -f "$HERE/bench.py" ] || die "missing $HERE/bench.py (it turns these logs into the summary)"

# --dir avoids passing a glob of paths: the repo path itself contains a space.
PY_CMD=python
PY_ARGS=""
if ! command -v python >/dev/null 2>&1; then
  if command -v py >/dev/null 2>&1; then
    PY_CMD=py
    PY_ARGS="-3"
  else
    die "no python launcher found (need 'python' or 'py -3') to run bench.py"
  fi
fi

# $PY_ARGS is unquoted on purpose: it is empty or the single word "-3".
# shellcheck disable=SC2086
"$PY_CMD" $PY_ARGS "$HERE/bench.py" --wavs "$WAV_COUNT" --reps "$REPS" --dir "$OUT_DIR"
