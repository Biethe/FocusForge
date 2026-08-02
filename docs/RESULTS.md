# Results

Every number here was measured on real hardware and is traceable to a committed artifact.
Where something has not been measured it says so. Negative results are kept — several of them
are the most useful things in this file.

Device: **Samsung Galaxy A20e**, Exynos 7884B, Armv8.0-A, 2× Cortex-A73 + 6× Cortex-A53, 3 GB
RAM, Android 11. No dotprod, no i8mm, no SVE, no LSE atomics.

---

## 1. The headline: the smaller model is slower

The received wisdom is that a smaller quantisation is faster on a bandwidth-limited CPU,
because each token moves fewer weight bytes. We shipped that assumption in our own
documentation. **On this device it is wrong**, and it is wrong for a reason that is the whole
argument of the project.

Same phone, same build, same self-benchmark, one hour apart:

| | q8_0 (386 MB) | Q4_K_M (271 MB) | |
|---|---|---|---|
| prefill, 1 thread | 117.8 ms/token | 154.1 | **31% slower** |
| prefill, 2 threads | 58.3 | 78.5 | **35% slower** |
| prefill, 6 threads | 33.4 | 41.9 | **26% slower** |
| prefill, 8 threads | 29.4 | 35.1 | 19% slower |
| decode, 6 threads | 17.2 tok/s | 15.1 tok/s | 12% slower |
| **model RSS** | 423 MB | **303 MB** | **120 MB cheaper** |
| **load time** | 2507 ms | **782 ms** | **3.2× faster** |

Profiles: [`a20e-selfbench`](../bench/profiles/a20e-selfbench.device.profile.json) and
[`a20e-q4km`](../bench/profiles/a20e-q4km.device.profile.json).

**The likely mechanism** — stated as an explanation, not a measurement. `q8_0` is a scale and
an int8: unpacking it is nearly free. `Q4_K_M` is a k-quant with super-blocks, per-block
scales and minimums, and unpacking costs real arithmetic per weight. On a CPU with `dotprod`
or `i8mm`, the matrix multiply is fast enough that the unpacking hides behind it. **This chip
has neither**, so the unpack cost is exposed and dominates the bytes saved. We have not
verified this by profiling the kernels; a device with dotprod would settle it, and we do not
have one (see [`bench/registry.md`](../bench/registry.md)).

### What we ship, and why it is the slower one

**Q4_K_M**, despite being slower. Both quantisations satisfy the latency contract — 2221 ms
against a 3000 ms limit for a typical prompt at 6 threads, versus 1770 ms — and the binding
constraint on this device is **memory**, not latency:

| | q8_0 | Q4_K_M |
|---|---|---|
| camera session alone | 177 MB | 177 MB |
| plus the model | 560 MB measured | ~440 MB |
| headroom against the 700 MB budget | 140 MB | **260 MB** |

Cheapest configuration that complies, not fastest. The 3.2× faster load is a real user-facing
gain too: it is time the coach cannot answer in.

---

## 2. Time to first token: found, explained, fixed

| build | what changed | TTFT |
|---|---|---|
| 0.5.0 | first working coach | **9727 ms** |
| 0.5.5 | prompt halved, vision paused during generation | 4836 ms |
| 0.5.7 | 6 threads instead of 2 | 2659 ms cold |
| 0.5.7 | + KV cache reuse of the shared prompt prefix | **1481 ms** |
| | contract | ≤ 3000 ms |

**6.6× faster, and not one step of it was a guess.** Three on-device measurements fit a cost
model exactly:

```
ttft = (promptTokens − reusedTokens) × msPerFreshToken(threads)
```

| threads | ms/token | prefill |
|---|---|---|
| 2 | 59.2 | 16.9 tok/s |
| 4 | 41.7 | 24.0 tok/s |
| 6 | 32.0 | 31.2 tok/s |

Fitted on the **cold** run at each thread count, then used to predict warm runs it had never
seen: **worst error 1.7%**, mean 0.7%, over six predictions. Raw:
[`a20e-threads-kvcache-20260802.json`](../bench/results/a20e-threads-kvcache-20260802.json).

That model is what lets the runtime *decide* rather than *explore*: "would 4 threads meet the
contract?" is arithmetic, not an experiment the user sits through.

### The thread count contradicted our own documentation

`CLAUDE.md` §5 specified two threads aimed at the two big cores. Reasonable, and never
measured. **Six threads is 1.85× faster.** Scaling is sub-linear — 3× the threads for 1.85×
the speed, because six of the eight cores are A53s — and thread *affinity* remains untested,
so the guidance may still be right about placement while wrong about count.

---

## 3. Memory

