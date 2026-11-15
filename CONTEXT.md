# Sievex — Context

Personal-use Android app: capture a thought by voice when your hands are busy, transform it
locally, and file it into a Markdown vault already shaped for later digestion. Walking is the
anchor case.

> Working name taken from the project folder. Nothing below depends on it.

## Thesis

The product is not transcription. It is the sixty seconds after transcription, and the guarantee
that the five seconds of capture never lose anything.

Three stages, judged separately:

- **Capture** — seconds, outdoors, hands busy. Success = nothing lost, nothing to fiddle with.
- **Transform** — unattended, on-device. Success = an artifact you would have written yourself
  with ten minutes and a keyboard.
- **Digest** — later, seated. Success = reviewing beats re-listening, and the note connects to
  what you already know.

Most dictation apps optimize stage one as a screen experience (live transcript). That is the
wrong interface for a pocketed phone, which is why they feel clunky in exactly the walking case.
The leverage is in the two seams: never losing the capture, and arriving already shaped.

## Locked decisions

| # | Question | Decision | Why |
|---|----------|----------|-----|
| 1 | Platform | Android flagship, single user, no cloud | Owner's device; full local control |
| 2 | Capture rhythm | On-demand bursts, 20–60s, never continuous by default | Protects podcast fidelity and battery |
| 3 | Trigger | Earbud tap, read as the podcast's own pause/resume — no button hijacking | Works with any podcast app, no OEM button war |
| 4 | Session | A walk session groups bursts; armed foreground service, mic closed until a burst | Background-start rules; no idle mic |
| 5 | Languages | **English only in v1**; Spanish deferred. One language per note when it lands | Cuts the corpus and the edge cases; no cost to staying on the multilingual model |
| 6 | Scope order | Walking v1 → desk v1.1 → night journaling later | One context done properly |
| 7 | Modes | Six intent modes; topic is a tag. Model picks + confidence, one-tap override | Being wrong is cheap because audio persists |
| 8 | Artifact | Fixed envelope + per-mode extras | Six modes ≈ 1.5× the work of four |
| 9 | Model latitude | Organize, plus clearly separated inference fields | Body stays the owner's words |
| 10 | Links | In v1, embedding similarity only, high threshold, no LLM arbitration | Predictable; brute force is instant at this scale |
| 11 | Filing | Auto-file every note to a Markdown folder; thin in-app mirror | App is a pipe, not a second notebook |
| 12 | Review | Queue, open whenever; fix exceptions only | No ritual to maintain |

## Glossary

- **Burst** — one on-demand recording, typically 20–60 seconds.
- **Walk session** — a container that groups the bursts of one walk; also the scope of an armed
  foreground service.
- **Armed** — the service is running and permitted to capture, but the microphone is closed.
- **Transform** — the local ASR → LLM pass that turns audio into a filed note.
- **Filing** — writing the note's Markdown into the chosen folder and recording its hash.
- **Mode** — the intent of the capture (insight, idea, action, reference, question, reflection).
- **Envelope** — the fields every note has, regardless of mode.
- **Extras** — the two or three mode-specific fields layered on the envelope.
- **Source** — provenance: walk/time, plus podcast episode and playback position when available.

## Non-goals for v1

- Live transcript or any streaming-on-screen mode
- Cloud sync, accounts, multi-device backend
- Meetings, multi-speaker capture, call recording
- iOS
- Wake word or always-listening
- In-app note browser as the primary surface
- Audio editing or annotation

## Open questions

- Where the Markdown folder lives and which sync carries it (spec assumes a user-granted SAF
  folder plus whatever sync the owner already runs).
- Audio retention policy (default: keep indefinitely, surface total size).
- Tag vocabulary: seeded list versus free-form (proposal in spec: seeded, model may propose new).
- Walk session auto-start versus manual arming.

## Pointers

- `SPEC.md` — the buildable v1 specification.
