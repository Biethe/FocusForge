# Signals — what FocusForge measures, and how

> Plain-language reference for every number the app computes. Written so a non-expert can
> check the reasoning; the code is in the `:core` module (`core/src/main/kotlin/com/focusforge/core/`)
> and every threshold named here is a constant in `SignalConfig.kt`.

## 0. What we do not do

We measure **behaviour**, not feelings. Eyes open or shut, how long a blink lasted, which
way the head is pointing, whether the jaw is wide open. There is no emotion classification
anywhere in this project and there never will be — the EU AI Act bans emotion inference in
education and workplace settings, and our framing is a self-coaching tool a person runs on
themselves.

That is enforced, not just promised. MediaPipe offers 52 facial "blendshape" scores, many
of which describe expression (brows, cheeks, mouth corners). `SignalMapper.kt` keeps
**13** of them — eyes and jaw only — and drops the rest before they reach memory. Of the
478 face-mesh points it keeps **14**. Nothing else is ever computed, stored, or recorded.

Camera frames are processed in RAM and discarded. The only thing that can be written to
storage is a *recording*: a list of those 13 + 14 numbers per frame. There is no way to
reconstruct a face, let alone identify a person, from one.

---

## 1. What one frame gives us

For each camera frame the detector returns, or does not return, a face. When it does, we
take:

| What | How many | Used for |
|---|---|---|
| Eye blink scores (left, right) | 2 | eye closure, blinks, PERCLOS |
| Eye squint scores | 2 | kept for later phases |
| Eye look in/out/up/down (both eyes) | 8 | kept as a cross-check on iris gaze |
| Jaw open score | 1 | yawns |
| Facial transformation matrix | 16 floats | head yaw / pitch / roll |
| Eye-contour points (6 per eye) | 12 | eye aspect ratio |
| Iris centre points | 2 | horizontal gaze |

A frame where no face was found still counts: it is recorded with `faceVisible = false` and
no other data, so that time away from the phone is honestly accounted for rather than
silently skipped.

---

## 2. First, the app learns *your* neutral

There is no universal "facing the screen". It depends on where the phone sits on its
stand, how tall you are, and where your book or laptop is. So for the first
**5 seconds** (`BASELINE_CALIBRATION_MS`) with a face in view, the app records your yaw,
pitch and iris position and takes the **median** of each. That becomes zero. Every gaze
threshold afterwards is a deviation from *your* neutral, not from an assumed straight-ahead.

Median rather than average, so one blink or one stretch during those five seconds does not
drag the origin with it. At least 10 good frames are required
(`BASELINE_MIN_SAMPLES`); once set, the baseline is frozen for the session.

**This is why every recording must start with ~10 seconds of looking normally at your
work** — see the protocol in §9.

---

## 3. Eye closure

> `eyeClosure` — 0 means wide open, 1 means fully shut.

**Primary formula:** the average of the detector's two eye-blink scores.

```
eyeClosure = (eyeBlinkLeft + eyeBlinkRight) / 2
```

Averaging the two eyes means a one-eye glitch cannot fake a blink.

**Fallback**, used only if blink scores are missing: the *eye aspect ratio* (EAR), computed
straight from the six lid points of each eye — how tall the eye opening is divided by how
wide it is.

```
EAR = (|p2 - p6| + |p3 - p5|) / (2 x |p1 - p4|)
eyeClosure = (EAR_OPEN_REF - EAR) / (EAR_OPEN_REF - EAR_CLOSED_REF),  clamped to 0..1
```

`EAR_OPEN_REF = 0.28` and `EAR_CLOSED_REF = 0.13` are typical open- and closed-eye values.
All distances are converted to pixels first, because the analysis frame is 480x640 — a
normalized vertical distance is not comparable to a horizontal one on a non-square image.

If neither source is available, `eyeClosure` is **null**, not zero. That distinction runs
all the way through: we never report "eyes open" when what we mean is "cannot see".

---

## 4. Blinks, blink rate, and long closures

A *closure* starts when `eyeClosure` rises above `EYE_CLOSE_LEVEL = 0.50` and ends when it
falls back below `EYE_OPEN_LEVEL = 0.35`. The 0.15 gap is **hysteresis**: without it a
score hovering near 0.5 would emit dozens of fake blinks per second. (There is a test for
exactly that.)

