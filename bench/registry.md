# Device registry

One entry per machine we have real measurements for. Everything here was produced by the
app or by CI on that device — nothing is estimated, and nothing is carried over from another
machine.

**Adding yours:** run the self-benchmark (LLM screen → *Self-benchmark → device profile*),
share the resulting `device.profile.json`, and open a PR adding a row plus the profile under
`bench/profiles/`. The profile embeds its own raw evidence, so a reviewer can check every
number in your row without owning your phone.

---

## Samsung Galaxy A20e (SM-A202F/DS) — Android 11

The target device, and the reason this project exists.

| | |
|---|---|
| SoC | Exynos 7884B |
| CPU | 2× Cortex-A73 @ 1.56 GHz + 6× Cortex-A53 @ 1.35 GHz |
| ISA | **Armv8.0-A** — `fp asimd aes pmull sha1 sha2 crc32` |
| **Missing** | **no dotprod, no i8mm, no SVE, no LSE atomics, no fp16 arithmetic** |
| RAM | 3 GB (2.68 GiB usable) |
| Vision loop | 8.3–9.6 fps at 640×480, MediaPipe FaceLandmarker |
| LLM | llama.cpp `b10227`, SmolLM2-360M-Instruct, CPU only, `-march=armv8-a` |

**Headline numbers** (q8_0, 386 MB — see the note below):

| | measured |
|---|---|
| prefill | 16.9 / 24.0 / 31.2 tok/s at 2 / 4 / 6 threads |
| TTFT, warm cache, 6 threads | **1481 ms** |
| decode | 12.3–17.1 tok/s |
| model load | 3005 ms |
| RSS, camera + model | **560 MB** peak (budget 700) |
| profile chose | 6 threads |

Profiles: [`a20e-selfbench.device.profile.json`](profiles/a20e-selfbench.device.profile.json),
[`run2`](profiles/a20e-selfbench-run2.device.profile.json),
[`run3`](profiles/a20e-selfbench-run3.device.profile.json) — three runs of the same
benchmark on the same phone, committed together because they **disagree at 8 threads**
(27.5 / 57.9 / 86 ms per token). See `bench/profiles/README.md`.

Raw: [`a20e-threads-kvcache-20260802.json`](results/a20e-threads-kvcache-20260802.json),
[`a20e-phase5-gate-20260802.json`](results/a20e-phase5-gate-20260802.json).

> **Quantisation note.** Every number above was measured with **q8_0**, because the official
> SmolLM2 GGUF repository publishes only that quantisation. The shipping model is Q4_K_M at
> 271 MB, loaded onto the device on 2026-08-02. Its numbers are `NOT MEASURED YET` and will
> replace these when they are. Treat the table as a **floor**: fewer weight bytes per token
> on a bandwidth-limited CPU can only help.

---

## GitHub `ubuntu-24.04-arm` runner — Neoverse-class

The contrast device. Same code, same contract, seven years newer.

| | |
|---|---|
| CPU | 4 cores, **one cluster** (no big.LITTLE) |
| ISA | `asimddp i8mm sve sve2 bf16` — **every feature the A20e lacks** |
| Workload | reference CPU kernel, **not** a language model (see below) |

| | measured |
|---|---|
| reference throughput | 2.75 / 1.25 / 0.60 ms per unit at 1 / 2 / 4 threads |
| sustained load | 0.5% throughput lost over 20 s |
| profile chose | **1 thread** |

Profile: [`ci-arm64.device.profile.json`](profiles/ci-arm64.device.profile.json), regenerated
by CI on every push.

> **Workload note.** A CI job cannot sensibly fetch a 270 MB model inside a 60-second
> benchmark, so this machine is measured with a deterministic quantised-dot-product kernel.
> Thread scaling, the scaling knee, sustained-load behaviour, topology and ISA features are
> comparable with the phone. **Milliseconds are not.**

---

## Template for a new entry

```markdown
## <Device> — <OS version>

| | |
|---|---|
| SoC | |
| CPU | |
| ISA | (the `Features:` line from /proc/cpuinfo) |
| Missing | (dotprod? i8mm? SVE?) |
| RAM | |

| | measured |
|---|---|
| prefill | tok/s at N threads |
| TTFT, warm | ms |
| decode | tok/s |
| RSS peak | MB |
| profile chose | N threads |

Profile: `bench/profiles/<device>.device.profile.json`
Model + quantisation:
Anything that surprised you:
```

**What we would most like to see.** A device *between* these two — an Armv8.2 phone with
dotprod but no SVE — because neither of ours can tell us what those kernels are worth in
practice. And any device where the profile chooses something we would not have guessed.
