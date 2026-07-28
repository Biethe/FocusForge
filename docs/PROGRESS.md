# Progress Log

> One dated entry per session, newest first. Written in plain language for a non-expert.
> Every claim in **Evidence** must be a link (CI run, committed file) or
> "operator confirmed: &lt;what was seen&gt;". If something is not measured yet, it says
> `NOT MEASURED YET`.

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
