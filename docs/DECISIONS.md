# Decision Log

> One entry per technical decision, dependency, or optimization, newest first.
> Every new dependency gets a line here with its license (MIT / Apache-2.0 / BSD only).
> Every optimization gets before/after numbers from committed benchmark files —
> negative results are kept and reported honestly.

## 2026-08-02 — TTFT is prompt processing; reuse the KV cache, and stop writing French prompts

Both fixes from the previous entry are confirmed on the device, and three measurements now
pin the cost model down.

| run | prompt | TTFT | decode |
|---|---|---|---|
| smoke test, camera idle | 22 tok | 1240 ms | 13.0 tok/s |
| coach EN, camera paused | 81 tok | 4836 ms | 11.8 tok/s |
| coach FR, camera paused | 132 tok | 9102 ms | 10.7 tok/s |

- **The vision pause worked**: decode recovered from 7.6 tok/s (camera live) to 11.8, against
  13.0 with nothing else running at all.
- **The prompt halving worked**: TTFT 9727 → 4836 ms.
- **Fitting the three points gives TTFT ≈ 61 ms per prompt token, intercept ≈ 0** — prefill
  runs at about 16 tok/s. TTFT on this device is *entirely* prompt processing. That predicts
  7944 ms for the 132-token French prompt against 9102 measured, close enough to act on.
- Meeting the 3000 ms contract by trimming alone would need ≤51 prompt tokens, and the
  English prompt is already stripped to 81. Trimming further would start removing the numbers
  the advice is based on.

**So stop re-processing the part that never changes.** Every coaching prompt opens with the
same instruction and differs only in the figures. The JNI now keeps the prompt tokens that are
in the KV cache, finds the **longest common prefix** with the next prompt by comparing token
ids, drops the cache after that point (`llama_memory_seq_rm`) and decodes only the tail.

Comparing token ids rather than assuming a fixed instruction block is deliberate: it keeps
working when the prompt is reworded, translated or restructured, so there is no structural
promise for a future edit to break silently. The reuse count is returned to Kotlin and will go
into the session export, because an optimisation whose benefit cannot be read off the evidence
is indistinguishable from a claim. **Effect on device: NOT MEASURED YET.**

### French prompts are withdrawn; French *replies* stay

The French run cost 132 prompt tokens against 81 for the same content in English — accented
text costs more tokens in this vocabulary — which at 61 ms/token is three seconds of latency.
It also produced a **refusal**: *"Je suis désolé, mais je ne peux pas répondre à ce que tu as
dit."* SmolLM2-360M is an English-centric model; the longer prompt bought worse output as well
as slower.

The instruction is now always English and simply ends with "Write your reply in French." A
test asserts the French prompt stays within 30 characters of the English one. **Whether the
replies are actually good French is unmeasured** — if they are not, the honest options are to
drop the toggle or change model, and that is the architect's call, not something to paper over.

## 2026-08-02 — The coach works on-device, and it violates the TTFT contract

**It generated text on the A20e.** That is the last unproven piece of Phase 5. Measured
alongside a live camera session: **TTFT 9727 ms, 7.6 tok/s, RSS peak 580 MB.**

Decode passes (7.6 vs a floor of 5) and memory passes (580 vs 700). **TTFT fails the
3000 ms contract by more than 3x**, and both causes are identifiable rather than mysterious:

1. **Prompt length, the dominant term.** TTFT includes processing every prompt token. The
   smoke test's 22-token prompt gave 1240 ms; the coach prompt was ~130 tokens. Scaling
   linearly predicts ~7300 ms, which accounts for most of the 9727.
2. **CPU contention, the rest.** The vision loop runs MediaPipe on every frame on the same
   2×A73 + 6×A53 as the model. Decode also fell from 13.0 tok/s (idle) to 7.6 (camera live) —
   the same effect measured on the generation side, where prompt length plays no part.

Two fixes, both of which are also the right design:

- **The prompt is halved**, 232 chars against 479 — roughly 58 tokens against 130. Prompt
  length is not a style question when TTFT is a contract term; it is latency the user waits
  through. A test now fails if any prompt exceeds 400 characters, with the measurement in the
  failure message.
- **The vision loop stands down while the model works** (`FacePipeline.paused`). The camera
  keeps running — restarting it would cost more than the pause — but MediaPipe does not.
  This is a **preview of the Phase 6 governor's fps-budget knob**: here as a fixed rule,
  there as a measured decision. It is the first real evidence that the knob is worth having.

