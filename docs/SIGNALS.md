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

> `eyeClosure` — 0 means open as wide as *your* normal, 1 means fully shut.

**Primary formula:** the fraction of your eye's own opening that has been lost. First the
*eye aspect ratio* (EAR) — how tall the eye opening is divided by how wide it is, straight
from the six lid points of each eye:

```
EAR = (|p2 - p6| + |p3 - p5|) / (2 x |p1 - p4|)
eyeClosure = 1 - (EAR / your open-eye EAR),  clamped to 0..1
```

All distances are converted to pixels first, because the analysis frame is 480x640 — a
normalized vertical distance is not comparable to a horizontal one on a non-square image.

Your open-eye EAR is learned during calibration (§2), median over the window, and it is
genuinely personal: the operator's three sessions calibrated to 0.274, 0.293 and 0.298.
Until ten samples exist the literature default `EAR_OPEN_REF = 0.28` stands, so that a
session starting mid-blink cannot define a shut eye as "open" and then read every closure
as zero afterwards. There is a test for that.

**This is a ratio of physical aperture, which is the quantity the blink and PERCLOS
literature is defined against.** That matters — see §5.1 for what happened when it wasn't.

**Fallback**, used only when the lid points are missing: the average of the detector's two
eye-blink scores.

```
eyeClosure = (eyeBlinkLeft + eyeBlinkRight) / 2
```

Averaging the two eyes means a one-eye glitch cannot fake a blink. Note the two sources are
**not on the same scale** — that is the whole point of §5.1 — so a stream that switched
between them mid-session would show a step. In practice the detector emits both or neither.

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
so the rate is reported as a number rather than a good/bad verdict. It is deliberately
*not* an input to the focus score — see §15.8.

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

### 5.1 RESOLVED — the cutoff was fine; what it was applied to was not

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

#### What the three recordings said

Full output: `bench/eye-scale-20260731.txt`, produced by `bench/analyze_eye_scale.py` from
the committed recordings. Frames were split into open and closed populations using the
*geometric* measure, deliberately, so that the confidence score under investigation was not
used to define its own answer.

Fraction of measurable time counted as "eyes closed", time-weighted:

| definition | distracted | drowsy | focused |
|---|---|---|---|
| `blink >= 0.50` | 0.100 | 0.313 | 0.033 |
| `blink >= 0.65` | 0.013 | 0.180 | 0.002 |
| `blink >= 0.80` (what we shipped) | **0.000** | **0.000** | **0.000** |
| `aperture >= 0.70` | 0.031 | 0.178 | 0.003 |
| `aperture >= 0.80` (P80, what we ship now) | 0.003 | **0.118** | 0.000 |

The eye-blink score's two populations *overlap* on the distracted recording — its open p95
(0.482) and its closed p5 (0.482) are the same number. No cutoff on that measure separates
open from closed there. The aperture measure has no such problem.

#### The fix

**We changed the input, not the threshold.** `PERCLOS_CLOSED_LEVEL` is still 0.80, still
the literature's number, still unfitted to anything. What it tests is now a true aperture
ratio (§3) rather than a model confidence. A shut eye reaches 0.90 on that scale, so P80 is
reachable with headroom — where the old scale topped out at 0.73 and could never get there.

That also means eye closure changed *everywhere*, not just in PERCLOS: blinks and the gaze
eye-closure limit now read the same aperture fraction. Their thresholds were unchanged too.

#### What we are still conservative about

Our aperture is itself a proxy. MediaPipe's mesh does not fully collapse a shut eye, so the
median frame in a held closure reads 0.78 — just under P80. PERCLOS therefore counts only
the deepest part of each closure: 11.8% for a drowsy session in which the eyes were
geometrically closed 26% of the time. **Our PERCLOS reads low, not high.** We prefer that
direction, and we are not fitting a looser cutoff to close the gap, because `aperture >=
0.70` would be a number chosen to make our own numbers look better rather than one that
means anything.