What the closure was then depends on how long it lasted:

| Duration | Verdict | Why |
|---|---|---|
| under 50 ms | noise, ignored | shorter than any real blink |
| 50–500 ms | **a blink** | human blinks run roughly 100–400 ms |
| over 500 ms | **a long closure** | this is the drowsiness marker, not a blink |

**Blink rate** is the number of blinks in the last 60 seconds, reported per minute. Below
20 seconds of data (`BLINK_RATE_MIN_COVERAGE_MS`) it reports *nothing* rather than
extrapolating a rate from three seconds of camera. Typical adult rate is 12–20 per minute;
both unusually low (locked-in concentration) and unusually high (strain) are meaningful,
which is why Phase 4 will use the rate rather than a "good/bad" verdict.

If the face disappears mid-closure, the closure is abandoned rather than measured — we
have no idea how long the eyes stayed shut while we could not see them.

---

## 5. PERCLOS

> The single most established drowsiness measure there is.

**PERCLOS = the fraction of time the eyes were at least 80% closed.**

We use the P80 definition (`PERCLOS_CLOSED_LEVEL = 0.80`) over a rolling
**60-second** window (`PERCLOS_WINDOW_MS`).

Two details that matter more than the threshold:

1. **It is measured in time, not frames.** See §8.
2. **Time when the eyes cannot be seen is excluded from the calculation**, not counted as
   "eyes open". If you walk away for 20 seconds, PERCLOS reports what your eyes were doing
   during the time we could actually see them, and `perclosCoverageMs` tells you how much
   time that was. Counting an absent face as "not closed" would quietly flatter a user who
   left the room.

### 5.1 Open question: is 0.80 the right number on this device?

The P80 definition comes from research that measures the *eyelid aperture* — the percentage
of the pupil actually covered by the lid. We do not have that. We have MediaPipe's
`eyeBlink` score, which is a model output on a 0–1 scale, not a physical percentage. Using
0.80 on it assumes the two scales line up.

**They do not, on this device.** Measured on the A20e with build `0.3.1-eyediag`,
2026-07-31, operator holding both eyes fully shut for 15 s:

| Reading | Eyes open | Peak during a held closure |
|---|---|---|
| `eyeBlink` left | 0.16 | **0.79** |
| `eyeBlink` right | 0.09 | **0.66** |
| average of the two (what PERCLOS tests) | 0.12 | **0.73** |
| eye aspect ratio from the lid landmarks | 0.26 | **0.02** (open extreme 0.28) |

With `perclosCoverageMs` reporting a full 60 s of measurable time and `bs 13` confirming
every allow-listed blendshape arrives, the pipeline is working exactly as designed — the
cutoff is simply unreachable. Note that this is *not* the two-eye averaging: the more
closed eye alone peaked at 0.79, still under 0.80. The score's scale does not reach 0.8.

The landmark path separated open from closed by a factor of 14 over the same test, which
makes it the stronger candidate as PERCLOS's input — and, being a true aperture ratio, the
one that lets us keep the P80 definition rather than bend a threshold to fit a confidence
score.

**Still unmeasured:** the *typical* value during a sustained closure. Both numbers above are
single-frame extremes, and a cutoff picked from an extreme under-triggers just as badly as
one picked from a paper. The `drowsy` recording in §12 is twelve 2-second closures, which
is exactly the distribution needed; `bench/analyze_eye_scale.py` computes it from the raw
frames. **No threshold changes until that analysis exists** (CLAUDE.md §4.1).

Until then PERCLOS reads 0.000 on this phone. That is a known-wrong number, kept visible
rather than papered over, and `longClosureCount` (cutoff 0.50, which *is* reached) carries
the drowsiness signal in the meantime.

---

## 6. Gaze on screen

A heuristic, and labelled as one. At each frame you are "on screen" if **all** of these hold:

```
a face is visible
AND eyeClosure < 0.60                       (GAZE_MAX_EYE_CLOSURE)
AND |yaw   - your neutral yaw|   <= 25 deg  (GAZE_MAX_YAW_DEV_DEG)
AND |pitch - your neutral pitch| <= 20 deg  (GAZE_MAX_PITCH_DEV_DEG)
AND |iris offset - your neutral| <= 0.35    (GAZE_MAX_IRIS_RATIO)
```

