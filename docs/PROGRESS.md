# Progress Log

> One dated entry per session, newest first. Written in plain language for a non-expert.
> Every claim in **Evidence** must be a link (CI run, committed file) or
> "operator confirmed: &lt;what was seen&gt;". If something is not measured yet, it says
> `NOT MEASURED YET`.

## 2026-07-31 — Phase 4: Focus score, fatigue flag, and the Session screen

**Did**
- Built the thing the whole project has been aiming at: **one number, 0 to 100**, that says
  how focused you are right now, plus a **fatigue flag** that goes up when you are fading.
- The score mixes three of the Phase 3 signals: are you pointed at your work (half the
  score), are your eyes staying open (a third), is your head settled (the rest). Each is
  turned into a 0-1 rating using the *measured* ranges from your own three recordings, then
  blended. Every rule and every number is written out in `docs/SIGNALS.md` §15 — no weight
  is buried in the code.
- **Smoothing**, so the number does not twitch: it eases towards the truth over about 8
  seconds. A single bad frame cannot move it; a real 30-second distraction clearly does.
- **Hysteresis on the fatigue flag**, so the warning cannot flicker: the evidence has to
  cross a high line and stay there 15 seconds to raise it, and fall under a *lower* line and
  stay there 30 seconds to clear it. Getting tired is easier to declare than getting better.
- **The Session screen**: big score at the top, a timeline of the whole session underneath
  (with the tired stretches shaded red), elapsed time, live summary stats, and a small
  camera preview so you can check you are in frame. Plus **Export session JSON**, which
  shares a file containing every signal, every score, timestamps, and the phone's silicon
  details from the Phase 1 probe.
- Merged the camera plumbing that the camera screen and the session screen both need into
  one place, so the two cannot drift apart.

**Evidence**
- **87 `:core` tests pass** (up from 66), including 21 new ones over the fusion, the
  smoothing and the hysteresis specifically: the flag does not move on a single deep blink,
  it does not flicker when the evidence hovers exactly on the trigger, and the score settles
  at the same rate whether the camera runs at 30 fps or 5 fps.
- Replayed over your three real recordings, **asserted in CI**:

  | | focused | distracted | drowsy |
  |---|---|---|---|
  | mean score | **96.7** | 74.9 | 60.7 |
  | lowest | 50 | 33 | 42 |
  | fatigue flag | never | never | **66% of the session** |

- APK builds locally, 33.9 MB, `0.4.0-phase4`.
- On-phone behaviour: **NOT MEASURED YET** — that is your verification below.

**Next — what you do**
1. Install `0.4.0-phase4` from the dev-latest release. Open FocusForge → **Start focus
   session**.
2. **Check the camera screen still works first** (Open camera probe → the mesh appears, REC
   works). Its camera code was moved into a shared file this session; it should behave
   identically, but that is the one thing I could not test without the phone.
3. Prop the phone up and read something for 5 minutes. The score should sit high and drift
   gently, not jump around.
4. Stare out of the window for 30 seconds. The score should visibly fall, then climb back
   when you return. If it moves by ±30 within a second or two, tell me — that is a bug.
5. Tap **Export session JSON** and send the file to the architect for review.

**Risks**
- The score's anchors come from *your* three recordings on *your* phone. On another face or
  another stand they may be wrong, and there is no way to know until someone tries.
- The 2-minute means above are flattered by the warm-up: the rolling windows spend the first
  minute filling, so the distracted session reads 74.9 as a mean but settles at 33-51. Over
  a real 25-minute session this does not matter. Do not quote 74.9 as "distracted scores 75".
- The fatigue flag has only ever been tested against *acted* drowsiness (§14.2). It fires on
  a performance of tiredness; whether it fires on the real thing is untested.
- Blink rate and yawns are measured and exported but deliberately excluded from the score,
  for reasons in §15.8. If the architect wants them in, that needs new measurements first.

---

## 2026-07-31 — Phase 3.1b: PERCLOS fixed, and the replay tests are real

**Did**
- The operator made the three 2-minute recordings and they are committed in
  `bench/replays/`. A face was visible **100%** of the time in all three — better than the
  50% the tests require.
- Measured what the eye numbers actually do across those four minutes of real behaviour,
  then fixed the bug the measurements pointed at. **The 0.80 cutoff was never the problem.**
  What was wrong is what we compared against it: MediaPipe's eye-blink score is a
  confidence value on an arbitrary scale, and we were treating it as "percentage of the eye
  that is covered". Those are different things, and on this phone that score tops out at
  0.73 — so a rule saying "count it when at least 80% closed" could never fire.