**Expected but NOT MEASURED YET.** Halving the prompt should roughly halve the prompt-processing
term and removing contention should recover part of the rest, but the only honest number is
the next on-device run. No projected TTFT is recorded anywhere.

**This is the governor's case study.** A hand-tuned build would stop here with two fixes and a
better number. The Phase 6 pitch is that the runtime should notice a contract violation and
turn the knob itself — and we now have a measured violation, an identified knob, and a
before number to compare against.

## 2026-08-02 — Two bugs the first coach session exposed, and a reverted fix

Session: `bench/sessions/session-sm-a202f-20260802-155732.json`, 1.7 min, 0.5.3, RSS peak
**560 MB** — under the 700 MB budget and below the 598 MB the addition predicted, so the
camera and the model genuinely coexist.

**Bug 1 — the coach never spoke.** The fatigue flag rose at t=46 s and stayed up for 56% of
the session; zero messages. `CoachPolicy` tested a *rising edge* against the previous frame,
but updated that previous value even on frames where the policy returned early during
warm-up. So an episode beginning before the 60 s warm-up had its transition consumed and
could never fire. Replaced with **episode tracking**: fatigue is coachable while the flag is
up and not yet coached, and re-arms when the flag clears. Fixes the class, not the instance.

**Bug 2 — the open-eye reference calibrated a third low.** `earOpen` landed at 0.188 against
0.274/0.293/0.298 in the three recordings. Cause: the user looks *down at the phone* to tap
"Start session", so the first seconds carry a depressed eye aspect ratio — and the reference
was a median **frozen** at the end of a 5-second window. Since closure is `1 - EAR/earOpen`,
that scaled every later reading.

**The fix that was tried and reverted, because it is the more interesting result.** The first
attempt made the reference a **p90** over a rolling window, reasoning that an open eye is the
upper part of the distribution. It regressed the pipeline: a higher reference makes every
frame read *more* closed, which amplifies the confound that EAR also falls when looking down
(§16.3). On the distracted recording long closures went 2 → 6 and the fatigue flag began
firing on a session where the user was merely distracted — the exact false positive the split
thresholds were introduced to prevent.

**What shipped: the median, rolling instead of frozen.** The window was the bug, not the
statistic. It recovers within a minute of the user looking up and then follows their posture.
Replay numbers after the change (before → after): focused PERCLOS 0.000 → 0.000, long
closures 0 → 0; distracted 2 → 2 long closures, no fatigue; drowsy PERCLOS 0.118 → 0.090,
long closures 14 → 12, fatigue 0.66 of the session. All ordering assertions hold.

## 2026-08-02 — Phase 5 GATE PASSED with real numbers, and one new risk

Measured on the A20e, build 0.5.2, q8_0 (386 MB), 2 threads, n_ctx 512.
Full record: `bench/results/a20e-phase5-gate-20260802.json`.

| | target | measured | headroom |
|---|---|---|---|
| TTFT (model resident) | <= 3000 ms | **1240 ms** | 2.4x |
| decode | >= 5 tok/s | **13.0 tok/s** | 2.6x |
| RSS peak | <= 700 MB | **557 MB** | 143 MB |

- **All three contract targets pass on the slower quant.** The shipping model is Q4_K_M at
  271 MB, which moves fewer weight bytes per token on a bandwidth-limited CPU, so these are
  floors rather than results.
- The two runs agree to within 7% on TTFT and 1% on decode, which is what a stable
  measurement looks like and gives the governor something trustworthy to benchmark against.
- The model costs **421 MB of RSS for a 386 MB file** — the mmapped weights fully faulted in
  plus ~35 MB of context, KV cache and compute buffers.

### New risk: the camera and the model have never been resident at the same time

That 557 MB was measured with **no camera running**. A real session runs CameraX and MediaPipe
FaceLandmarker alongside the model, and their cost adds on top of the 133 MB baseline rather
than into it. The camera-screen RSS has been outstanding since Phase 2 and is now on the
critical path rather than a nice-to-have.

Arithmetic, to make the stakes explicit: with q8_0 the LLM costs 421 MB, leaving 279 MB for
everything else including the vision pipeline. With Q4_K_M it costs roughly 306 MB, leaving
394 MB. **If the vision pipeline turns out to need more than ~280 MB, Q4_K_M stops being a
preference and becomes a requirement.** Requested from the operator; no design changes until
it is measured.

