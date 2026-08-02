# Device profiles — the cross-silicon exhibit

Two machines. One code path. Two genuinely different answers, each carrying the measurement
that produced it.

| | Samsung Galaxy A20e | GitHub `ubuntu-24.04-arm` |
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