For the same reason, PERCLOS on a focused session reads exactly 0.000 here: at ~9 fps an
ordinary 200 ms blink is one or two frames and rarely gets sampled at its deepest. PERCLOS
on this device measures *sustained* closure, not blinking. `blinkRatePerMin` covers blinks.

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

**It does not work on the A20e, and we are not fixing it yet.** The operator yawned twice
during the `drowsy` recording as the protocol asks. `jawOpen` peaked at 0.612 and spent two
frames — about 0.2 s — above 0.60, against a `YAWN_MIN_MS` of 1200. Detected yawns: 0.

This is the same failure as §5.1 in a different signal: `jawOpen` is a confidence score, we
threshold it as if it were a percentage, and its scale does not reach where we put the line.
The analogous fix would be a mouth aspect ratio from the lip landmarks — but we deliberately
keep no mouth landmarks (§0), so that fix would widen the privacy allow-list and needs
architect sign-off, not a unilateral edit. Until then `yawnCount` reads 0 on this device and
no claim is made for it.

---

## 10. All thresholds in one place

| Constant | Value | Meaning |
|---|---|---|
| `EYE_CLOSE_LEVEL` | 0.50 | eye counts as closing above this (half its opening lost) |
| `EYE_OPEN_LEVEL` | 0.35 | eye counts as open again below this (hysteresis gap: 0.15) |
| `EAR_OPEN_REF` | 0.28 | open-eye aspect ratio, used only until calibration learns yours |
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
2. **Replay tests over the operator's real recordings** in `bench/replays/` — committed
   2026-07-31, three 2-minute sessions on the A20e, and **passing**:

   | | focused | distracted | drowsy |
   |---|---|---|---|
   | face visible | 1.00 | 1.00 | 1.00 |
   | PERCLOS | 0.000 | 0.003 | **0.118** |
   | gaze on screen | **0.984** | 0.599 | 0.730 |
   | blinks/min | 9.4 | 22.4 | 7.4 |
   | long closures | 0 | 3 | **16** |
   | head spread | 0.4° | 9.4° | 3.5° |
   | yawns | 0 | 0 | 0 (see §9) |

   - `PERCLOS(drowsy) > PERCLOS(focused)` — 0.118 > 0.000
   - `gazeOnScreenFraction(focused) > gazeOnScreenFraction(distracted)` — 0.984 > 0.599
   - `longClosureCount(drowsy) > longClosureCount(focused)` — 16 > 0
   - each recording is at least 60 s long and saw a face at least 50% of the time

   If the recordings are ever absent these report as **skipped** with the message
   `NOT MEASURED YET` rather than passing vacuously. We do not fabricate data to make CI
   green (CLAUDE.md §4.1).

   Two things in that table were *not* asserted and are worth noticing anyway: the
   distracted session has the highest blink rate and by far the most head movement (9.4°
   against 0.4° focused), and the drowsy session's gaze fraction sits between the other
   two — closed eyes read as "not on screen", which is correct but means gaze alone cannot
   tell drowsy from distracted. Phase 4 needs more than one signal to separate them.

We assert **ordering only**. No accuracy percentage appears anywhere in this project,
because we have no ground truth to compute one against.

`SeparationReport` prints, but does not assert, the rolling-window spreads in §14.

---

## 14. How much can these numbers be trusted?

Three separate questions, with three different answers. This section exists so that nobody
— including us — quotes a number from this project as more than it is.

### 14.1 Which parts come from research?

