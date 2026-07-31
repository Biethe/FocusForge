# Progress Log

> One dated entry per session, newest first. Written in plain language for a non-expert.
> Every claim in **Evidence** must be a link (CI run, committed file) or
> "operator confirmed: &lt;what was seen&gt;". If something is not measured yet, it says
> `NOT MEASURED YET`.

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
