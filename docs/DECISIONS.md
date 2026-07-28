# Decision Log

> One entry per technical decision, dependency, or optimization, newest first.
> Every new dependency gets a line here with its license (MIT / Apache-2.0 / BSD only).
> Every optimization gets before/after numbers from committed benchmark files —
> negative results are kept and reported honestly.

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
