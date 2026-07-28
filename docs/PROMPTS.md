# FocusForge — Phase Playbook (prompts you paste into Claude Code)

How to use this file:
- Run **one phase per Claude Code session**. Open a terminal in the project folder, run `claude`.
- Start every session with the **Kickoff** prompt. End every session with the **Closeout** prompt.
- After the session, do the **You verify** steps yourself. You don't need to understand the code —
  every check is something you can see with your eyes.
- If a verification fails: paste the failure back to the agent once. If it fails again, stop and
  bring it to the architect chat.
- Tip: for Phases 2, 5 and 6, start the session in **Plan Mode** (press Shift+Tab until the
  indicator shows Plan) so the agent proposes before it edits.

---

## Kickoff (paste at the start of EVERY session)

```
Read CLAUDE.md and docs/PROGRESS.md. Then tell me, in 6 short bullets a non-expert understands:
(1) current state, (2) what the current phase requires (see docs/PROMPTS.md),
(3) your plan for this session, (4) what you will need from me and when,
(5) the main risk, (6) how we'll know the session succeeded.
Then WAIT for my "GO" before changing anything.
```

## Closeout (paste at the end of EVERY session)

```
Wrap up: (1) update docs/PROGRESS.md with a new dated entry using sections
Did / Evidence / Next / Risks, written in plain language for a non-expert — every claim in
Evidence must be a link (CI run, committed file) or "operator confirmed: <what I saw>";
(2) update the STATUS line in CLAUDE.md; (3) make sure CI is green or explain in PROGRESS.md
why not; (4) commit everything with clear messages and push; (5) list, as numbered steps,
anything I must do on my phone or PC before the next session.
```

---

## Phase 0 — Repo and rails (Day 1)

**Goal:** public GitHub repo, MIT license, CI skeleton proving we have Arm silicon on tap.

**Prompt:**
```
Phase 0. In this folder: initialize a git repo for the project described in CLAUDE.md.
Create: MIT LICENSE; a .gitignore for Android + a models/ ignore rule; docs/PROGRESS.md and
docs/DECISIONS.md (empty templates); scripts/ folder. Do NOT scaffold the Android app yet.
Then create a PUBLIC GitHub repo named "focusforge" using the gh CLI (walk me through
`gh auth login` if needed — I will type the auth steps myself) and push.
Add .github/workflows/ci.yml with two jobs:
(a) "placeholder-build" on ubuntu-latest that just echoes ok for now;
(b) "arm-probe" on ubuntu-24.04-arm that runs `lscpu` and `cat /proc/cpuinfo`, saves both to
probe-ci.txt, and uploads it as an artifact.
Verify the workflow runs green, then closeout.
```

**You verify:**
- The repo URL opens in a private/incognito browser window (proves it's public).
- The About section shows "MIT license".
- Actions tab: latest run is green. Open the `arm-probe` artifact, open `probe-ci.txt`, and check
  the Features line contains the words `asimddp` and `i8mm`. Save this file — it's submission
  evidence.

**Red flags:** agent wants to skip GitHub "for now" (refuse — CI is our build machine); agent adds
any dependency in this phase (there should be none).

---

## Phase 1 — Walking-skeleton APK + silicon probe (Days 1–2)

**Goal:** an installable APK whose only screen tells us the truth about the phone's silicon.

**Prompt:**
```
Phase 1. Scaffold the Android app per CLAUDE.md section 3 (Kotlin, minSdk 28, targetSdk 34,
arm64-v8a only, no INTERNET permission). One screen: "Probe". It must display, live:
device model, Android version, ABI list, core count, per-core max frequencies read from
/sys/devices/system/cpu/*/cpufreq/cpuinfo_max_freq (group cores into clusters by frequency),
the Features line from /proc/cpuinfo, total RAM, and current thermal status if the API level
allows. Add a "Share probe JSON" button that exports all of it via the Android share sheet.
Update CI: replace placeholder-build with a real job on ubuntu-latest that builds the debug APK
and (1) uploads it as an artifact, (2) publishes it to a rolling GitHub pre-release tagged
"dev-latest" so I can download the APK directly on my phone's browser.
Tell me exactly, step by step, how to install it on my Samsung A20e, including the Play Protect
warning I should expect. Closeout.
```

**You verify:**
- On the phone: download the APK from the repo's Releases page, install it (expect a warning —
  sideloading always triggers one; proceed).