- Eye closure is now measured from the *shape of the eyelids*: how far the eye opening has
  closed compared with how wide it is when open. That is a real percentage, so the standard
  0.80 line means what it says. **The threshold was not changed** — we fixed the input.
- It is measured against **your own** open eye, learned during the same 5-second
  calibration that already learns your neutral head position. The three recordings
  calibrated to 0.274, 0.293 and 0.298 — close to each other, which is a good sign the
  calibration is stable, and different enough from a textbook value to be worth learning.
- Found and fixed a related weakness while testing: one frame could previously define what
  "open" means, so a session starting mid-blink would have read every closure as zero. Ten
  samples are now required. There is a test.

**Evidence**
- 66 `:core` tests pass locally, including the **three replay assertions that were skipped
  until today**. Whole-run numbers from the operator's recordings:

  | | focused | distracted | drowsy |
  |---|---|---|---|
  | face visible | 1.00 | 1.00 | 1.00 |
  | PERCLOS | 0.000 | 0.003 | **0.118** |
  | gaze on screen | **0.984** | 0.599 | 0.730 |
  | blinks/min | 9.4 | 22.4 | 7.4 |
  | long closures | 0 | 3 | **16** |
  | head movement | 0.4° | 9.4° | 3.5° |

- Before the fix, the PERCLOS row read 0.000 / 0.000 / 0.000 — see `docs/DECISIONS.md`.
- Analysis output committed at `bench/eye-scale-20260731.txt`, from the committed script
  `bench/analyze_eye_scale.py --sweep`. CI run: see the commit's `core-tests` job.

**Next**
- Phase 4: the focus score and the session dashboard. The table above is the input to it.
- Worth knowing for Phase 4: gaze alone cannot tell drowsy from distracted (0.730 vs 0.599,
  and shut eyes read as "not on screen"), so the score needs more than one signal.
- Operator, when convenient: install the next build and confirm PERCLOS now moves on-screen
  when you close your eyes. Nothing depends on it — the same code path is already proven by
  the replay tests — but it closes the loop on the original report.

**Risks**
- **Our PERCLOS reads low, deliberately.** MediaPipe's face mesh does not fully close a
  shut eye, so the typical frame in a held closure reads 78% closed — just under the 80%
  line. The drowsy session scored 0.118 where the eyes were actually shut about 26% of the
  time. We chose to under-report rather than loosen the line to a number picked to make our
  own output look better. Phase 4 must not treat 0.118 as "only 12% drowsy".
- At ~9 fps an ordinary blink is one or two frames, so PERCLOS on this phone measures
  sustained closures rather than blinking. Blink rate covers the rest.
- **Yawn detection does not work on this device and is not fixed.** Two real yawns were
  recorded and zero detected: `jawOpen` peaked at 0.612 against a 0.60 line that must be
  held 1.2 s. It is the same bug as PERCLOS in a different signal, but the geometric fix
  needs mouth landmarks, which we deliberately do not collect for privacy reasons. That
  trade is the architect's call, not ours. Yawns are already first on the cut list.

---

## 2026-07-31 — Phase 3.1: Eye diagnostic (PERCLOS reads 0.000 on the phone)

**Did**
- The operator reported that PERCLOS stays at exactly `0.000` on the A20e even while
  holding both eyes shut for a full minute. Read the whole path — the mapper that receives
  MediaPipe's output, the eye-closure formula, and the rolling time window that produces
  PERCLOS. The time-window maths is correct, so the problem is upstream of it: either the
  eyes are never scored at all, or the score never gets high enough to count as "closed".
- Rather than guess a new threshold, added a **temporary diagnostic** to the camera screen
  (build `0.3.1-eyediag`): two extra lines showing the two raw eye-blink values exactly as
  MediaPipe reports them, their average (the number PERCLOS tests against 0.80), the
  eye-shape measurement from the lid landmarks, how many blendshapes arrived, and the
  extreme values seen. Tapping the panel resets the extremes so one thing can be measured
  at a time. The same numbers go to logcat.
- Wrote down the open question honestly in `docs/SIGNALS.md` §5.1: the 0.80 cutoff is
  borrowed from research that measures how much of the eye is physically covered, while we
  feed it a model confidence score. Those two scales were never checked against each other.
- **No threshold was changed.** Changing it before measuring would be inventing a number.

**Evidence**
- Symptom: operator confirmed — PERCLOS `0.000` on-screen during a deliberate one-minute
  eye closure, A20e, 2026-07-31.
