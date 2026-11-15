# Sievex — v1 Specification

Status: draft for review. Scope is the walking pipeline on Android. No code exists yet.

Companion: `CONTEXT.md` for the decision log and glossary.

---

## 1. Summary

One flow, end to end:

```
earbud tap (podcast pauses)
   → burst recorded (20–60s)
   → ASR (multilingual, on-device)
   → LLM transform into envelope + mode extras
   → note filed as Markdown in a synced folder
   → mirrored in a thin in-app inbox for exceptions
```

Everything local except one bootstrap download. The app requests `INTERNET` solely to fetch the
speech model on first launch, over HTTPS with pinned SHA-256 per file; after that it never uses the
network. Audio, transcripts and notes never leave the device.

## 2. Scope

**In v1:** walking capture, six intent modes, podcast provenance, cross-note links, Markdown
filing, inbox mirror, search — **English only**.

**In v1.1:** desk mode (screen present, longer dictation, different capture profile).

**Later:** night journaling, continuous recording (only if the audio-routing spike passes), voice
follow-up questions, weekly digest.

**Not built:** live transcript, cloud, multi-speaker, iOS.

---

## 3. Capture

### 3.1 States

```
IDLE ──arm──> ARMED ──pause event / tile──> RECORDING ──stop──> QUEUED
  ^              │                                               │
  └────disarm────┴───────────────────────────────────────────────┘
```

- **IDLE** — no foreground service, no permissions active beyond granted.
- **ARMED** — foreground service running with `microphone` type; `AudioRecord` not started. No mic,
  no audio-focus change, no SCO switch, no privacy indicator.
- **RECORDING** — `AudioRecord` writing a burst.
- **QUEUED** — audio persisted, transform job enqueued. UI never blocks here.

### 3.2 Arming

Arming is a deliberate, user-visible act and is what makes background capture legal and reliable.

- Manual: quick-settings tile, or "Start walk" in the app.
- Auto (opt-in, off by default): arm when headphones are connected **and** a media session is
  playing **and** the step detector reports walking.

Disarm on: explicit stop, headphone disconnect longer than 5 minutes, or 2 hours of no bursts.

### 3.3 Trigger

While ARMED, the app observes third-party media sessions (`NotificationListenerService` with media
access) and watches for the active podcast session transitioning `PLAYING → PAUSED`.

- Start a burst 200 ms after the pause lands (avoids tap-through noise).
- Do not start if a phone call is active or another app holds audio focus for communication.
- If several sessions exist, pick the one that was most recently playing.

The tap is never intercepted. The app reads the pause; it does not own the button.

### 3.4 Burst lifecycle

Start: pause event, or tile tap.

Stop, first of:

| Condition | Value |
|---|---|
| Media resumes | immediately |
| Silence after speech | 10 s |
| Hard cap | 90 s |
| Second tap | immediately |

Discard without filing when: speech detected for less than 1.2 s total, or VAD finds no speech.

### 3.5 Feedback

- Short chime on start, double-blip on stop, both through the current output route.
- Optional haptic if the phone is not in a pocket.
- Persistent notification shows state: *Armed — tap pause to capture* / *Recording* / *N queued*.

Confidence that it is listening must not require looking at the screen.

### 3.6 Edge cases

- Earbuds disconnect mid-burst → stop, keep audio, file normally.
- Screen off, phone locked → foreground service continues; do not request to be exempt from Doze
  unless testing shows it is required.
- No podcast session (silent walk) → trigger falls back to the tile; source degrades to time only.
- Podcast paused before the session armed → no pause event will fire; inbox hint suggests the tile.
- Transform interrupted (reboot, battery death) → job survives in SQLite and resumes on next start.
- Folder permission revoked → note stays in inbox, banner explains, nothing is lost.
- Two bursts within a few seconds → two notes; no debounce merge.

---

## 4. Audio

- Capture: 16 kHz mono, PCM 16-bit. ASR-native rate, no resampling.
- Store: Opus, 32 kbps VBR mono (≈ 240 KB per 60 s burst). Lossy but ASR-robust; switch to FLAC if
  future re-transcription quality ever demands it.
- Path: app-private `files/audio/{note_id}.opus`. Never in the vault.
- Apply light noise suppression (RNNoise) before VAD, not before storage. Keep the raw burst.
- VAD: Silero, 30 ms frames, trim leading/trailing silence before ASR.

Bluetooth note: because the podcast is **paused** during a burst, the SCO/HFP downgrade does not
degrade anything the owner is listening to. This is a side benefit of the pause-as-trigger design.
Expect a one-to-two second re-route glitch when playback resumes.

---

## 5. Transcription

- Engine: **Parakeet TDT 0.6B v3** (FastConformer-TDT, CC-BY-4.0). English in v1; 25 European
  languages available. Punctuation and capitalisation confirmed native — no extra PnC model.
