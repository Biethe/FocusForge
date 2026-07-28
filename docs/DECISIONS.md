# Decision Log

> One entry per technical decision, dependency, or optimization, newest first.
> Every new dependency gets a line here with its license (MIT / Apache-2.0 / BSD only).
> Every optimization gets before/after numbers from committed benchmark files —
> negative results are kept and reported honestly.

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