- Probe screen shows **8 cores in 2 clusters** (2 fast + 6 slow) and a Features line that does
  **NOT** contain `asimddp` or `i8mm` (that absence is a key fact for our story).
- Tap Share, send the JSON to yourself, and paste it into the architect chat.

**Red flags:** APK larger than ~30 MB already (something bloated got in); agent marks the phase
done without you confirming the install; more than one screen appears.

---

## Phase 2 — Camera + face landmarks (Days 3–4)

**Goal:** front camera at 640×480 with MediaPipe FaceLandmarker drawing a live face mesh, plus an
always-on performance HUD.

**Prompt:**
```
Phase 2. Add a "Camera" screen: CameraX front camera, ImageAnalysis at 640x480 targeting
10-15 fps, MediaPipe Tasks FaceLandmarker (Apache-2.0) in LIVE_STREAM mode with blendshapes and
facial transformation matrix enabled. The .task model file must be fetched by a gradle task or
scripts/get_models.sh at build/setup time, never committed. Overlay the landmarks on the preview.
Add a persistent perf HUD: camera fps, landmark inference ms (rolling average), app RSS MB.
Record the HUD values in a debug log I can screenshot. Keep everything else unchanged. Closeout.
```

**You verify:**
- Mesh sticks to your face; moves when you move; survives glasses if you wear them.
- HUD shows numbers. Write down fps and inference ms; screenshot and note them in the architect
  chat. (Whatever they are — this is our vision baseline.)

**Red flags:** OpenCV or any large new dependency appears (ask: "why is MediaPipe not enough?");
resolution silently raised above 640×480; HUD removed "for cleanliness".

---

## Phase 3 — Signals in :core + replay tests (Days 5–6)

**Goal:** real attention/fatigue signals computed in the pure-Kotlin `:core` module, tested in CI
by replaying recorded landmark streams (numbers, not video).

**Prompt:**
```
Phase 3. Create the :core module (pure Kotlin, zero Android imports). Implement, documented in
docs/SIGNALS.md in plain language with formulas: blink detection from eye blendshapes or EAR;
blink rate per minute; PERCLOS over a rolling 60 s window; gaze-on-screen heuristic from iris
landmarks + head yaw/pitch derived from the transformation matrix; head-pose stability; optional
yawn from jawOpen. Every threshold must be a named constant with a comment explaining the choice.
In :app, add a landmark-stream recorder (JSON of landmark/blendshape frames only — never video)
and a debug view showing all live signal values.
Add CI job "core-tests" on ubuntu-24.04-arm running the :core unit tests on a JVM, including
replay tests over recorded streams committed under bench/replays/.
Then give me a precise recording protocol for three 2-minute labeled sessions on my phone:
(1) focused reading, (2) distracted (looking at another phone), (3) simulated drowsiness
(slow long blinks). I will record them; the replay tests must then assert the obvious ordering,
e.g. PERCLOS(drowsy) > PERCLOS(focused), gaze-on-screen(focused) > gaze-on-screen(distracted).
Closeout.
```

**You verify:**
- Do the three recordings following the protocol; the agent commits them and CI goes green with
  the ordering assertions.
- Live debug view: close your eyes → PERCLOS climbs; look away → gaze-on-screen drops. You can
  see it happen.

**Red flags:** accuracy percentages claimed anywhere (we have no ground truth beyond ordering);
thresholds with no comments; the recorder capturing video frames.

---

## Phase 4 — Focus score + session dashboard (Day 7)

**Goal:** one smooth 0–100 focus score with a fatigue flag, a session screen, and JSON export.