- Diagnostic build compiles: `./gradlew assembleDebug` succeeded locally
  (`app/build/outputs/apk/debug/app-debug.apk`, 33.8 MB, versionName `0.3.1-eyediag`).
- **Answer, operator confirmed on the A20e, 2026-07-31** — eyes open: L 0.16, R 0.09,
  average 0.12, EAR 0.26. Eyes held shut 15 s, peaks: L **0.79**, R 0.66, average
  **0.73**, EAR down to **0.02** (open extreme 0.28). `bs 13`, and PERCLOS reported
  `0.000 over 60 s of measurable time`.
- Reading it: the plumbing is fine (all 13 values arrive) and the engine is fine (it
  measured a full minute and correctly found no time above the cutoff). The cutoff itself
  is unreachable — 0.73 never gets to 0.80. It is not the two-eye averaging either: the
  more closed eye alone stopped at 0.79. Meanwhile the landmark-based eye-shape
  measurement separated open from closed by a factor of 14, so it is the stronger
  candidate for what PERCLOS should be reading. Recorded in `docs/SIGNALS.md` §5.1.
- Still `NOT MEASURED YET`: the *typical* value during a sustained closure. Both numbers
  above are the single most extreme frame in 15 seconds, and a cutoff picked from an
  extreme misses just as badly as one picked from a paper.

**Next**
- Operator does the three labelled 2-minute recordings (`docs/SIGNALS.md` §12). The
  `drowsy` one is twelve 2-second closures, which is exactly the distribution needed. The
  current build records them correctly — PERCLOS showing 0.000 on screen does not affect
  what is stored.
- Then `bench/analyze_eye_scale.py` (committed, ready) prints the open- and closed-eye
  distributions from those files, the cutoff comes from that, and the replay ordering
  assertions in CI go green in the same commit as the fix.

**Risks**
- The two candidate measures may overlap — if the eye-blink score during a closure
  sometimes dips into the range it occupies while open, no clean cutoff exists on that
  measure and we would have to move PERCLOS onto the eye-shape measurement instead. The
  analysis script prints a WARNING when it sees that, and we would report it rather than
  tune a number until it looks good.
- The eye-shape measurement is computed from an eye that is only a handful of pixels tall
  at 640x480, so it may be noisier over two minutes than it looked over one 15-second test.
  The distributions will show this.
- PERCLOS shows a known-wrong 0.000 on the phone until this is fixed. Left visible on
  purpose; §12 now tells the operator to expect it, and `long closures` is the live check.
- The diagnostic lines make the panel taller. They come out once the question is settled.

---

## 2026-07-28 — Phase 3: Signals in :core + replay tests

**Did**
- Built the `:core` module: pure Kotlin, zero Android code, so the same maths runs on the
  phone and on a plain computer. It turns raw face numbers into meaning: how closed the
  eyes are, blinks and blink rate, **PERCLOS** (the share of the last minute the eyes were
  at least 80% shut — the standard drowsiness measure), long eye closures, whether you are
  looking at the screen, how steady your head is, and yawns.
- Everything is explained in plain language in **docs/SIGNALS.md**, including every single
  threshold and *why* it has the value it has, plus the exact recording protocol.
- The app learns **your own** neutral head position in the first 5 seconds of a session,
  because "facing the screen" depends entirely on where the phone sits on its stand. All
  gaze thresholds are measured from that, not from an assumed straight-ahead.
- Everything is measured in **time, not frames**. This matters: Phase 6 will deliberately
  slow the camera down to save battery, and without this, our own optimisation would have
  changed our own measurements.
- Added a **recorder** to the Camera screen: pick a label (focused / distracted / drowsy),
  tap REC, and it saves 2 minutes of *numbers only* — no video, no images, no way to
  reconstruct a face — then stops itself automatically. A Share button sends the file off
  the phone. Added a **live signals panel** showing every value as it happens.
- Tightened privacy further: of MediaPipe's 52 facial values we now keep only 13 (eyes and
  jaw); of its 478 face points, 14. The rest are discarded before they reach memory. That
  turns "we do not do emotion recognition" into a fact about the code.
- New CI job **core-tests** running on GitHub's Arm machine. It runs `:core` only — the job
  actively fails if the Android part of the build sneaks onto that machine, which is a
  rule in CLAUDE.md.

**Evidence**
- Green CI run, all three jobs (`build-apk`, `core-tests`, `arm-probe`):
  https://github.com/Biethe/FocusForge/actions/runs/30377142620 — the `core-tests` job runs
  on `ubuntu-24.04-arm` and its log opens by printing that runner's CPU Features line
  (`asimddp`, `i8mm`, `sve2` — the modern-Arm half of our two-device story), then the test
  results, then "Only :core was built on this runner, as required."