- **Head yaw and pitch** come from the 4x4 transformation matrix (§7). Pitch is the tighter
  of the two because the case we most care about — glancing down at a second phone in your
  lap — is almost entirely a pitch movement.
- **Iris offset** catches the side-glance you make *without* moving your head. It is the
  horizontal distance from the iris centre to the midpoint between the eye corners,
  as a fraction of half the eye width: 0 = dead centre, ±1 = iris at a corner. Both eyes are
  measured along the image's horizontal axis (not along each eye's own axis, which would make
  the two eyes cancel each other out when averaged) and averaged.
- **Vertical iris movement is deliberately not used.** At 640×480 and 50 cm the eye opening
  is a handful of pixels tall; a number derived from it would be noise. Looking up and down
  is captured by head pitch instead.
- The eye-closure term is set above the blink threshold so that ordinary blinking does not
  punch holes in the gaze trace. There is a test for that too.

**No face at all counts as "not on screen"** — that is a real answer, not a missing
measurement. A visible face whose head pose could not be computed is excluded instead.

`gazeOnScreenFraction` is the share of the last 60 seconds spent on screen.

---

## 7. Head pose and head-pose stability

The detector returns a 4x4 matrix describing where the head is and which way it points. We
extract three angles from its rotation part: **yaw** (turning left/right), **pitch**
(nodding up/down) and **roll** (tilting).

### The layout question, and why it is settled rather than assumed

Sixteen floats can be stored row-by-row or column-by-column, and MediaPipe's Java API does
not say which. Reading it the wrong way gives the transpose, which for a combined rotation
is genuinely a different pose — not simply the same angles with flipped signs.

We do not guess. The matrix also carries the head's **position** in front of the camera,
which is never zero. That position sits in the fourth *column* if the matrix is row-major
and in the fourth *row* if it is column-major. Whichever of the two is non-zero tells us
the layout, per frame, with no assumption at all (`HeadPose.detectLayout`). A unit test
writes the same pose both ways and asserts both read back identically.

### Stability

`headStabilityDeg` is the combined spread of yaw and pitch over the last **10 seconds**
(`HEAD_STABILITY_WINDOW_MS`):

```
headStabilityDeg = sqrt( variance(yaw) + variance(pitch) )
```

Below `HEAD_STABLE_MAX_DEG = 6` degrees the head counts as steady. Someone reading still
drifts a few degrees; six allows that and excludes looking around the room.

---

## 8. Why everything is measured in time, not frames

The camera does not deliver frames at a fixed rate — it drifts with light and CPU load, and
Phase 6 will *deliberately* drop the vision loop to about 5 fps to save battery. If PERCLOS
counted frames, it would move whenever the frame rate moved, and our own optimisation would
corrupt our own measurements.

So each sample carries the time since the previous sample. A single sample can never
account for more than `MAX_FRAME_WEIGHT_MS = 500` ms, so one stall (app backgrounded,
camera hiccup) cannot be charged entirely to whichever frame happened to be last. There is
a test asserting that the same behaviour measured at 30 fps and at 5 fps gives the same
answer to within 2%.

---

## 9. Yawns (optional signal)

The jaw counts as wide open above `YAWN_JAW_OPEN_LEVEL = 0.60` and closed again below
`YAWN_JAW_CLOSE_LEVEL = 0.35` (hysteresis, same reasoning as blinks). It must stay open for
`YAWN_MIN_MS = 1200` ms to count, which excludes talking and laughing, and two yawns must be
`YAWN_REFRACTORY_MS = 5000` ms apart so one long yawn is not counted twice.

This is the first item on the cut list in `docs/PROMPTS.md` if we fall behind.

---

## 10. All thresholds in one place