**Prompt:**
```
Phase 4. In :core, implement a fusion of the Phase 3 signals into a 0-100 focus score plus a
boolean fatigue flag, with smoothing and hysteresis so values don't flicker (document the exact
rules in docs/SIGNALS.md). In :app, build the "Session" screen: big current score, a simple
timeline sparkline, elapsed time, and session summary stats. Add "Export session JSON" via the
share sheet (all signals, scores, timestamps, plus the probe info). Unit-test the fusion and
hysteresis in :core with synthetic inputs. Closeout.
```

**You verify:**
- Prop the phone on a stand, read something for 5 minutes: score stays high-ish and stable.
- Stare out the window 30 s: score visibly drops, then recovers. No wild ±30 jumps second to
  second.
- Export a session JSON and paste it into the architect chat for review.

**Red flags:** score behavior only described, not demonstrated; fusion weights buried in code
with no doc.

---

## Phase 5 — On-device LLM coach (Days 8–9)

**Goal:** SmolLM2-360M Q4 running locally via llama.cpp, generating short coaching messages on
events, with TTFT and tok/s measured and displayed. Airplane-mode proof.

**Prompt:**
```
Phase 5. Integrate llama.cpp (MIT) following its Android example pattern: pinned checkout or
submodule, JNI wrapper, arm64-v8a. Model: SmolLM2-360M-Instruct Q4_K_M GGUF (Apache-2.0).
Since the app has no INTERNET permission, add an "Import model" flow using the system file
picker (SAF), copying the .gguf into app storage; also add scripts/get_models.sh so I can
download it on my PC (print the exact Hugging Face search terms and expected file size).
Load with mmap, n_ctx 512, 2 threads. Coach behavior: fires ONLY on events (fatigue flag,
sustained low focus, every 10-minute milestone) with a compact prompt built from the last
5 minutes of signals; persona: supportive focus coach, max 40 words, language per a settings
toggle (English/French). Show each message with its measured TTFT and tok/s, and log both into
the session JSON. Closeout.
```

**You verify:**
- Download the GGUF on your PC (agent gives exact instructions), transfer to phone, import it.
- **Airplane mode ON**, run a session, trigger fatigue (long slow blinks ~1 min): a coaching
  message appears with TTFT and tok/s numbers on screen. Screenshot with the airplane icon
  visible in the status bar — this is a core submission proof artifact.
- Report TTFT and tok/s to the architect chat, whatever they are.

**Red flags:** agent proposes a bigger model "for quality" (refuse — 3 GB RAM); any INTERNET
permission appears in the manifest (this is a hard rule); coach generating continuously instead
of on events.

---

## Phase 6 — The Arm optimization pass (Days 10–11) — the heart of the submission

**Goal:** a scripted benchmark mode, then optimizations landed one commit at a time, each with
honest before/after numbers on the A20e and the CI arm64 runner.

**Prompt (session A — baseline):**
```
Phase 6A. Build a benchmark mode in the app: a scripted 5-minute run that replays a committed
landmark stream through the full :core pipeline while triggering 3 coach generations at fixed
times, and records: effective fps, per-frame ms, TTFT, tok/s, RSS, battery level and (if the
device reports it) charge counter at start/end, and thermal status. Output: one JSON in the
export format, saved under bench/results/ naming scheme <device>-<git-sha>-<label>.json.
Also add a CI job on ubuntu-24.04-arm that builds llama.cpp and runs llama-bench on the same
GGUF (fetched in CI) with 1,2,4 threads, saving JSON to bench/results/ci/.
I will run the on-phone benchmark twice (agent: tell me the exact controlled protocol —
airplane mode, fixed brightness, starting battery %, phone flat on desk) and send you the JSONs.
Commit them as the BASELINE. No optimizations in this session. Closeout.
```