| Signal | Basis | Confidence |
|---|---|---|
| **PERCLOS** | Wierwille & Ellsworth (1994); Dinges & Grace (1998, FHWA). The most validated drowsiness measure in the literature, correlated against psychomotor vigilance lapses. The P80 variant is standard. | Definition: strong. Our measurement of it: unvalidated. |
| **Eye aspect ratio** | Soukupová & Čech (2016), *Real-Time Eye Blink Detection using Facial Landmarks*. The formula in §3 is theirs. | Formula: strong. Our per-user normalisation is sensible engineering, not a published method. |
| **Blink duration bands** (50–500 ms blink, >500 ms long closure) | Human blinks run roughly 100–400 ms across many studies; drowsiness work commonly treats sustained closures separately, at 500 ms or 1 s. | Reasonable. Our 500 ms line is at the permissive end. |
| **Blink rate** | 12–20/min is the usual resting figure; it drops substantially during reading and screen work. Our focused session read 5–6/min, which is consistent with that. | Reasonable as an observation. We make no inference from it. |
| **Gaze cone** (25° yaw, 20° pitch, 0.35 iris) | **Nothing.** We chose these by reasoning about a phone on a desk stand. | Invented. |
| **Head stability** (6° spread) | **Nothing.** | Invented. |
| **Yawn timing** (1.2 s, 5 s refractory) | Loosely, that the wide-open phase of a yawn is shorter than the yawn. | Invented — and it does not work here anyway (§9). |

The honest summary: **the drowsiness half has real research behind its definitions and the
attention half does not.** The gaze and head-stability thresholds are documented guesses.

### 14.2 Can the numbers be trusted?

**Not as absolute measurements, and not by anyone other than the person who recorded them.**

- The research validated PERCLOS using dedicated eye-tracking instrumentation. We measure it
  from a 640×480 front camera at ~9 fps through a face mesh that was never designed to
  report eyelid aperture. Same definition, different instrument, no crosswalk between them.
- **Do not import cutoffs from the literature onto our numbers.** Driving studies associate
  PERCLOS above roughly 0.15 with meaningful drowsiness. Our drowsy session scored 0.118 —
  which does *not* mean "below the danger line", because our scale reads low by construction
  (§5.1). Comparing our PERCLOS to a published threshold is a category error.
- There is no ground truth anywhere in this project. Nobody scored these recordings by hand,
  no alertness test was administered, and the operator was *acting* drowsy rather than being
  drowsy. What §14.3 shows is that the pipeline separates three deliberately performed
  behaviours — not that it detects fatigue.
- n = 1 person, 1 session per state, one device, one room, three consecutive recordings.
  Nothing here generalises to another face, another light, or another phone.

What the numbers *are* good for: comparing one person against their own earlier minutes on
the same device in the same session. That is exactly the scope of a self-coaching tool, and
it is the only claim this project makes.

### 14.3 Are they sensitive enough to tell the three states apart?

Measured, not asserted. Rolling windows from the second minute of each recording onwards,
one window per frame, printed by `SeparationReport` (run it: `./gradlew -PcoreOnly
:core:test --tests '*SeparationReport*' -i`):

| | focused | distracted | drowsy |
|---|---|---|---|
| PERCLOS | 0.000 flat | 0.000–0.007 | **0.061–0.142** |
| gaze on screen | **0.982–0.991** | 0.276–0.916 | 0.634–0.734 |
| head spread | **0.20–0.81°** | 2.5–29.0° | 1.5–7.8° |
| blink rate | 5–12/min | **13–32/min** | 3–12/min |

**No single signal separates all three states. Every state is isolated by at least one:**

- **PERCLOS isolates drowsy.** Its lowest drowsy window (0.061) is nearly nine times the
  highest window from either other session (0.007). Clean.
- **Blink rate isolates distracted** — 13/min at its lowest against 12/min at the other two
  sessions' highest. A one-blink margin: real in this data, far too thin to rely on.
- **Gaze and head stability isolate focused**, and neither is close: the focused session
  never left 0.98 gaze or 0.81° of head movement.
- **Overlaps that matter:** distracted and drowsy overlap on gaze *and* on head movement,
  and focused and drowsy overlap on blink rate. So a Phase 4 score that reads only gaze
  cannot tell someone tired from someone distracted — the thing a coach most needs to get
  right, since the useful advice differs.