| Constant | Value | Meaning |
|---|---|---|
| `EYE_CLOSE_LEVEL` | 0.50 | eye counts as closing above this |
| `EYE_OPEN_LEVEL` | 0.35 | eye counts as open again below this (hysteresis gap: 0.15) |
| `EAR_OPEN_REF` / `EAR_CLOSED_REF` | 0.28 / 0.13 | open / closed eye aspect ratio, fallback path only |
| `BLINK_MIN_MS` | 50 | shorter closures are noise |
| `BLINK_MAX_MS` | 500 | longer closures are long closures, not blinks |
| `BLINK_RATE_WINDOW_MS` | 60 000 | blink rate is per minute |
| `BLINK_RATE_MIN_COVERAGE_MS` | 20 000 | no rate is reported below this much data |
| `PERCLOS_CLOSED_LEVEL` | 0.80 | the standard P80 definition |
| `PERCLOS_WINDOW_MS` | 60 000 | rolling minute |
| `MAX_FRAME_WEIGHT_MS` | 500 | cap on the time one frame may represent |
| `GAZE_MAX_YAW_DEV_DEG` | 25 | head turn still counting as on screen |
| `GAZE_MAX_PITCH_DEV_DEG` | 20 | head tilt still counting as on screen |
| `GAZE_MAX_IRIS_RATIO` | 0.35 | side-glance limit, fraction of half an eye width |
| `GAZE_MAX_EYE_CLOSURE` | 0.60 | above this you cannot be looking at anything |
| `GAZE_WINDOW_MS` | 60 000 | rolling minute |
| `HEAD_STABILITY_WINDOW_MS` | 10 000 | window for head movement |
| `HEAD_STABLE_MAX_DEG` | 6 | spread below which the head is "steady" |
| `YAWN_JAW_OPEN_LEVEL` / `YAWN_JAW_CLOSE_LEVEL` | 0.60 / 0.35 | jaw open / closed |
| `YAWN_MIN_MS` | 1 200 | minimum wide-open time for a yawn |
| `YAWN_REFRACTORY_MS` | 5 000 | minimum gap between two counted yawns |
| `BASELINE_CALIBRATION_MS` | 5 000 | learning your neutral pose |
| `BASELINE_MIN_SAMPLES` | 10 | minimum good frames for a baseline |

**None of these are validated against ground truth**, because we have none. They are
documented heuristics. The only thing this project asserts is *ordering* between labelled
recordings — see §12.

---

## 11. The recording format

One JSON file per session, written by the app, read by the tests. Numbers only.

```
{
  "schemaVersion": 1,
  "label": "focused",              // focused | distracted | drowsy
  "device": "samsung SM-A202F",
  "androidVersion": "11",
  "appVersion": "0.3.0-phase3",
  "imageWidth": 480, "imageHeight": 640,
  "mirrored": true,
  "blendshapeNames": [ ...13 names... ],   // the file says what its own columns mean
  "landmarkIndices": [ ...14 indices... ],
  "frames": [
    { "t": 0, "faceVisible": true,
      "blendshapes": [ ...13 floats... ],
      "matrix":      [ ...16 floats... ],
      "landmarks":   [ ...42 floats: x,y,z per index... ] },
    ...
  ]
}
```

`t` is milliseconds since the first frame. A frame with no face carries `faceVisible: false`
and three empty lists. Values are rounded to 4 decimals — far finer than the detector's own
precision, and it roughly halves the file size (a 2-minute recording is around 0.5 MB).

The name and index tables are stored *inside* the file, so old recordings stay readable if
we ever change the allow-lists.

---

## 12. Recording protocol — what the operator does

Three recordings, two minutes each. The app stops each one automatically at 2:00, so you
cannot get the length wrong.

### Before you start

1. Charge the phone above 50%.
2. Sit where you normally study, with something to read (book, tablet, laptop) in front of you.
3. Put the phone on its stand **beside** what you are reading, roughly 40–70 cm away, camera
   facing you. Do not move the phone or the stand between the three recordings.
4. Light on your face, not behind you. Avoid sitting with a bright window at your back.
5. Install the newest APK from
   https://github.com/Biethe/FocusForge/releases/tag/dev-latest and open **FocusForge**.
6. Tap **Open camera probe**. Allow the camera. Wait until green dots appear on your face
   and the panel at the top shows numbers.

### The rule that applies to all three recordings

**Spend the first 15 seconds simply looking at your reading material, normally.** The app
learns your neutral head position during that window. If you start a recording already
looking away, every number afterwards will be measured from the wrong origin.

### Recording 1 — "focused"

1. Tap the **Label** button until it reads `focused`.
2. Tap **REC**. A message confirms it started.
3. Read normally for two minutes. Blink normally. Do not perform — just read.
4. At 2:00 the app stops itself and shows "saved ... frames".

### Recording 2 — "distracted"