## 2026-08-02 — Phase 5.0b: GATE PASSED, and the model is held open

- **The gate is passed on the A20e.** Build `0.5.1` generated 20 tokens: **11.4 tok/s decode**
  against a contract floor of 5, on the *slower* q8_0 quant (386 MB). llama.cpp runs on
  Armv8.0-A hardware with no dotprod, no i8mm, no SVE.
- **The reported "TTFT 3149 ms" is not TTFT and must not be recorded as such.** Our first
  implementation loaded, generated and freed in a single call, and started the clock before
  `llama_model_load_from_file` — so that figure is mapping and faulting in a 386 MB file off
  eMMC, plus prompt processing, plus one token. It is a *cold-start* number.
- **Changed:** the model is now held open behind a handle. `nativeLoad` / `nativeGenerate` /
  `nativeFree`, with load time and TTFT measured separately, because they are separate
  things: load happens once per session, TTFT happens per coaching message, and the contract
  is about the second one.
- **Three reasons this had to change anyway**, so it is not rework:
  1. A coach that reloaded a 386 MB model per message would be unusable.
  2. The governor's 60-second self-benchmark cannot afford a multi-second reload per
     configuration.
  3. Amendment 4 asks for a parameterizable call path; threads and `n_ctx` are fixed at
     context creation in llama.cpp, so "change thread placement" means "open a new session",
     which is only affordable when load is measured and understood.
- The smoke test now runs **two generations on one open model** with different prompts —
  cold and warm — because reporting either alone would misrepresent the device.
- `llama_memory_clear` between runs, so run 2 is not cheaper than run 1 for reasons that have
  nothing to do with the silicon.
- **Still outstanding:** the operator reported timings but not the RSS block, and amendment 2
  makes RSS a stop condition. Requested with the next run.

## 2026-08-02 — Phase 5.0a: the gate's real result, and the chat template

- **The SIGILL risk is retired.** The operator ran build 0.5.0 on the A20e and the app did
  not crash: the native library loaded, the model loaded, the context initialised and
  `llama_decode` ran. Every instruction in that path is executable on Armv8.0-A hardware.
  That was the highest-risk item in the project and it is now settled **on the device**,
  not by static analysis.
- **What failed instead:** `no tokens generated`. Cause: SmolLM2-360M-**Instruct** is trained
  on ChatML, and we fed it a bare sentence. With no assistant turn to complete, the first
  token it predicts is end-of-generation; greedy sampling takes it, the loop breaks before
  counting anything, and the run reports zero tokens while every layer underneath worked.
- **Fix:** apply the model's own chat template via `llama_model_chat_template` +
  `llama_chat_apply_template` before tokenizing, falling back to the raw prompt if a model
  carries no template. This was needed for the coach regardless, so it is not throwaway work.
- **Second fix, and the more important one: the error message.** `no tokens generated` cost a
  round trip on the operator's phone and told us nothing. The JNI now reports prompt token
  count, the first sampled token id, whether it was end-of-generation, the `llama_decode`
  return code, and whether the template was applied — and names the likely cause in prose.
  On a project where every on-device observation costs a human round trip, a diagnostic that
  cannot distinguish "the silicon is wrong" from "the prompt was wrong" is a defect in its
  own right.

## 2026-08-02 — Phase 5.0: llama.cpp pinned, and the Armv8.0 baseline is enforced three ways

- **What/License:** llama.cpp (MIT) as a shallow git submodule at `native/llama.cpp`, pinned
  to tag **b10227** (`f5919bf458ef190468b5c329bb293f8a54a1e69c`). NDK **26.3.11579264**
  (r26d) and CMake 3.22.1, both pinned in `app/build.gradle.kts` and installed explicitly in
  CI — the toolchain version is part of the reproduction recipe for the riskiest code we own.
- **Why the flags matter more than usual:** the A20e is an Exynos 7884B, Armv8.0-A, with
  `fp asimd aes sha1 sha2 crc32` and nothing else. llama.cpp's Android examples routinely
  carry `-march=armv8.2-a+dotprod` or `+i8mm`. Such a binary does not run slowly on this
  phone — it SIGILLs, on the only device we have.