- Runtime: **sherpa-onnx** on Android. It ships an int8 ONNX conversion of this exact model
  (encoder 622 MB, decoder 12 MB, joiner 6 MB, tokens 92 KB), a Kotlin/Java API, Silero VAD, and a
  published arm64-v8a APK, so there is no NDK cross-compile to own. **NeMo-Speech.cpp** stays as the
  desktop reference implementation and quality oracle (q8_0 GGUF, verified working).
- Budget ~0.8 GB resident during transcription, and serialise against the LLM: never both loaded.
- Runs on the queue, never inline with the UI.
- English only in v1. Neither runtime reports a detected language (`languages: []` from
  NeMo-Speech.cpp, `lang: ""` from sherpa-onnx), so misdetection cannot be observed in output.
  Treat it as a latent risk: if English notes start degrading, test whether the model
  misclassified the burst.
- Output language is English; the `language` field stays in the schema so Spanish needs no
  migration later.
- Vocabulary biasing exists in NeMo-Speech.cpp (`--speech-context` plus `--speech-context-boost`,
  repeatable). sherpa-onnx exposes `hotwords_file` and `hotwords_score`; whether it honours them for
  `nemo_transducer` needs verifying before we depend on it.
- VAD is Silero (`silero_vad.onnx`, ~2 MB), shipped as a separate file by both runtimes.
- Store the raw transcript verbatim alongside the cleaned version used in the note.

### 5.1 Host baseline (measured, not projected)

Parakeet TDT v3 `q8_0` through NeMo-Speech.cpp 0.3.0, desktop x86 CPU, default threading:

| Case | Audio | Wall | Speed | WER |
|---|---|---|---|---|
| Short clean | 7.4 s | 0.82 s | 9.1× | 0.0% |
| Long clean | 59.5 s | 4.20 s | 14.2× | 0.0% |
| Long, 10 dB SNR | 59.5 s | 4.16 s | 14.3× | 0.0% |
| Long, 5 dB SNR | 59.5 s | 4.11 s | 14.5× | 2.2% |

Caveats: tiled LibriSpeech, not real walking audio; white noise, not traffic or wind. Real bursts
will score worse. What this establishes is that the runtime, the model, punctuation and scaling are
sound, and that a 60-second burst costs seconds rather than minutes.

Acceptance: word error rate ≤ 15% on the owner's real walking audio, with earbuds, wind and traffic
present.

---

## 6. Transform

One local LLM call, structured output, one note.

### 6.1 Envelope

Every note carries:

```json
{
  "mode": "insight",
  "mode_confidence": 0.86,
  "language": "en",
  "title": "Moat is switching cost, not data",
  "one_line": "Data isn't a moat unless you can't get it anywhere else.",
  "verbatim": "cleaned transcript, preserving the owner's wording",
  "why_it_matters": "one or two sentences of inference",
  "open_question": "a question to chase, or null",
  "tags": ["product", "strategy"],
  "extras": {}
}
```

`verbatim` is the trust anchor: it is the owner's words, lightly cleaned, never rewritten.

### 6.2 Mode extras

| Mode | Extras |
|---|---|
| `insight` | `what_it_challenges` |
| `idea` | `mechanism`, `falsifier`, `next_probe` |
| `action` | `do`, `by_when`, `unblocks` |
| `reference` | `look_up`, `why_it_caught_me` |
| `question` | `already_suspect`, `what_would_settle` |
| `reflection` | `what_surfaced`, `how_it_landed` |

Mode is chosen by the model in the same generation and carries a confidence. Below 0.6 it falls
back to `insight`. A one-tap mode change re-runs the transform from the stored transcript — no
re-recording.

### 6.3 Prompt contract

- System: role, language = the note's language, output JSON only, schema.
- Context: one paragraph of mode guidance, the tag vocabulary, and the three most recent note
  titles for voice calibration.
- Input: transcript, `captured_at`, source metadata, and candidate related notes (titles + one
  lines only).
- Constraints: do not introduce facts absent from the transcript; inference belongs only in
  `why_it_matters`, `open_question`, and the mode extras.
- Constrained decoding against the JSON schema (grammar), temperature 0.2–0.4, ~600 output tokens.

### 6.4 Validation and failure

1. Schema invalid → retry once with the validator error appended.
2. Verbatim fidelity: token overlap with the ASR transcript ≥ 90% (order-insensitive, stopwords
   removed). Failing that likely means fabrication → retry with a stricter instruction.
3. Second failure → file a **raw capture**: title from the first eight words, body = transcript,
   mode `raw`, state `needs_attention`. The note is ugly but never lost, and the inbox shows it.

