# Submission screenshots

Proof artifacts for the Arm AI Optimization Challenge entry. Each one exists to make a
specific claim checkable by a judge who cannot run the app.

**Committed so far**: `airplane-coach.jpg` and `session-distracted.jpg`, both from
2026-08-02. Two other screenshots from the same evening were pulled and **deliberately not
committed** — the Session screen had not scrolled in those, so the camera preview, and the
operator's face, are in frame. The two that are here happen to be face-free because the
screen was scrolled past the preview when they were taken; nothing was cropped.

| file | what it has to show | why it matters |
|---|---|---|
| `airplane-coach.png` | A coaching message on the Session screen **with the airplane-mode icon visible in the status bar**, and the measured TTFT / tok-s beside it | The core claim of the whole project: a language model wrote this on the device, with every radio off. Without the status bar in frame it proves nothing. |
| `session-score.png` | The Session screen mid-session: score, timeline, summary numbers | What the app actually is |
| `llm-benchmark.png` | The threads × KV-cache benchmark table | The optimisation story, in the app's own words |
| `probe-silicon.png` | The silicon probe screen | The two-device Arm story: what this phone is and what it lacks |

## Before committing any of these — read this

**The Session screen shows a live camera preview.** A screenshot of it will contain the
operator's face, and this repository is public and must stay public (CLAUDE.md §4.4).

That is not a privacy-rule violation by the app — nothing leaves the device on its own — but
publishing a photograph of a person is a separate decision from publishing numbers, and it is
the operator's to make, not the builder's.

Before adding a Session-screen screenshot, do one of:

1. **Crop out the preview strip.** It is the top ~110 dp of the content, immediately below the
   status bar. **Keep the status bar** — the airplane icon is the entire point of
   `airplane-coach.png`.
2. **Black it out** in any image editor.
3. **Point the phone at a ceiling or wall** and screenshot after the coach has spoken. The
   message stays on screen once generated, though the face indicator will read `no face`.

The smoke-test, benchmark and probe screens carry no camera preview and need none of this.

## Getting them off the phone

Plug the phone into the PC over USB and copy from `Internal storage/Pictures/Screenshots/`
(or `DCIM/Screenshots/` depending on the Android version), then drop them in this folder with
the names above.
