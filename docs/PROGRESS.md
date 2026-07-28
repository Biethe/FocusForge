# Progress Log

> One dated entry per session, newest first. Written in plain language for a non-expert.
> Every claim in **Evidence** must be a link (CI run, committed file) or
> "operator confirmed: &lt;what was seen&gt;". If something is not measured yet, it says
> `NOT MEASURED YET`.

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
