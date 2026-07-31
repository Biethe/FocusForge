# FocusForge — Resources (everything is free; total budget: €0)

## Hackathon

- Main page: https://arm-ai-optimization-challenge.devpost.com/
- Track details (Track 3 = Mobile AI): https://arm-ai-optimization-challenge.devpost.com/details/trackdetails
- Rules: https://arm-ai-optimization-challenge.devpost.com/rules — read once yourself.
- Resources/updates: https://arm-ai-optimization-challenge.devpost.com/resources — Arm runs
  workshops and office hours during the challenge; check Updates weekly. Questions: devevang@arm.com.
- Deadline: Aug 14, 2026, 4:00 pm PDT (Aug 15, 1:00 am in France). We submit Aug 12.

## Models and frameworks (license is the first column for a reason)

| License | What | Where | Notes |
|---|---|---|---|
| Apache-2.0 | **SmolLM2-360M-Instruct** (our coach) | Search Hugging Face: `SmolLM2-360M-Instruct GGUF` (HuggingFaceTB original; community GGUF builds e.g. bartowski) | Q4_K_M ≈ 250–300 MB. Download on PC, transfer to phone, import in-app. Never commit. |
| Apache-2.0 | Qwen2.5-0.5B-Instruct (fallback if quality disappoints) | Search HF: `Qwen2.5-0.5B-Instruct GGUF` | Heavier; only with architect sign-off. |
| Gemma Terms (custom) | Gemma 3 270M (alternate) | Search HF: `gemma-3-270m GGUF` | Usable but NOT Apache — keep out of the repo entirely; mention only as "also compatible". |
| Apache-2.0 | **MediaPipe Tasks FaceLandmarker** (vision) | https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker | 478 landmarks + blendshapes + head pose; the `.task` model downloads from the docs page; fetch via script, don't commit. |
| MIT | **llama.cpp** (LLM runtime + llama-bench) | https://github.com/ggml-org/llama.cpp | Contains an Android example app — our integration template. Pin an exact commit. |

Datasets: DAiSEE, EngageWild and similar engagement datasets are **research-only** — we neither
train on nor redistribute them. Validation uses your own recorded labeled sessions (Phase 3).
The three papers from the original project idea go in the README as background references.

## Arm learning paths the judges themselves point to (skim, don't study)

- Measure LLM performance with KleidiAI/SME2 on Android (methodology to imitate):
  https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/
- LLM inference on Android with MediaPipe + XNNPACK:
  https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/kleidiai-on-android-with-mediapipe-and-xnnpack/
- Android chat app with ExecuTorch (the alternate runtime, if llama.cpp JNI ever blocks us):
  https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/build-llama3-chat-android-app-using-executorch-and-xnnpack/
- GitHub Arm-hosted runners: https://learn.arm.com/learning-paths/cross-platform/github-arm-runners/

## Infrastructure (all free)

- GitHub `ubuntu-24.04-arm` runners: free and unlimited for **public** repos — 4 vCPU, 16 GB,
  Neoverse N2 class with dotprod/i8mm/SVE2. This is our "modern Arm" benchmark machine and the
  reason the repo is public from day 0.
  Reference: https://github.blog/changelog/2025-08-07-arm64-hosted-runners-for-public-repositories-are-now-generally-available/
- Optional third data point: `macos-15` runners are Apple Silicon (also Arm) — only if we're ahead
  of schedule.
- Arm Performix (https://developer.arm.com/servers-and-cloud-computing/arm-performix): Arm's new
  profiling toolkit, Neoverse/cloud-oriented. Stretch goal only: one profiling run of our :core
  replay on the arm64 runner or a cloud box, for a bonus screenshot. Do not let it block anything.

## Where the signal definitions come from

Read alongside `docs/SIGNALS.md` §14, which states plainly which of our signals have a
research basis (the drowsiness ones) and which are documented guesses (the attention ones).
We cite these for their *definitions*; none of them validate our implementation, which uses
a phone camera at ~9 fps where the studies used dedicated eye-tracking hardware.

- **PERCLOS** — Wierwille & Ellsworth (1994), and Dinges & Grace (1998), *PERCLOS: A Valid
  Psychophysiological Measure of Alertness as Assessed by Psychomotor Vigilance* (US FHWA
  report). The origin of the measure and of the P80 variant we implement.
- **Eye aspect ratio** — Soukupová & Čech (2016), *Real-Time Eye Blink Detection using
  Facial Landmarks*, CVWW. The EAR formula in SIGNALS.md §3 is theirs.
- Blink duration and rate figures in SIGNALS.md §4 are the standard ranges reported across
  the blink literature rather than any single paper, and we make no inference from them.

**Not used, deliberately:** engagement/affect datasets (DAiSEE, EngageWild and similar) are
research-licensed, and CLAUDE.md §4.2 forbids downloading, training on, or shipping them.
We do not train anything — every threshold here is hand-set and documented.

## Tools on your side

- **scrcpy** (https://github.com/Genymobile/scrcpy): mirrors and screen-records your phone from
  the PC — this is how we film the demo video. Needs USB debugging enabled (Developer options).
- **adb** — only three commands matter: `adb devices` (is the phone seen?), `adb install app.apk`
  (alternative to browser sideload), `adb logcat` (paste crashes to the builder).
- **GitHub CLI** (`gh`) and a **Temurin JDK 17** — the builder installs/uses these with you in
  Phase 0/1. Android Studio is NOT required.
- Phone prep recap: Developer options ON (tap Build number ×7), allow installs from browser/Files,
  ~2 GB free storage, a stand or improvised prop for the phone, charger for benchmark days.

## Submission proof artifacts to collect along the way (don't reconstruct them at the end)

1. `probe-ci.txt` (Phase 0) and the A20e probe JSON (Phase 1) — the two-silicon story.
2. Airplane-mode coaching screenshot (Phase 5) — the privacy proof.
3. The manifest permalink showing no INTERNET permission (Phase 5).
4. Baseline vs optimized benchmark JSONs + `docs/RESULTS.md` (Phase 6).
5. Green CI run links (every phase) and `bench/registry.md` (Phase 7).
