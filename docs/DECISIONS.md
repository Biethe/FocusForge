# Decision Log

> One entry per technical decision, dependency, or optimization, newest first.
> Every new dependency gets a line here with its license (MIT / Apache-2.0 / BSD only).
> Every optimization gets before/after numbers from committed benchmark files —
> negative results are kept and reported honestly.

## 2026-07-31 — Phase 4: the focus score is a weighted sum, and the camera pipeline is shared

- **What:** `FocusScorer` in `:core` fuses three of the Phase 3 signals into 0-100 with a
  fatigue flag. Weights 0.50 attention / 0.30 alertness / 0.20 steadiness; anchors taken
  from the measured ranges of the three recordings; 8 s exponential smoothing whose
  coefficient is derived from the real frame gap; Schmitt trigger (0.60 on / 0.35 off) with
  15 s and 30 s dwell times for the flag. Full rules in docs/SIGNALS.md §15.
- **Why a weighted sum rather than anything cleverer:** it is the only form where the
  contribution of each signal can be read off the constants and asserted in a test, and
  where the coach can later say *why* the score is what it is. With one person's data and no
  ground truth, anything fitted would be fitting noise.
- **Alternative rejected — a multiplicative score** (`attention x alertness x steadiness`):
  any single term at zero zeroes the whole score, so glancing away for one window would read
  the same as falling asleep. The information about *which* axis failed is exactly what the
  coaching message needs.
- **Blink rate is deliberately excluded**, despite §14.3 showing it was the only signal that
  isolated the distracted session — it did so by 13/min against 12/min. A one-blink margin
  on one recording is not a term, it is noise with a name. Measured and exported, not scored.
- **Evidence:** replayed over the three committed recordings, mean scores are focused 96.7,
  distracted 74.9, drowsy 60.7, with the fatigue flag raised for 66% of the drowsy session
  and never in the other two. Asserted in CI (`RecordedFocusScoreTest`), plus 18 synthetic
  tests over the fusion and the hysteresis.
- **Known limitation, documented not fixed:** those means are inflated by the 60 s windows
  filling during the first minute of a 2-minute clip. Distracted settles at 33-51.

## 2026-07-31 — Phase 4: FacePipeline extracted so two screens share one camera path

- **What:** camera setup, MediaPipe configuration, frame rotation/mirroring and the result
  callback moved out of `CameraActivity` into `FacePipeline`, now used by both it and the
  new `SessionActivity`.
- **Why:** the session screen needs precisely the same pipeline. The alternative was a
  second copy of ~120 lines including the 640x480 resolution rule and the mirroring maths —
  two copies that would drift, and only one of which anyone would remember to fix.
- **Risk accepted:** this refactors code that was working and is the operator's diagnostic
  tool. It compiles and the behaviour is line-for-line identical, but it is untested on the
  phone until the operator installs 0.4.0. Flagged in PROGRESS as the thing to check first.

## 2026-07-31 — Phase 3.1: eye closure is measured geometrically, not from a confidence score

- **What:** `eyeClosure` now comes from the eye aspect ratio of the lid landmarks, divided
  by the user's own calibrated open-eye ratio (`1 - EAR/EAR_open`). MediaPipe's `eyeBlink`
  blendshape is demoted to the fallback for frames with no lid points. `BaselineCalibrator`
  learns `earOpen` alongside the neutral pose; `EAR_CLOSED_REF` is deleted.
- **Why:** `PERCLOS_CLOSED_LEVEL = 0.80` was being applied to a model confidence as though
  it were a percentage of eyelid aperture. On the A20e that score peaks at 0.73 with the
  eyes fully shut, so PERCLOS was structurally incapable of reading anything but 0.000.
- **Before / after**, over the operator's three committed 2-minute recordings
  (`bench/replays/`, analysis in `bench/eye-scale-20260731.txt`):

  | PERCLOS | focused | distracted | drowsy |
  |---|---|---|---|
  | before (blendshape @ 0.80) | 0.000 | 0.000 | 0.000 |
  | after (aperture @ 0.80) | 0.000 | 0.003 | 0.118 |

  Three replay ordering assertions went from skipped to passing.
- **The threshold did not change.** 0.80 is still the literature's P80, unfitted. Only the
  quantity it is applied to changed, which is why this is a bug fix and not a tuning.
- **Alternative rejected:** keeping the blendshape and lowering the cutoff to ~0.55–0.65.
  It would have worked numerically (drowsy 0.18–0.31) but the number would have been fitted
  to one face on one phone, and the sweep showed the open and closed populations *overlap*
  on the distracted recording — open p95 0.482 against closed p5 0.482 — so no cutoff on
  that measure separates them there. We would also have had to stop calling it P80.
- **Alternative rejected:** loosening to `aperture >= 0.70` to compensate for MediaPipe's
  mesh not fully collapsing a shut eye (which makes our PERCLOS read low — 0.118 where the
  eyes were geometrically closed 26% of the time). Rejected as fitting a number to make our
  own output look better. We report the conservative bias instead (docs/SIGNALS.md §5.1).
- **Negative result kept:** the same class of bug affects yawns and is *not* fixed.
  `jawOpen` peaked at 0.612 against a 0.60 cutoff needing 1.2 s, so two real yawns detected
  as zero. The geometric fix needs mouth landmarks, which the privacy allow-list excludes
  by design — that requires architect sign-off, not a unilateral edit (docs/SIGNALS.md §9).

## 2026-07-28 — Phase 3: kotlinx-serialization-json for the recording format