**The statistical caveat is large.** Those windows are 60 s long inside a 120 s recording,
so the ~560 windows per session are not 560 independent observations — they are one or two,
heavily autocorrelated. "SEPARATED" above is a description of this data, not a significance
test, and it would be wrong to report it as one. What it genuinely rules out is a pipeline
so noisy that a single reading is meaningless; what it cannot tell us is whether the
separation survives a different person, a different day, or genuine rather than acted
fatigue.

**What would actually raise confidence**, in rough order of value per hour spent: repeat
recordings on different days (does focused-vs-focused vary as much as focused-vs-drowsy?);
a second person; a genuinely tired session recorded late at night rather than performed; and
a hand-scored minute of video to check our closure detection against a human count. None of
these are done. Until they are, every number in this project is a documented heuristic.

---

## 15. The focus score and the fatigue flag (Phase 4)

Everything above measures *one thing at a time*. This section is the rule that turns them
into the single number on the Session screen. The code is `core/.../FocusScore.kt` and every
constant named here is in `FocusThresholds`.

### 15.1 Three terms

Each signal is first mapped onto 0..1 by a straight line between two anchors, clamped at
both ends. The anchors come from the operator's three recordings (§14.3), which means they
are **fitted to one person on one device** — the score compares you against your own earlier
minutes, and nothing else.

| Term | From | 0 at | 1 at | Why those anchors |
|---|---|---|---|---|
| **attention** | `gazeOnScreenFraction` | 0.40 | 0.90 | The distracted session ranged 0.28–0.92 over rolling windows; the focused one never fell below 0.98. 0.40 sits *inside* the distracted range, so looking away half the time already scores badly rather than merely starting to. |
| **alertness** | `perclos` | 0.10 | 0.01 | Focused measured a flat 0.000 and distracted never passed 0.007, so under 0.01 is "eyes open". Drowsy ran 0.061–0.142. |
| **steadiness** | `headStabilityDeg` | 15° | 2° | Focused measured 0.20–0.81°; distracted reached 29°. |

### 15.2 The mix

```
rawScore = 100 x (0.50 x attention + 0.30 x alertness + 0.20 x steadiness)
```

The weights are **a judgement call, not a fitted result**: being pointed at the work is the
most direct evidence of focus; eye closure is the fatigue axis the coach exists to catch;
head movement corroborates but on its own would punish someone who simply fidgets. They sum
to 1, so each term's maximum contribution is exactly its weight in points. There is a test
asserting precisely that (`each term contributes exactly its documented weight`).

### 15.3 Smoothing

The displayed score is an exponential moving average of `rawScore` with a time constant of
**8 seconds** (`SCORE_SMOOTHING_TAU_MS`): after 8 s about 63% of a step change has been
absorbed, after 24 s about 95%.

The coefficient is computed from the *actual* gap between frames —
`alpha = 1 - exp(-dt / tau)` — not from a fixed per-frame constant. That matters because
Phase 6 will deliberately drop the camera to ~5 fps: with a per-frame constant the score
would suddenly feel sluggish exactly when the optimisation landed. There is a test that the
score settles the same way at 30 fps and at 5 fps.

Note the inputs are themselves 60-second rolling windows, so most of the smoothing already
happened upstream; 8 s is the anti-twitch layer, not the main damping.

### 15.4 The fatigue flag

Evidence, 0..1, is the **larger** of two ramps — either route on its own is enough:

```
fatigueEvidence = max(
    ramp(perclos,              0.02 -> 0.12),
    ramp(longClosures/min,     1.0  -> 6.0 )
)
```

The drowsy recording produced 16 long closures in two minutes (8/min); the distracted one
produced 3 (1.5/min) and the focused one none.

That evidence then drives a **Schmitt trigger with a dwell time on each edge**:

| | Level | Must hold for |
|---|---|---|
| Raise the flag | evidence >= **0.60** | **15 s** |
| Clear the flag | evidence <= **0.35** | **30 s** |

- The **gap between 0.60 and 0.35** is what stops a value sitting on the line from blinking
  the warning on and off. There is a test that alternates the input either side of the raise
  level for five minutes and asserts the flag changes at most once.
- The **dwell** is what stops a single deep blink raising an alarm: the evidence must be
  past the level *continuously*, and any excursion back across it restarts the clock.
- **Clearing is deliberately slower than raising.** One alert stretch does not mean the
  tiredness has gone.

### 15.5 Warming up

`ready` is false until the neutral pose is calibrated (§2). The score is still computed, but
the Session screen shows `--` rather than a number, because a score measured against an
origin that is still moving would shift under the user for no reason they can see.

### 15.6 What it does on the real recordings

Replayed through the whole pipeline, whole-session means (`RecordedFocusScoreTest`):

| | focused | distracted | drowsy |
|---|---|---|---|
| mean score | **96.7** | 74.9 | 60.7 |
| lowest score | 50 | 33 | 42 |
| fatigue flag raised | never | never | **66% of the session** |

Asserted in CI: focused scores above both others, and the fatigue flag fires on the drowsy
session and on neither of the other two.

Two honest caveats about that table. The **means are flattered by the warm-up**: these are
2-minute clips, and the 60-second rolling windows spend the first minute filling, so the
distracted session's mean of 74.9 sits well above the 33–51 it settles at once the window
is full. And the lowest score of 50 in the *focused* session is that same warm-up, not a
lapse in attention. Over a realistic 25-minute session neither effect matters; over a
2-minute clip they dominate, which is why §14.3 reports rolling windows from the second
minute onwards instead.

### 15.7 The session export

`SessionRecording` (`core/.../SessionRecord.kt`), written by the Session screen's **Export
session JSON** button and shared through the system share sheet. It carries the device and
silicon facts from the Phase 1 probe, whole-session totals, and one row per second:

```
{
  "schemaVersion": 1, "appVersion": "0.4.0-phase4",
  "startedAtEpochMs": ..., "durationMs": ...,
  "device": { "model": "...", "cpu_features": "...", "total_ram_bytes": "...", ... },
  "summary": { "meanScore": 96.7, "minScore": 50, "maxScore": 100,
               "fatigueFraction": 0.0, "perclos": 0.0, ... },
  "samples": [
    { "t": 0, "score": 88, "rawScore": 88.4,
      "attention": 1.0, "alertness": 1.0, "steadiness": 0.61,
      "fatigue": false, "fatigueEvidence": 0.0, "ready": true,
      "faceVisible": true, "perclos": 0.0, "gazeOnScreenFraction": 0.99, ... },
    ...
  ]
}
```

**It contains no landmarks and no blendshapes** — unlike a Phase 3 landmark recording, which
is a test fixture and does. A session export is the safer of the two files to send anywhere,
and there is a test asserting the encoded text cannot even mention face geometry.

The timeline is thinned to one row per second (the pipeline produces about nine); the
*summary* is still computed from every frame. A one-hour session is roughly 3 600 rows.

### 15.8 What is deliberately not in the score

- **Blink rate**, even though §14.3 found it was the one signal that isolated the distracted
  session. It did so by 13/min against 12/min — a **one-blink margin** on a single
  recording. A term that fragile would add noise dressed up as information. It is measured,
  displayed and exported; it just does not move the score.
- **Yawns**, because yawn detection does not work on this device at all (§9). A term that is
  structurally zero would be dead weight, and worse, would look like evidence of calm.
- **`faceVisible` as its own term.** Walking away already drives `gazeOnScreenFraction`
  down, because a missing face counts as "not on screen" (§6). Adding a second penalty for
  the same event would double-count it.

If a later phase adds any of these, the reason should be a new measurement, not a hunch.
