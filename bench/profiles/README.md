# Device profiles — the cross-silicon exhibit

Two machines. One code path. Two genuinely different answers, each carrying the measurement
that produced it.

| | Samsung Galaxy A20e | GitHub `ubuntu-24.04-arm` |
|
|---|---|---|
| silicon | Exynos 7884B, 2018 | Neoverse-class server part |
| topology | **8 cores, 2 clusters** (2×A73 @1.56 GHz + 6×A53 @1.35 GHz) | **4 cores, 1 cluster** |
| dotprod / i8mm / SVE | **none of them** | **all of them** |
| workload measured | llama.cpp + SmolLM2-360M q8_0 | reference CPU kernel |
| thread sweep derived | 1, 2, 6, 8 | 1, 2, 4 |
| **chosen** | **4 threads** | **1 thread** |
| predicted for the real prompt | 2190 ms | 144 ms |

Neither number was configured. Both were derived by `ProfileDeriver` from measurements taken
on that machine, against the same checked-in `PerformanceContract`, and both profiles embed
the raw evidence so the choice can be disputed or re-derived under a different contract.

## `a20e-selfbench` — the phone profiling itself

`a20e.device.profile.json` was derived on a build machine from the operator's committed
benchmark. `a20e-selfbench.device.profile.json` is the phone running the whole thing on its
own: measuring, fitting, choosing and writing the file, in 90 seconds, unattended.

It is the better artifact, and it validates the automated path against the hand-run one:

| threads | hand-run benchmark | phone's self-benchmark |
|---|---|---|
| 1 | — | 117.8 ms/token |
| 2 | 59.2 | **58.3** (−1.5%) |
| 4 | 41.7 | not in its sweep |
| 6 | 32.0 | **33.4** (+4.4%) |
| 8 | — | 29.4 ms/token |

The sweeps differ because the phone derives its own from its topology — 1, 2, 6, 8, being
the cluster sizes and the whole machine — and never tries 4, which was a number I picked by
hand. It also measured 1 and 8, which I never did.

**It chose 6 threads even though 8 is faster.** Two threads would need 3090 ms for the
typical prompt against a 3000 ms limit and misses; six needs 1770 ms and complies. Eight
would be quicker still and is left alone. Cheapest that complies, not fastest — spare cores
on a phone are battery and heat.

## Read the workload field before comparing milliseconds

`ci-arm64` was measured with **a deterministic CPU kernel, not a language model**. A CI job
cannot sensibly fetch a 270 MB model inside a 60-second benchmark, so the substitution is
deliberate — and it is recorded in the profile's own `device.workload` and `workload_note`
fields rather than left for a reader to infer.

What that means in practice:

- **Comparable**: how throughput scales with threads, where the scaling knee is, whether the
  machine slows under sustained load, and everything about topology and ISA features.
- **Not comparable**: milliseconds. The runner's "144 ms" is 53 units of reference work, not
  53 prompt tokens of SmolLM2.

The A20e profile is the real thing — actual llama.cpp measurements on the physical device,
run through the same derivation rather than re-measured on a build machine.

## Why the answers differ so much

The phone picks **4 threads** because prefill there runs at 23.7 tokens/s and the contract
needs the typical warm prompt inside 3000 ms; two threads would miss it. The runner picks
**1 thread** because a single Neoverse core already finishes the same shaped work in 144 ms,
and the rule is *cheapest configuration that complies* rather than fastest — spare cores are
battery on one machine and someone else's job on the other.

That is the argument for the whole module in one table: shipping the phone's constant to the
server, or the server's to the phone, would be wrong in both directions.

## Regenerating

```bash
./gradlew -PcoreOnly :governor:deriveProfile      # profile whatever machine you are on
./gradlew -PcoreOnly :governor:deriveA20eProfile  # re-derive the phone's from bench/results/
```

`ci-arm64.device.profile.json` is produced by the `core-tests` job on every push and uploaded
as the `device-profiles` artifact; the copy here came from
[run 30764459115](https://github.com/Biethe/FocusForge/actions/runs/30764459115).
