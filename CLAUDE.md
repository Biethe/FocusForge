# FocusForge — Project Constitution

> This file is read automatically by Claude Code at the start of every session.
> It is the single source of truth. If a request conflicts with this file, say so before acting.
> The human operator is NOT an Android or ML expert. The architect (a separate Claude chat) wrote
> this file and reviews progress via `docs/PROGRESS.md`. You are the builder.

**STATUS: Phase 4.5 complete and validated against ground truth (2026-08-01). The operator's 30 s blink probe (10 performed blinks + 3 one-second closures, committed at bench/replays/blinkprobe-*.json) confirms the retune: old thresholds found 3 of 10 blinks, the split thresholds find 6 of 10, and 3 of 3 slow closures either way. The 0-blink session is fully explained — old threshold plus 1 Hz export sampling; the open-eye calibration hypothesis is REFUTED (operator read 0.31 live, probe calibrated 0.278). 91 :core tests pass. NEW KNOWN LIMIT (docs/SIGNALS.md §16.7): we miss ~40% of blinks because a 120-290 ms blink can fall entirely between frames at 8.4 fps. Blink counts are reported as a floor and are NOT corrected. ARCHITECT DECISION NEEDED before Phase 6: blink rate and frame rate cannot both be optimised, and Phase 6 exists to lower the frame rate; PERCLOS, long closures and the focus score are unaffected. Also outstanding: Phase 2 HUD numbers. Next: Phase 5 — on-device LLM coach.** (Update this line at the end of every session.)

## 1. What we are building

A fully on-device **focus & fatigue coach** for Android: the phone sits on a stand facing the user
while they study or work. A camera pipeline extracts behavioral attention signals (blinks, PERCLOS,
gaze-on-screen, head-pose stability), a fusion layer produces a live focus score, and a small local
LLM generates short, supportive coaching messages. **Nothing ever leaves the device.**

This is our entry to the **Arm AI Optimization Challenge 2026, Track 3 (Mobile AI)**.
- Hackathon: https://arm-ai-optimization-challenge.devpost.com/
- Hard deadline: **Aug 14, 2026, 4:00 pm PDT** (= Aug 15, 1:00 am in France).
- Our target submission date: **Aug 12, 2026**.
- Judged on: Technological Implementation (40), UX/DX (15), Impact (20), WOW (25).
- The submission story is **optimization**: every feature exists to produce measured
  before/after numbers on real Arm silicon (size, speed, latency, memory, battery).

## 2. Ground-truth hardware (never assume, always probe)

Primary device — operator's phone: **Samsung Galaxy A20e (SM-A202F/DS)**
- SoC: Exynos 7884B — big.LITTLE: **2× Cortex-A73 @ ~1.6 GHz + 6× Cortex-A53 @ ~1.35 GHz**
- RAM: **3 GB** (our app must stay under **~700 MB RSS**)
- ABI: arm64-v8a only. ISA: **NEON only — NO dotprod, NO i8mm, NO SVE.**
- Android 9–11 (sideloaded APKs; Play Store policies do not apply). Storage: 32 GB.
- Battery 3000 mAh, front camera 8 MP fixed-focus. Assume desk distance ~40–70 cm.

Secondary device — CI: GitHub-hosted `ubuntu-24.04-arm` runner (Neoverse N2 class:
**has** dotprod, i8mm, SVE2). This is our "modern Arm" data point. Silicon may vary between
runs — always log `/proc/cpuinfo` with every benchmark.

The contrast between these two devices IS the story: one codebase that probes its silicon at
runtime and adapts (model size, kernels, threading) across seven years of Arm CPUs.
- Big cluster = cores 6-7 (A73 @ 1.56 GHz); little cluster = cores 0-5 (A53 @ 1.35 GHz).
  Use these exact core IDs for Phase 6 thread affinity.
- Features confirmed: fp asimd aes sha1 sha2 crc32 ONLY. Armv8.0-A baseline:
  no dotprod, no i8mm, no SVE, no LSE atomics, no fp16 arithmetic (no fphp/asimdhp).
- HARD RULE: all native code, llama.cpp included, compiles with -march=armv8-a baseline.
  Never copy armv8.2+/dotprod/i8mm flags from llama.cpp Android examples — they SIGILL
  on this device. Verify the LLM actually runs on-device before Phase 5 is "done".