1. Tap **Label** until it reads `distracted`. (You cannot change the label while recording.)
2. Tap **REC**.
3. First 15 seconds: read normally.
4. Then repeat for the rest of the two minutes: pick up a second phone, hold it low and to
   one side, look at it for about 10 seconds, then look back at your reading for about 5
   seconds. Roughly 7–8 of these cycles.
5. It stops itself at 2:00.

### Recording 3 — "drowsy"

1. Tap **Label** until it reads `drowsy`.
2. Tap **REC**.
3. First 15 seconds: read normally.
4. Then keep facing your reading, but act tired: close your eyes slowly and hold them shut
   for about **2 seconds**, roughly every 8–10 seconds. That is around 12 slow closures.
   Yawn widely twice somewhere in the middle.
5. It stops itself at 2:00.

### Getting the three files to the PC

After each recording, tap **Share** and send the file to yourself (Gmail to your own
address is easiest, or save to Drive/Files). Then on the PC, download all three and put
them in the project's `bench/replays/` folder. They are named
`focused-...json`, `distracted-...json`, `drowsy-...json`.

Alternatively, plug the phone into the PC by USB and copy from
`Android/data/com.focusforge/files/replays/`.

### If something looks wrong

- Green dots do not stick to your face → note the lighting and report it; do not record.
- "Wait for the face mesh to appear before recording" → the detector has not produced a
  frame yet; wait a few seconds and tap REC again.
- The signals panel says `baseline calibrating…` for more than 10 seconds → the face is not
  being detected reliably. Fix the lighting first.

### Watching the signals while you do it

The panel under the performance HUD shows every value live. Two things you can verify with
your own eyes, no numbers required:

- **Close your eyes and hold them shut** → `long closures` ticks up.
  (`PERCLOS` stays at 0.000 on the A20e — that is the known-wrong cutoff described in §5.1,
  not a fault in your recording. It does not affect what gets stored.)
- **Turn your head away** → `gaze` flips from `ON` to `OFF`.

### The eye diagnostic (build 0.3.1-eyediag, temporary)

Two extra lines near the bottom of the panel exist to settle §5.1. To read them:

1. Open the camera screen, look at the phone normally for ~10 seconds.
2. **Tap the signals panel once** — a "Eye peaks reset" message appears. This clears the
   `eyes peak` line so it measures only what happens next.
3. Close your eyes and hold them shut for **15 seconds**. Keep your head still and facing
   the phone.
4. Open your eyes and read the two lines out loud / photograph them:

```
eyes raw  L 0.04  R 0.05  avg 0.05  EAR 0.26  bs 13
eyes peak L 0.97  R 0.62  avg 0.79 (P80=0.80)  EAR 0.11..0.27  [tap=reset]
```

(Those numbers are an illustration, not a measurement.) What matters is `bs` — 0 means no
blendshapes arrived at all — and `peak avg`, which is the number PERCLOS compares against
0.80.

**This was run on 2026-07-31; the result is in §5.1.** The procedure is kept here because
it is worth re-running on any second device.

**It does not block the three recordings.** Recordings store the raw blendshape values and
the lid landmarks, so PERCLOS can be recomputed under any definition or cutoff afterwards
without re-recording.

---

## 13. What CI asserts

Two layers, both on the `ubuntu-24.04-arm` runner (`core-tests` job):

1. **Synthetic tests** (always run, 55+ of them): every formula and threshold against
   inputs whose answer we know exactly — hysteresis, frame-rate independence, the matrix
   layout, calibration, PERCLOS with a missing face, and so on. Plus ordering assertions
   over three *generated* streams, which prove the pipeline separates the three behaviours.
2. **Replay tests over the operator's real recordings** in `bench/replays/`:
   - `PERCLOS(drowsy) > PERCLOS(focused)`
   - `gazeOnScreenFraction(focused) > gazeOnScreenFraction(distracted)`
   - `longClosureCount(drowsy) > longClosureCount(focused)`
   - each recording is at least 60 s long and saw a face at least 50% of the time

   Until the recordings are committed these report as **skipped**, with the message
   `NOT MEASURED YET`. We do not fabricate data to make CI green (CLAUDE.md §4.1).

We assert **ordering only**. No accuracy percentage appears anywhere in this project,
because we have no ground truth to compute one against.