- **60 tests pass, 4 skip.** The 4 skipped ones are the replay assertions over the
  operator's real recordings; they report `NOT MEASURED YET` and will not pass on invented
  data. Their output is in the `core-test-report` artifact of that run.
- The 60 passing tests include: blink hysteresis (a wobbling score must not emit fake
  blinks), PERCLOS unchanged between 30 fps and 5 fps, PERCLOS with a missing face,
  the head-pose matrix layout, calibration against an outlier, and ordering assertions
  over three *generated* streams (synthetic PERCLOS focused=0.040 vs drowsy=0.188;
  gaze-on-screen focused=0.960 vs distracted=0.385 — printed in the CI log).
- A real bug the tests caught and we fixed: the first version assumed reading the head
  matrix the "wrong way round" only flipped signs. It does not — 20.0° read back as 17.9°.
  We now detect the layout from the data instead. See docs/DECISIONS.md.
- New APK on the rolling release: https://github.com/Biethe/FocusForge/releases/tag/dev-latest
  (33 MB, was 32 MB — the +1 MB is the JSON library). Permissions still CAMERA only.
- On-phone signal behaviour: NOT MEASURED YET — operator does the recordings next.
- Phase 2 HUD numbers (fps / inference ms / RSS): still NOT MEASURED YET.

**Next**
- Operator: install the new APK, watch the signals panel (close your eyes → PERCLOS climbs;
  look away → gaze flips to OFF), then make the three 2-minute recordings following
  docs/SIGNALS.md §12 and send the files back. Once they are committed, the 4 skipped tests
  turn into real assertions and Phase 3 is fully closed.
- Then Phase 4: fuse these signals into one 0–100 focus score with a session screen.

**Risks**
- The recordings are the gate. Until they exist we have proven the pipeline separates the
  three behaviours on data we generated ourselves — not on a real face. That is a genuine
  gap and it is marked as such rather than papered over.
- If MediaPipe's blink scores turn out to be weak on the A20e's fixed-focus front camera,
  the eye-aspect-ratio fallback takes over automatically, but its two reference values are
  literature typicals, not calibrated to the operator. If the recordings show that, we
  calibrate them per-user in Phase 4.
- Gaze uses head direction plus horizontal eye position only. Deliberately no vertical eye
  tracking: at 640×480 and desk distance that number would be noise. Looking up and down is
  caught by head pitch, which is fine for a phone on a stand but would not be for glasses.

---

## 2026-07-28 — Phase 2: Camera + face landmarks + perf HUD

