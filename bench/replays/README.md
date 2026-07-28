# bench/replays — recorded landmark streams

Test fixtures for the `:core` replay tests. Each file is one labelled session recorded on a
real device by the app's recorder.

**These files contain numbers only — no video, no images, no face mesh.** Per frame: 13
eye/jaw scores, a 4x4 head-orientation matrix, and 14 eye-contour points. The format is
documented in [`docs/SIGNALS.md` §11](../../docs/SIGNALS.md); the recording protocol the
files must follow is §12 of the same document.

## Expected contents

| File | Label | What it is |
|---|---|---|
| `focused-*.json` | `focused` | 2 minutes of normal reading |
| `distracted-*.json` | `distracted` | 2 minutes of glancing at a second phone |
| `drowsy-*.json` | `drowsy` | 2 minutes of slow, long eye closures |

The tests pick a recording up by the label in its filename, so keep the label as the first
part of the name (the app names files that way automatically).

## What the tests do with them

`RecordedOrderingTest` asserts the obvious ordering between the three — drowsy has more eye
closure than focused, focused looks at the screen more than distracted. Nothing else. We
have no ground truth, so we make no accuracy claim.

If these files are absent, those tests report as **skipped** with `NOT MEASURED YET` rather
than passing on invented data.

## Adding a recording

Recordings are append-only, like everything under `bench/`. Do not edit or re-cut an
existing file — record a new session and add it.