- **What/License:** org.jetbrains.kotlinx:kotlinx-serialization-json 1.6.3 (Apache-2.0),
  plus the matching Kotlin serialization compiler plugin 1.9.24 (Apache-2.0).
- **Why:** the landmark recording format is defined once, in `:core`, and read back
  byte-identically by the replay tests. A hand-rolled parser would have been a second,
  silently divergent definition of the same schema. Cost: debug APK 32 MB → 33 MB.
- **Alternative rejected:** `org.json` (free on Android) — unavailable on a plain JVM, so
  the replay tests could not have used the same code path as the app.

## 2026-07-28 — Phase 3: head-pose matrix layout detected, not assumed

- **What:** MediaPipe returns the facial transformation matrix as 16 floats without
  documenting row- vs column-major. We detect it per frame from where the translation sits
  (fourth column = row-major, fourth row = column-major) rather than picking one.
- **Why:** reading a combined yaw/pitch/roll rotation transposed is a genuinely different
  pose, not the same angles negated — an earlier version of this code assumed otherwise and
  a unit test caught it (20.0 deg read back as 17.9 deg). Detection removes the assumption
  entirely; a test writes the same pose in both layouts and asserts identical angles.

## 2026-07-28 — Phase 3: `-PcoreOnly` keeps the Android SDK off the arm64 runner

- **What:** plugin versions moved from the root build script into `settings.gradle.kts`
  (`pluginManagement.plugins`), and `:app` is only included when `-PcoreOnly` is absent.
  The `core-tests` CI job runs `./gradlew -PcoreOnly :core:test` on `ubuntu-24.04-arm`.
- **Why:** CLAUDE.md §4.4 forbids installing the Android SDK on the arm64 runner. With
  `apply false` in the root script, Gradle would still resolve the Android Gradle Plugin on
  that runner; this arrangement means it is never even referenced. The job proves it by
  running `gradlew projects` and failing if `:app` appears.

## 2026-07-28 — Phase 3: blendshape and landmark allow-lists

- **What:** of MediaPipe's 52 blendshapes we keep 13 (eyes + jaw); of its 478 landmarks we
  keep 14 (eye contours + iris centres). Everything else is dropped in `SignalMapper.kt`
  before it reaches memory, the recorder, or a file.
- **Why:** most of the remaining blendshapes describe facial expression (brows, cheeks,
  mouth corners). Keeping them out of the code makes "we do not do emotion inference"
  (CLAUDE.md §4.3, EU AI Act Art. 5) a property of the implementation rather than a promise.
  It also shrinks recordings to ~0.5 MB per 2 minutes, small enough to commit as fixtures.

## 2026-07-28 — Phase 2: CameraX + MediaPipe FaceLandmarker

- **What/License:** androidx.camera (core/camera2/lifecycle/view) 1.3.4 (Apache-2.0),
  androidx.activity 1.9.0 (Apache-2.0), com.google.mediapipe:tasks-vision 0.10.14
  (Apache-2.0). Model: face_landmarker.task float16, pinned URL version /1/, fetched at
  build time by the :app:downloadModels gradle task or scripts/get_models.sh — never
  committed (3.76 MB, bundled as an asset so the app needs no network at runtime).
- **Why:** locked technology choices per CLAUDE.md §3. CPU delegate (default) — the GPU
  delegate on the A20e's Mali-G71 MP2 is untested and our story is CPU optimization.

## 2026-07-28 — Phase 2: MediaPipe's INTERNET permission stripped at merge time

- **What:** tasks-vision's library manifest injects INTERNET + ACCESS_NETWORK_STATE into
  the merged APK. We remove both with `tools:node="remove"` in our manifest; CI now fails
  the build if any permission beyond CAMERA (+ the androidx app-private
  DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which grants no capability) appears.
- **Why:** privacy rule is absolute. Verified locally with aapt2: INTERNET is gone.

## 2026-07-28 — Phase 2: APK size 2.3 MB → 32 MB

- **What:** the debug APK grew to 32 MB, almost entirely MediaPipe's
  libmediapipe_tasks_vision_jni.so (arm64) + the bundled 3.76 MB model.
- **Why accepted:** it is the locked landmark stack; no smaller official build exists.
  Recorded honestly — this is our vision-stack size baseline for the optimization story.

## 2026-07-28 — Phase 1: zero-dependency Probe app

- **What:** The Probe screen is plain Kotlin `android.app.Activity` with programmatic views —
  no AndroidX, no Compose, no runtime dependencies beyond the Kotlin stdlib.
- **Why:** Smallest possible first APK (measured 2.3 MB debug) and nothing to license-audit.
  AndroidX/CameraX arrive in Phase 2 when they are actually needed.

## 2026-07-28 — Phase 1: build tooling (not shipped in the APK beyond stdlib)

- **What/License:** Android Gradle Plugin 8.5.2 (Apache-2.0), Kotlin 1.9.24 + stdlib
  (Apache-2.0), Gradle 8.7 (Apache-2.0). CI actions: actions/checkout, setup-java,
  gradle/actions, upload-artifact (MIT), softprops/action-gh-release (MIT).

## 2026-07-28 — Phase 1: committed debug keystore

- **What:** `app/debug.keystore` (standard Android debug password, debug builds only)
  is committed.
- **Why:** CI generates a fresh keystore per run otherwise; the signature would change on
  every build and the phone would refuse to update the app without uninstalling first.
  One committed debug key = install once, update forever. Never used for release signing.

<!-- Template for each entry:

## YYYY-MM-DD — <short title>

- **What:** ...
- **Why:** ...
- **License (if dependency):** ...
- **Before/after (if optimization):** ... (link to bench/results/ files)

-->