- **Configuration:** `GGML_NATIVE=OFF` (otherwise ggml runs `-mcpu=native` against the *build
  host* and bakes in the CI runner's i8mm and SVE2 — precisely the instructions that would
  kill us), `GGML_CPU_ARM_ARCH=armv8-a`, `GGML_CPU_ALL_VARIANTS=OFF`, `GGML_BACKEND_DL=OFF`,
  `GGML_CPU_KLEIDIAI=OFF` (its kernels are all dotprod/i8mm/SVE), `GGML_OPENMP=OFF` (no
  OpenMP in the NDK sysroot), `GGML_LLAMAFILE=OFF`.
- **`GGML_LLAMAFILE` is off deliberately and temporarily.** It is a candidate *measured*
  optimization — exactly the sort of A/B the Phase 6 governor exists to decide, rather than
  something we switch on because it usually helps.
- **Enforced three ways, because a warning would scroll past in CI:**
  1. `native/CMakeLists.txt` fails the build if any banned flag reaches any target.
  2. CI disassembles the shipped `.so` and fails on any `sdot/udot/smmla/ummla/bfdot/bfmmla`
     or SVE register use.
  3. The app prints what the `.so` was compiled for on the smoke-test screen, so a mis-built
     APK is readable rather than inferred from a crash.
- **Verified on the built binary (2026-08-02):** 209 of 209 compile commands used
  `-march=armv8-a` and nothing else; ggml's own feature detection left `HAVE_DOTPROD`,
  `HAVE_MATMUL_INT8`, `HAVE_SVE`, `HAVE_SME` and `HAVE_FP16_VECTOR_ARITHMETIC` all empty; and
  869 438 lines of disassembly contain **zero** dotprod, i8mm, bf16 or SVE instructions.
- **One subtlety worth recording.** The binary *does* contain four LSE atomic instructions
  (`ldadd`/`ldaddal`, Armv8.1), which the A20e lacks. They are safe: clang's
  `-moutline-atomics` puts them inside `__aarch64_ldadd*` helpers that branch on
  `__aarch64_have_lse_atomics` and fall back to an `ldxr`/`stxr` loop. Confirmed by reading
  the disassembly, not by assuming. CI allows LSE **only** inside those guarded helpers and
  fails if one appears anywhere else — that is the failure mode that would otherwise present
  as an unexplained SIGILL.

## 2026-08-02 — STRATEGIC PIVOT: Phase 6 becomes a self-tuning runtime (":governor" / "aarchmage")

Architect decision after the judge webinar. Recorded verbatim, as issued:

> STRATEGIC PIVOT (architect decision after judge webinar — record in DECISIONS.md):
> Phase 6 is redefined. We no longer ship hand-tuned optimizations; we ship a self-tuning
> runtime. New module :governor (library-grade, reusable, its own README — public name
> "aarchmage"):
> 1. First-launch self-benchmark (~60 s, one-time, with progress UI): per-cluster short
>    LLM throughput probe (little cluster / big cluster / mixed, using the loaded GGUF),
>    memory-pressure check against RSS budget, brief thermal-sustain probe. No synthetic
>    numbers: every figure measured on this device at this moment.
> 2. Derive and persist device.profile.json (the "silicon lockfile"): chosen threads,
>    affinity mask, quant/model choice from available files, vision fps budget, plus the
>    raw benchmark evidence embedded. Exportable via share sheet.
> 3. Performance contract in a checked-in contract.json (TTFT ≤3000 ms, drain ≤X%/hr,
>    effective fps ≥5). Session governor v1: monitor TTFT/tok/s/thermal/battery each
>    window; on contract violation, adjust ONE knob per decision with hysteresis
>    (fps budget → n_ctx → thread placement), and log every decision with its trigger
>    into the session JSON (auditable, evidence rule applies to the governor itself).
> 4. CI: run the same self-benchmark path on ubuntu-24.04-arm and commit its derived
>    profile next to the A20e's — the cross-silicon exhibit.
> 5. Plan first (Plan Mode), estimate each item in days, flag anything that threatens
>    the Aug 12 target, and propose what to simplify. Wait for operator GO.

And the Phase 5 amendments issued with it:

> Phase 5 amendments (architect):
> 1. HIGHEST RISK ITEM: compile all native code -march=armv8-a baseline per CLAUDE.md.
>    Before any UI work, build a minimal JNI smoke test that loads the GGUF and generates
>    20 tokens on-device; operator confirms it on the phone FIRST. Only then build the
>    coach UI around it. If you hit SIGILL, the compile flags are the suspect, not the model.
> 2. Memory: load with mmap, n_ctx 512, report RSS immediately after model load and after
>    first generation. If RSS exceeds 700 MB, stop and report before proceeding.
> 3. DECISION GATE (architect-set): if tokens are not generating on-device by end of
>    Aug 4, we switch runtime to the fallback in docs/RESOURCES.md rather than debugging
>    further. Surface blockers the moment they appear; do not thrash past 45 minutes.
> 4. The governor pivot lands next session on top of your JNI layer — keep the inference
>    call path clean and parameterizable (threads, affinity, n_ctx as runtime arguments,
>    not compile-time constants). That interface is about to become the whole project.

**Status: plan submitted to the operator, awaiting GO. No implementation started.**
Superseded by this: the Phase 6A/6B prompts in `docs/PROMPTS.md`, whose hand-tuned
optimization list is replaced by the governor deriving those same choices at runtime. The
individual optimizations (thread placement, duty-cycling) survive as *knobs the governor
turns*, not as separate hand-landed commits.

## 2026-08-02 — ARCHITECT RULING: blink rate demoted, frame rate annotated

Recorded verbatim, as issued:

> 1. Blink rate is DEMOTED to display/telemetry only: fusion weight = 0. The fusion and
>    fatigue flag rest on PERCLOS, long closures, gaze-on-screen, and head stability —
>    all fps-robust at the Phase 6 duty-cycled rate.
> 2. Rationale for the record: blink rate is semantically ambiguous (reading suppresses
>    blinks), high-variance across individuals, and unvalidatable with our ordering-
>    assertion methodology; duty-cycling is a core judged deliverable.
> 3. Evidence-rule consequence: session exports must now include effective visionFps per
>    sample window and a blinkRateValidity field ("full-rate" | "undersampled"). When
>    undersampled, the UI shows blink rate greyed or hidden; exports keep the raw count
>    but flagged. We never present an undercount as a measurement.
> 4. Phase 4.5 remains mandatory: blink detection must be verified correct at full frame
>    rate (calibration protocol + replay assertions unchanged).
> 5. Update docs/SIGNALS.md to reflect the demotion and the fps annotation.
> 6. OPTIONAL stretch, only if we're ahead after Phase 6B: event-driven burst sampling —
>    at low fps, when eye closure crosses the enter-threshold, burst to full fps for 3 s
>    to time the closure precisely, then decay back. Improves long-closure edge precision
>    and is a nice "attention-aware sensing" flourish for the submission. Do not build it
>    now; add it to DECISIONS.md as deferred.

### As implemented (2026-08-02)

- **(1)** `FocusThresholds.WEIGHT_BLINK_RATE = 0.0`, carrying the ruling in the same place as
  the other weights. No blink term is computed — multiplying by zero would be dead
  arithmetic dressed as a design. Two tests: the score is bit-identical across a 10x
  difference in blink rate with every other input held fixed, and the four weights still
  sum to 1.
- **(3)** `visionFps` is on every export row, measured **per sample window** as the ruling
  specifies — frames the engine saw since the previous row over the elapsed time, not a
  rolling average. `blinkRateValidity` is `full-rate` or `undersampled` on each row and on
  the session totals, where it is `undersampled` if the run *ever* dropped below the line,
  since one duty-cycled stretch makes the whole count a floor. Both screens now print
  `blinks 33+ (rate undersampled at 8.9 fps)` instead of a rate.
- **Threshold for `full-rate`: 15 fps**, derived rather than chosen — measured blink
  durations are p50 132 ms, and two samples inside the median blink needs a 66 ms interval.
  **The A20e measures 8.4-9.6 fps, so this flag will read `undersampled` permanently on the
  operator's phone**, and more so after Phase 6. That is the intended outcome of the
  evidence rule, not a defect to tune away.
- **(4)** Unchanged and still passing: the ground-truth probe assertions, the replay ordering
  assertions, and the 3-30/min sanity tripwire.
- **Not hidden by this:** blinking still reaches the score indirectly through PERCLOS, since
  a blink *is* a brief eye closure. Measured, that costs nothing — the operator's focused
  recording has 33 blinks and PERCLOS 0.000 — and there is now a test that a normally
  blinking session still scores >= 95.