Hard limits: title ≤ 80 chars, one_line ≤ 180, why_it_matters ≤ 400, open_question ≤ 240.

Tags: at most four, lowercase, seeded vocabulary; a model-proposed new tag is stored but flagged
until it recurs twice, so the vocabulary cannot sprawl.

### 6.5 Model candidates

| | LFM3-1.7B | Qwen3.5-4B |
|---|---|---|
| Parameters | 1.73 B | 4 B |
| Resident at 4-bit | ~1.0 GB (vendor) | ~2.5 GB (estimate) |
| Languages | 16, incl. Spanish | 201, incl. Spanish |
| Licence | LFM Open Licence v1.0 | Apache-2.0 |
| Shape | text-only, edge-tuned | vision + text |

Provisional pick is **LFM3-1.7B**. The transform is narrow extraction plus one sentence of
inference, and it has to coexist in memory with a 0.6 GB ASR model; Liquid also publishes measured
edge decode throughput at least 1.8× the 4B. Qwen3.5-4B is kept as the quality ceiling, not the
default.

Vendor benchmark tables are not comparable across vendors, so the only meaningful test is ten real
Spanish transcripts and ten English ones through both pipelines, judged on the filed note rather
than the transcript.

Both models default to a thinking mode. Disable it and prefill the assistant turn with `{` so JSON
is the only legal continuation; otherwise thinking traces break grammar-constrained decoding.

---

## 7. Related-note links

- Encoder: small multilingual sentence model, 384-dim, int8 ONNX.
- Embed `title + one_line + verbatim` at filing time; store the vector as a BLOB.
- Retrieve top 5 by cosine; attach those scoring ≥ 0.82, capped at 3. No LLM arbitration.
- Render as Obsidian wikilinks matching the filed filenames.
- Fewer than 5 notes in the corpus → no links section, no "empty" placeholder.

Scale note: brute-force cosine over a few thousand notes is microseconds. No vector database.

---

## 8. Filing

### 8.1 Destination

User grants a folder via SAF (`ACTION_OPEN_DOCUMENT_TREE`) with persistable permission. Default
suggestion: `Inbox/Sieve/` inside an existing vault. Whatever sync the owner runs (Obsidian Sync,
Syncthing, Drive) carries it from there; the app has no sync code.

Filename: `{YYYY-MM-DD HHmm} {slug}.md`, slug from the title, letters/digits/spaces/hyphens only,
max 60 chars, short id suffix on collision.

### 8.2 File format

```markdown
---
id: 01JCH3...
captured: 2026-11-13T15:36:12-03:00
mode: insight
mode_confidence: 0.86
language: en
tags: [product, strategy]
source: {app: "Pocket Casts", episode: "…", position_ms: 840000}
duration_ms: 38000
audio: 01JCH3....opus
asr_model: whisper-small-ml-q5
llm_model: qwen3-4b-q4
prompt_version: 3
links: ["2026-11-10 0940 Vertical SaaS pricing.md"]
---

# Moat is switching cost, not data

> Data isn't a moat unless you can't get it anywhere else — and for most of these companies you can.

**What I said**

Every founder kept saying the model isn't the moat, then described their moat as proprietary data,
which is the same mistake with extra steps. The real moat is whatever makes leaving cost a week.

**Why it matters**

Reframes fine-tuning as a data-moat bet. If the moat is switching cost, the budget belongs in
integration, not training.

**Open question**

What's the cheapest way to buy switching cost inside a workflow I don't own?

**Related**

- [[2026-11-10 0940 Vertical SaaS pricing]]
```

Front matter is machine-readable and round-trippable. Body sections are for humans and Obsidian.
`why_it_matters` and `open_question` are rendered as inference, distinct from the verbatim block.

### 8.3 Canonical rules

- **After filing, the file wins.** The database is a cache and an index.
- In-app edits write through to the file immediately and update the stored hash.
- External edits are detected by hash mismatch on rescan (app open, pull-to-refresh) and re-read
  into the database, flagged `externally_modified`. No re-transform, no overwrite.
- If both sides changed since the last sync: file wins; the pending in-app edit is discarded with a
  visible warning. Documented behavior, never silent.
- External deletion marks the note `archived` and is not recreated. Deleting a note in Obsidian is
  a real decision.
- SAF permission revoked → writes queue; audio and database records remain intact.

---

## 9. In-app inbox

Deliberately thin. It exists to fix exceptions, not to be read.

- **Queue** — pending transforms, needs-attention notes, raw fallbacks.
- **Today** — this walk's notes, grouped by session.
- **All** — reverse chronological, filter by mode, tag, date, needs-attention.
- **Search** — SQLite FTS5 over title, one_line, verbatim.

Detail view: rendered note, audio player, raw transcript toggle, and actions — change mode (re-runs
transform), edit text (write-through), delete, open file in the vault, re-file.

