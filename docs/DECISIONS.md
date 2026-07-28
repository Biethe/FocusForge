# Decision Log

> One entry per technical decision, dependency, or optimization, newest first.
> Every new dependency gets a line here with its license (MIT / Apache-2.0 / BSD only).
> Every optimization gets before/after numbers from committed benchmark files —
> negative results are kept and reported honestly.

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