**Prompt (session B — optimizations):**
```
Phase 6B. Using the committed baseline, land these as SEPARATE commits, each with before/after
numbers appended to docs/DECISIONS.md, measured by re-running the benchmark (I'll run on-phone
measurements when you ask, one optimization at a time):
1. Thread placement: pin our camera/analysis executor threads toward the A53 cluster and the
   llama.cpp threads (2) toward the A73 cluster via JNI thread affinity; if pinning fails on
   this device, fall back to thread priorities and SAY SO in DECISIONS.md.
2. Duty-cycling: drop vision to ~5 fps when signals have been stable for 30 s; burst back on
   change. Measure battery + CPU effect.
3. Quantization sweep on the CI runner via llama-bench: Q4_0 vs Q4_K_M vs Q5_K_M for our model
   (size, tok/s); pick the phone candidates and I'll run the top 2 on-device.
4. Anything else you propose, one commit each, numbers or it didn't happen.
If an optimization makes things worse, keep the data, revert the change, and record the negative
result. Finish by generating docs/RESULTS.md: a table comparing baseline vs optimized on the
A20e, and A20e vs Neoverse N2 CI runner, from the committed JSONs only. Closeout.
```

**You verify:**
- `docs/RESULTS.md` exists and every number in it traces to a file in `bench/results/` (spot-check
  two numbers yourself by opening the JSONs).
- You personally ran the on-phone benchmarks for baseline and after each optimization the agent
  asked about (protocol followed: airplane mode, same brightness, battery between 50–80%).
- The battery delta between baseline and optimized runs is stated with the raw start/end values.

**Red flags:** any number in RESULTS.md you can't find in a committed JSON; "estimated" or
"expected" improvements; all optimizations in one giant commit; a benchmark re-run that improved
suspiciously (ask for 3 repeats and the spread).

---

## Phase 7 — Hardening + registry (Day 12)

**Goal:** it doesn't crash, it doesn't cook the phone, and the docs work for a stranger.

**Prompt:**
```
Phase 7. (1) 30-minute soak: I'll run a full session; app must not crash, RSS must stay under
budget, thermal status logged — give me the protocol. (2) Write the README top to bottom:
what/why, the two-device story, setup from zero (PC + phone), how to validate every claim,
results tables from docs/RESULTS.md, screenshots. I will follow the README literally on a clean
folder and report where I get stuck — fix those spots. (3) Create bench/registry.md: one entry
per device we have data for (A20e, Neoverse N2 runner), with probe facts + headline numbers,
and a template inviting others to submit theirs via PR. Closeout.
```

**You verify:** you complete the README steps yourself without help; the 30-min soak finishes with
the app still responsive; phone warm is fine, hot is a finding to record.

---

## Phase 8 — Submission package (Days 13–14)

**Goal:** everything Devpost needs, ready two days early.

**Prompt:**
```
Phase 8. Write docs/SUBMISSION.md containing: Project Overview (and why it should win, mapped to
the four judging criteria); Functionality/Output; Setup Instructions (condensed from README);
the list of proof artifacts with direct links (probe JSONs both devices, airplane-mode
screenshot, no-INTERNET manifest line, RESULTS.md, CI runs, registry). Then write a 3-minute
video script + shot list using only: screen recording of the phone (scrcpy), the app in use on
a stand, and terminal/CI shots — no music, no third-party logos or trademarks, per hackathon
rules. Finally produce a submission-day checklist for me. Closeout.
```

**You verify:** read SUBMISSION.md as if you were a judge — if any sentence confuses you, it will
confuse them; send it to the architect chat for the final review pass. Record the video (scrcpy
screen-record + one real shot of the phone on its stand), upload to YouTube, submit on Devpost
**Aug 12**.

---

## Pacing and the cut list

| Days (target dates) | Phases |
|---|---|
| 1–2 (Jul 29–30) | 0, 1 |
| 3–4 (Jul 31–Aug 1) | 2 |
| 5–6 (Aug 2–3) | 3 |
| 7 (Aug 4) | 4 |
| 8–9 (Aug 5–6) | 5 |
| 10–11 (Aug 7–8) | 6A, 6B |
| 12 (Aug 9) | 7 |
| 13–14 (Aug 10–11) | 8 |
| 15 (Aug 12) | Submit. Buffer until Aug 14, 4 pm PDT. |

**If ≥2 days behind after Phase 5**, cut in this order (tell the architect first): yawn detection
→ duty-cycling → the French language toggle → the second on-device quant candidate. Never cut:
the probe, the airplane-mode proof, thread placement, the baseline-vs-optimized table.