| | measured |
|---|---|
| app baseline, no camera | 133 MB |
| camera + MediaPipe session | 177 MB (the vision pipeline costs only **44 MB**) |
| + q8_0 model resident | **560 MB** peak |
| + Q4_K_M model resident | ~440 MB projected |
| budget (CLAUDE.md §2) | 700 MB |

The model can therefore stay loaded for a whole session, which is what makes a coaching
message cost the 1481 ms warm TTFT rather than a 3005 ms cold start.

---

## 4. Signals: what separates the three behaviours

Eight labelled recordings — three focused, two distracted, three drowsy — all committed under
[`bench/replays/`](../bench/replays/) and replayed in CI. The assertions compare the **worst**
recording of one label against the **best** of another, so no single flattering session can
carry a claim.

| | focused (3) | distracted (2) | drowsy (3) |
|---|---|---|---|
| focus score | 96.6 – 96.7 | 66.9 – 75.3 | 46.5 – 89.5 |
| gaze on screen | 0.979 – 0.994 | 0.564 – 0.604 | 0.719 – 0.946 |
| long closures | 0 – 1 | 0 – 2 | 5 – 12 |
| fatigue flag | never | never | **all three** |

- **The score separates focused from everything else**: worst 96.6 against best other 89.5.
- **It is reproducible**: three separate sessions of the same behaviour scored 96.7, 96.7,
  96.6 — a spread of 0.1 points.
- **The fatigue flag is exact** on this set: three of three drowsy, none of the other five.

### The negative result that justifies the design

**PERCLOS alone does not separate every drowsy session.** One drowsy recording measures
PERCLOS **0.000** — identical to a focused session — because the eyes closed to 74% of their
open aperture and P80 is a real line. Long closures (5 against 0) caught it.

Had the score rested on PERCLOS, that session would have read as perfectly alert. It only
became visible because there was **more than one recording per label**; with the single drowsy
recording we started with, PERCLOS looked like a reliable discriminator. Two explanations were
proposed for it and both were wrong — including one of ours that was implemented and then
reverted when it flagged the wrong file. See [SIGNALS.md §17](SIGNALS.md#17).

---

## 5. Cross-silicon: the same code on two Arm machines

| | Galaxy A20e | GitHub `ubuntu-24.04-arm` |
|---|---|---|
| topology | 8 cores, **2 clusters** | 4 cores, **1 cluster** |
| dotprod / i8mm / SVE | **none** | **all** |
| thread sweep *derived* | 1, 2, 6, 8 | 1, 2, 4 |
| **chosen** | **6 threads** | **1 thread** |

Neither was configured. Both were derived by the same code from measurements taken on that
machine, against the same checked-in contract. Shipping either machine's constant to the other
would be wrong in both directions.

The runner is measured with a reference CPU kernel, not a language model — thread scaling and
sustained-load behaviour are comparable, **milliseconds are not**. Recorded in the profile's
own `workload_note` field, not only here.

---

## 6. What is measured badly, or not at all

| | status |
|---|---|
| **blink counts** | Under-report by ~40%. At 8.4 fps a 120–290 ms blink can fall between frames; the ground-truth probe detected 6 of 10 performed blinks. Reported as a floor, flagged `undersampled`, and excluded from the score. |
| **yawn detection** | **Does not work on this device.** Two real yawns detected as zero: `jawOpen` peaks at 0.612 against a 0.60 threshold needing 1.2 s. The geometric fix needs mouth landmarks the privacy allow-list excludes. |
| **battery drain** | `NOT MEASURED YET`. The contract's limit is `null` — recorded, not enforced. The 30-minute soak sets it. |
| **thread affinity** | Never implemented or measured. Profiles record it as an explicit "not measured". |
| **8-thread throughput** | **Not reproducible**: 27.5 / 57.9 / 86 ms per token across three runs of the same benchmark. Eight threads leaves no core for Android's own work. Excluded from selection when a single run detects it. |
| **profile predictions** | Optimistic by a measured **1.66×** — the benchmark runs with the camera idle, the coach runs with it live. Treat predicted latency as a floor. |
| **French coaching** | Withdrawn. Judged bad by the operator; SmolLM2-360M is English-centric, and a French prompt also cost 60% more tokens. |
| **accuracy of any signal** | There is none. No ground truth exists; the project asserts **ordering**, never accuracy. |

---

## 7. Reproducing all of this

```bash
./gradlew -PcoreOnly :core:test          # signals, score, coach policy, replay assertions
./gradlew -PcoreOnly :governor:test      # topology, cost model, benchmark, governor
./gradlew -PcoreOnly :governor:deriveProfile   # profile whatever machine you are on
python3 bench/analyze_blinks.py bench/replays/*.json --sweep
```

On the phone: **LLM smoke test → Self-benchmark → device profile** writes a profile with the
reasoning for every choice in plain sentences.
