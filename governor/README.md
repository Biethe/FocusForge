# aarchmage

A self-tuning runtime for Arm devices. It measures the silicon it woke up on, derives a
configuration from those measurements, and then holds itself to a written performance
contract while it runs — adjusting one knob at a time and logging why.

Built for [FocusForge](../README.md), but it depends on nothing in it: no app types cross the
API, and the module is pure Kotlin/JVM. The same code runs on an Android phone and on a Linux
CI runner, which is the point.

## The problem it exists for

The usual approach to mobile performance is to tune on the device you own and ship the
constants. That produces numbers that are true for one phone and unexamined everywhere else.

We had a live example. The project's own guidance said to run the language model on two
threads aimed at the phone's two fast cores. It was a reasonable assumption. When it was
finally measured on the target device, **six threads was 1.85× faster** — and the difference
was the whole gap between meeting the latency contract and missing it by 60%.

Nobody was careless. The number was simply never measured, because measuring it by hand costs
a person an afternoon per device. aarchmage measures it in about a minute, on whatever machine
it happens to be running on.

## What it does

1. **Discovers the machine.** Core count, clusters grouped by maximum frequency, ISA features,
   memory — read from `/sys` and `/proc`, never assumed. A file it cannot read is reported as
   unknown rather than defaulted to something plausible.
2. **Benchmarks it.** A short sweep on the axes that actually move: thread count, and cache
   reuse. The candidate thread counts come from the topology, so a four-core uniform runner
   and an eight-core big.LITTLE phone get different and appropriate sweeps.
3. **Fits a cost model.** Not a leaderboard — a *predictive* model, so that "would four
   threads meet the contract?" becomes arithmetic instead of an experiment the user sits
   through.
4. **Derives a device profile** and persists it: the chosen configuration plus the raw
   evidence behind it, so any choice can be audited or disputed.
5. **Governs.** Each window, it compares measurements against the contract. On a violation it
   changes **one** knob, with hysteresis, and records the trigger and the measurement that
   caused it.

## The cost model

```
ttftMs = (promptTokens − reusedTokens) × msPerFreshToken(threads) + intercept(threads)
```

Derived from, and validated against, a real device. The constants were fitted on the *cold*
run at each thread count, then used to predict warm runs the fit had never seen:

| threads | ms per fresh token | prefill | worst prediction error |
|---|---|---|---|
| 2 | 59.2 | 16.9 tok/s | 0.5% |
| 4 | 41.7 | 24.0 tok/s | 1.1% |
| 6 | 32.0 | 31.2 tok/s | 1.7% |

Measured on a Samsung Galaxy A20e (Exynos 7884B, Armv8.0-A, 2×A73 + 6×A53) running
SmolLM2-360M-Instruct q8_0 through llama.cpp. Raw data:
[`bench/results/a20e-threads-kvcache-20260802.json`](../bench/results/a20e-threads-kvcache-20260802.json).

On a device where time to first token is *not* dominated by prompt processing, the intercept
absorbs the difference and the same code applies.

## Principles

- **Nothing is estimated.** Every number in a profile came from a measurement on that machine.
  Where something has not been measured, the field is null and stays null.
- **Unmeasured is not the same as bad.** A contract term with no measurement this window is
  not a violation; a term with a null limit is recorded and not enforced.
- **Cheapest, not fastest.** The governor picks the least-resource configuration predicted to
  satisfy the contract. Spare cores on a phone are battery and heat, and a component that
  always maximises is not tuning.
- **Every decision carries its trigger.** A configuration change with no recorded reason is
  treated as a defect, the same as a benchmark number with no evidence behind it.
- **It says when it cannot comply.** If no benchmarked configuration is predicted to meet the
  contract, that is reported. Returning the best of a bad set would let a caller believe the
  promise was kept.

## Platform boundary

Anything Android-specific reaches the module through an interface implemented by the host app
— battery, thermal status, the share sheet, progress UI. That boundary is not stylistic: the
CI rule for this project forbids the Android SDK on the Arm runner, so a single Android import
here would make the cross-silicon exhibit impossible to build.

## Status

Under construction — Phase 6. Discovery, the contract and the cost model are implemented and
tested; the benchmark harness, profile derivation and the governor loop are in progress.