---

## 10. Data model

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  context TEXT,              -- walk | desk | night
  burst_count INTEGER DEFAULT 0
);

CREATE TABLE notes (
  id TEXT PRIMARY KEY,
  session_id TEXT REFERENCES sessions(id),
  captured_at TEXT NOT NULL,
  duration_ms INTEGER,
  language TEXT,
  mode TEXT,
  mode_confidence REAL,
  title TEXT,
  one_line TEXT,
  verbatim TEXT,
  why_it_matters TEXT,
  open_question TEXT,
  extras TEXT,               -- JSON, mode-specific
  tags TEXT,                 -- JSON array
  source TEXT,               -- JSON
  audio_path TEXT,
  transcript TEXT,           -- raw ASR
  asr_model TEXT,
  llm_model TEXT,
  prompt_version INTEGER,
  state TEXT,                -- queued|transcribing|transforming|filed|raw|needs_attention|archived
  file_path TEXT,
  file_hash TEXT,
  externally_modified INTEGER DEFAULT 0,
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE links (
  from_id TEXT, to_id TEXT, score REAL,
  PRIMARY KEY (from_id, to_id)
);

CREATE TABLE embeddings (
  note_id TEXT PRIMARY KEY, model TEXT, dim INTEGER, vector BLOB
);

CREATE TABLE jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  note_id TEXT, stage TEXT, attempts INTEGER DEFAULT 0,
  last_error TEXT, created_at TEXT
);

CREATE VIRTUAL TABLE notes_fts USING fts5(
  title, one_line, verbatim, content='notes', content_rowid='rowid'
);
```

---

## 11. Risks and spikes

Ordered. Each has a clear pass/fail question; several can kill or reshape the design.

| # | Risk | Spike | Pass condition |
|---|---|---|---|
| R1 | Background mic start is restricted (Android 14+ foreground-service rules) | Prove an already-running `microphone`-type service can start `AudioRecord` from a background media callback on the target device | A burst records with the screen off and the app not visible |
| R2 | Third-party media sessions may not be observable | Read playback state, metadata, and position from the podcast app via notification access | State transition and episode metadata are both visible |
| R3 | Earbud mic quality vs phone mic | 10-minute manual test: podcast playing, record 30 s on earbuds, then on phone mic, judge both | One route is clearly intelligible outdoors |
| R4 | Parakeet on Android: runtime, speed, int8 parity | sherpa-onnx int8 on-device over 10 real English bursts, compared against the NeMo-Speech.cpp reference | ≥ 2× real time on device; int8 vs q8 disagreement under 2% WER; queue drains within 10 min |
| R5 | Small-model JSON conformance and quality | Same 20 transcripts through LFM3-1.7B and Qwen3.5-4B | ≥ 95% schema-valid first try; 8/10 notes worth keeping |
| R6 | Battery | 60-minute armed session, then transform 5 bursts | Armed < 3%; transform < 2% |
| R7 | SAF round-trip with the real vault | Write, edit externally, rescan, re-edit | Content and hash stay consistent; no duplicate files |
| R8 | Both models resident | Peak RSS across a six-burst walk with serialised loading | < 1.8 GB combined; the 16 GB device makes this advisory, not a gate |

R1 and R2 are the two that could invalidate the trigger. If R1 fails, the fallback is tile-only
capture, which changes the feel from "just talk" to "reach for the phone." Everything else in this
spec survives.

---

## 12. Acceptance criteria

- Pocketed phone, podcast playing, one earbud tap → chime within 300 ms, note filed within 5
  minutes of resuming.
- Ten consecutive real walks: ≥ 95% of bursts become filed notes with no manual intervention.
- Zero lost captures across twenty test walks, including airplane mode, battery saver, and an
  earbud disconnect mid-burst.
- Edit a note in Obsidian, reopen the app: content matches, no duplicate.
- Every filed burst reports `language: en`; a misdetected one is surfaced in the inbox, not
  silently filed.
- Airplane mode for a full walk: everything still works.
- Network use is limited to the one-time, checksum-verified model download.

---

## 13. Deliberately undecided

- LLM identity — LFM3-1.7B is the provisional pick, Qwen3.5-4B the quality ceiling; decided by R5.
- Exact similarity threshold for links (0.82 is a starting guess).
- Whether walk sessions arm automatically.
- Tag seed vocabulary.
- Audio retention policy.

---

## 14. After v1

Desk mode → night journaling → continuous recording if R3/R6 permit → a single follow-up question
asked aloud before filing → weekly digest → re-transform old notes with newer models.

The re-transform path is the reason `audio`, `transcript`, `asr_model`, `llm_model`, and
`prompt_version` are stored separately on every note.