## 2026-08-02 — DEFERRED: event-driven burst sampling (architect, item 6)

- **What:** at a duty-cycled low frame rate, when eye closure crosses the enter threshold,
  burst the camera to full rate for ~3 s to time the closure precisely, then decay back.
- **Why deferred, not rejected:** it would sharpen long-closure edge timing and is a genuine
  "attention-aware sensing" story for the submission. **Do not build before Phase 6B**, and
  only if we are ahead of schedule.
- **What it would and would not fix:** it improves the *timing* of closures we have already
  detected. It does **not** recover missed blinks — the trigger only fires once a closure is
  already visible, and the blinks we lose are the ones that never appear in a frame at all.
  So it does not reopen the demotion in item 1.

## 2026-08-01 — Phase 4.5: blink counts are a floor, and we do not correct them

- **What:** the blink probe gives ground truth — 10 performed blinks, 6 detected. The
  missing 4 are not a threshold problem: at 8.4 fps a frame arrives every 119 ms and a
  normal blink lasts 120-290 ms, so some open and close entirely between frames. Only 6
  blink-shaped events exist in the raw trace even with the detector set to 0.20.
- **Decision:** report the undercount, do not correct it. A scaling factor estimated from a
  single 30-second probe would be manufactured data, and it would then propagate into every
  blink number the project publishes.
- **Consequence for the architect:** blink rate and frame rate cannot both be optimised.
  Phase 6 exists to *drop* the frame rate for battery, which will make this worse. PERCLOS,
  long closures and the focus score are unaffected — they measure sustained states, not
  events. If blink rate is to be trusted as a signal, that needs deciding before Phase 6,
  not after.
- **Refuted:** the open-eye calibration hypothesis for the 0-blink session. Operator
  measured 0.31 live and the probe calibrated to 0.278, both healthy. The session's zero was
  the old threshold (which detects 3 of 10) and its shallow `eyeClosure` column was 1 Hz
  sampling — about 2 s of blinks in 64 s gives roughly a 3% chance per sample, so the one
  observed hit is the expected number. An earlier draft of §16.5 called that gap real by
  comparing it against a recording with three times the closure events; that comparison was
  wrong and is corrected in place.

## 2026-08-01 — Phase 4.5: blinks and long closures get separate thresholds

- **What:** `BlinkDetector` now runs two hysteresis state machines. Blinks use
  `EYE_CLOSE_LEVEL / EYE_OPEN_LEVEL` = **0.30 / 0.18** (was 0.50 / 0.35); long closures keep
  **0.50 / 0.35** under the new `LONG_CLOSURE_LEVEL / LONG_CLOSURE_OPEN_LEVEL`.
- **Why:** measured from the committed recordings, real blinks peak at a median depth of
  0.51 with a p10 tail at 0.28 — so a 0.50 entry level sat exactly on the median blink and
  detected 19 of the 37 closure events in the focused recording.
- **Why not simply lower the one pair:** the eye aspect ratio also falls when the user looks
  *down*. The distracted recording rests at closure 0.192 (p75), so with an exit level below
  that, blinks never end and arrive as multi-second long closures. Measured, long closures
  focused/distracted/drowsy with the levels shared: 0/2/14 at 0.50/0.35 but 0/13/16 at
  0.30/0.18 and 0/17/18 at 0.25/0.15 — and the fatigue flag fired for 60% of a *distracted*
  session. Splitting the pairs keeps 16.4 blinks/min and 0/2/14.
- **Before / after** (`BlinkThresholdReport`, `bench/blinks-20260801.txt`):

  | focused | blinks/min | long closures (f/d/dr) | fatigue false-fires |
  |---|---|---|---|
  | before | 9.4 | 0 / 2 / 14 | none |
  | naive retune 0.30/0.18 shared | 16.4 | 0 / 13 / 16 | distracted, 60% |
  | shipped, split | 16.4 | 0 / 2 / 14 | none |

- **New CI assertion:** focused blink rate must be 3-30/min and non-zero. Every existing
  replay assertion was a comparison *between* recordings, so a bug suppressing blinks
  everywhere passed all of them. This is the tripwire for that class.
- **Also:** `earOpen` (the calibrated open-eye reference) is now in the live panel and every
  session export. It scales every closure, so when it calibrates low every eye number reads
  shallow — the leading hypothesis for the 0.219 maximum in the reported session, and
  previously invisible.

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