**Did**
- Added the "Camera" screen (opened by a new button on the Probe screen): the front camera
  runs at 640×480 and every frame goes through MediaPipe's FaceLandmarker, which finds 478
  face points plus "blendshapes" (eye-open/eye-closed style values we'll use in Phase 3).
  The points are drawn live over the camera preview in green.
- Added the always-on performance HUD at the top of that screen: camera frames per second,
  average landmark-detection time in milliseconds, and app memory (RSS) in MB. The bottom
  of the screen shows the last 8 one-per-second samples with timestamps — screenshot that
  area for the record. The same lines also go to the Android debug log.
- The AI model file (3.76 MB) is downloaded automatically when the app is built — on the PC,
  in CI, or via scripts/get_models.sh — and is never committed to git. It ships inside the
  APK, so the phone never needs a network connection.
- Caught and fixed a privacy-rule violation: the MediaPipe library tried to add INTERNET
  and network-state permissions to our app. They are now forcibly stripped, and CI fails
  any build whose APK contains a permission other than CAMERA.

**Evidence**
- Green CI run: https://github.com/Biethe/FocusForge/actions/runs/30371328429 — its
  "Verify permissions" step prints the full permission dump (CAMERA only, no INTERNET).
- New APK on the rolling release: https://github.com/Biethe/FocusForge/releases/tag/dev-latest
- Dependency licenses + the INTERNET-stripping and APK-size decisions: docs/DECISIONS.md.
- APK size is now 32 MB (was 2.3 MB) — almost entirely MediaPipe's native library + model;
  recorded honestly as our vision-stack baseline.
- On-phone fps / inference ms / RSS: NOT MEASURED YET — operator runs it next.

**Next**
- Operator: update the app, grant camera permission, check the mesh sticks to the face,
  write down the HUD numbers (fps, ms, RSS) and screenshot them — that's our vision
  baseline for the optimization story. Then Phase 3: signals in :core + replay tests.

**Risks**
- MediaPipe on the A20e's CPU is untested; if fps is well below 8 or inference far above
  ~100 ms, that becomes the first optimization target (it's a finding, not a failure).
- The landmark overlay math assumes portrait orientation (the screen is locked to
  portrait on purpose); if points look offset, report it with a screenshot.

---

## 2026-07-28 — Phase 1: Walking-skeleton APK + silicon probe

**Did**
- Built the first installable app. One screen ("Probe") that shows, refreshed every second:
  phone model, Android version, supported ABIs, CPU core count grouped into clusters by their
  max frequency, the raw CPU Features line with a clear YES/NO verdict for `asimddp` and
  `i8mm`, total RAM, and thermal status (on Android 10+). A "Share probe JSON" button exports
  everything through the normal Android share menu.
- Kept it deliberately tiny: no libraries at all beyond the Kotlin standard one. The APK is
  **2.3 MB** (the red-flag line was 30 MB). The manifest declares **zero permissions** — CI
  now fails the build if any permission ever sneaks in.
- CI now really builds the APK on every push and publishes it to a rolling "dev-latest"
  pre-release so the operator can download it straight from the phone's browser.
- Installed a local build toolchain on the PC (JDK 17, Gradle 8.7, Android SDK 34 in the
  home folder) so compile errors are caught before pushing; CI remains the official builder.

**Evidence**
- Green CI run (both jobs): https://github.com/Biethe/FocusForge/actions/runs/30367768698
- APK download page: https://github.com/Biethe/FocusForge/releases/tag/dev-latest
- The same CI run's log ("Verify no INTERNET permission" step) shows the aapt2 permissions
  dump containing no permission lines.
- On-phone numbers (clusters, features, RAM as the A20e reports them): NOT MEASURED YET —
  waiting for operator to install and share the probe JSON.

**Next**
- Operator installs the APK on the A20e, checks 8 cores / 2 clusters and NO asimddp/i8mm,
  and shares the probe JSON to the architect chat. Then Phase 2: CameraX + MediaPipe
  FaceLandmarker with the perf HUD.

**Risks**
- Sideloading warnings (Play Protect) can look scary — instructions below explain exactly
  what to expect so nothing is tapped in panic.
- Thermal status needs Android 10+; if the A20e is still on Android 9 the app will say so
  honestly rather than show a number.
- The probe reads `/sys/.../cpuinfo_max_freq`; a few devices block this. The app shows
  "?" instead of guessing if that happens — report it if you see it.

---

## 2026-07-28 — Phase 0: Repo and rails

**Did**
- Turned the planning folder into a real project: added the MIT license, a .gitignore that
  guarantees model weights and build junk are never committed, empty templates for this log and
  the decision log, and a scripts/ folder. Moved the planning docs (PROMPTS, RESOURCES,
  SUPERVISION) into docs/ where the constitution expects them.
- Connected the folder to the GitHub repo **FocusForge** (it already existed on the Biethe
  account from an earlier attempt, with unrelated stub history — merged, nothing lost) and made
  it **public**, which the hackathon requires and which gives us free Arm build machines.
- Added the first CI workflow: a placeholder build job, plus an "arm-probe" job that runs on
  GitHub's Arm machine and saves a report of its processor.
- Verified CI runs green and downloaded the probe report: the CI machine is a **Neoverse-N2**
  and its Features line contains `asimddp`, `i8mm`, and `sve2` — the modern-Arm half of our
  two-device story (the phone has none of these).

**Evidence**
- Public repo: https://github.com/Biethe/FocusForge (opens without login; About shows MIT).
- Green CI run: https://github.com/Biethe/FocusForge/actions/runs/30365607803
- Probe report committed at `bench/probes/ci-neoverse-n2-de5f784.txt` (from that run's
  `probe-ci` artifact). Check the "Features" line for `asimddp` and `i8mm`.
- Phone-side numbers: NOT MEASURED YET (no app exists yet — that's Phase 1).

**Next**
- Phase 1: scaffold the Android app with the single "Probe" screen, build the APK in CI,
  publish it to a rolling "dev-latest" pre-release, and the operator installs it on the
  Samsung A20e.

**Risks**
- The GitHub login on this PC stores credentials in plain text (gh's default here) — fine for
  a hackathon, but don't reuse that account password anywhere sensitive.
- Two leftover repos exist on the account (`ArmFatigue`, old name) — harmless, but deleting
  it later avoids confusion. This folder now pushes to FocusForge only.

---

<!-- Template for each entry:

## YYYY-MM-DD — Phase N: <title>

**Did**
- ...

**Evidence**
- ...

**Next**
- ...

**Risks**
- ...

-->