- Usable RAM is 2.68 GiB. RSS budget ≤700 MB hard, ≤600 MB target.

## 3. Architecture (fixed — do not restructure without architect approval)

```
:app        Android app (Kotlin, minSdk 28, targetSdk 34, abiFilters arm64-v8a only)
:core       Pure Kotlin/JVM module — ALL signal logic. Zero Android imports. Unit-testable on any JVM.
native/     C++/JNI: llama.cpp integration (git submodule or pinned checkout)
bench/      Benchmark scripts + committed result JSONs (results are append-only; never edit old results)
models/     .gitignored. Weights are NEVER committed. Fetched by scripts/get_models.sh or sideloaded.
docs/       PROGRESS.md, DECISIONS.md, SIGNALS.md, PROMPTS.md, SUPERVISION.md, RESOURCES.md, SUBMISSION.md
.github/    CI workflows (see CI split rule)
```

Technology choices (locked): CameraX for camera; **MediaPipe Tasks FaceLandmarker** (Apache-2.0)
for landmarks + blendshapes + head pose; **llama.cpp** (MIT) via its Android example pattern for
the LLM; **SmolLM2-360M-Instruct, Q4 GGUF** (Apache-2.0) as the coach model. Alternates require an
entry in `docs/DECISIONS.md` and architect sign-off via the operator.

## 4. The five hard rules

1. **Evidence rule.** Never state a number or a "works" claim without an artifact: a CI run link, a
   committed script plus its output file, or an operator-confirmed screenshot. If not measured yet,
   write exactly `NOT MEASURED YET`. Never estimate a benchmark. Negative results (an optimization
   that didn't help) are kept and reported honestly.
2. **License rule.** Dependencies: MIT / Apache-2.0 / BSD only. Repo license: MIT. Every new
   dependency gets a line in `docs/DECISIONS.md` with its license. Model weights and dataset files
   are never committed. Engagement datasets (DAiSEE, EngageWild, etc.) are research-only licensed:
   we do NOT train on them, download them, or include them.
3. **Privacy rule.** The app manifest declares **no INTERNET permission — ever**. The model file is
   imported via the system file picker. Camera frames are processed in memory and discarded; only
   derived numbers (landmarks, signals, scores) may be logged, and only when the operator toggles
   it. We compute attention/fatigue **behavioral signals only**. Never use the words "emotion
   recognition" in code, docs, or UI; do not add emotion classification. (EU AI Act Art. 5 bans
   emotion inference in education/workplace contexts; our framing is a self-coaching tool the user
   runs on themselves, and we keep it that way.)
4. **CI split rule.** APKs are built on `ubuntu-latest` (x64) — the NDK cross-compiles arm64 native
   code there. Performance jobs (:core JVM tests, landmark-replay tests, llama-bench) run on
   `ubuntu-24.04-arm`. Never attempt to install the Android SDK on the arm64 runner. The repo must
   be **public from day 0** (arm64 runners are free only on public repos, and the hackathon
   requires a public repo).
5. **Operator rule.** End every session by: updating `docs/PROGRESS.md` with a plain-language entry
   (sections: **Did / Evidence / Next / Risks** — write for a non-expert), updating the STATUS line
   at the top of this file, committing with a clear message, and pushing. When the operator must do
   something on the phone or PC, give exact step-by-step instructions (assume no prior knowledge).
   Only the operator installs APKs and runs on-phone tests.

## 5. Working style

- One phase per session (phases live in `docs/PROMPTS.md`). Small commits after each working step.
- Propose a plan and wait for operator GO before large changes; prefer the smallest change that
  passes the phase's verification.
- If blocked >45 minutes on the same error: stop, summarize the error in plain language in
  PROGRESS.md, and tell the operator to bring it to the architect. Do not thrash.
- Performance targets are goals, not claims: vision loop ≥8 fps sustained; coach TTFT ≤3 s and
  ≥5 tok/s decode on the A20e; app RSS ≤700 MB. Record actuals, whatever they are.
- Default optimization posture for the A20e: vision work scheduled toward the A53 (little) cluster;
  LLM generation on 2 threads toward the A73 (big) cluster; duty-cycle the camera pipeline when
  signals are stable. Every optimization lands as its own commit with before/after numbers in
  `docs/DECISIONS.md`.
