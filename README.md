# FocusForge

A fully on-device focus & fatigue coach for Android. The phone sits on a stand facing you
while you study or work: a camera pipeline extracts behavioral attention signals (blinks,
PERCLOS, gaze-on-screen, head-pose stability), a fusion layer produces a live focus score,
and a small local LLM generates short, supportive coaching messages.
**Nothing ever leaves the device** — the app declares no INTERNET permission.

Entry for the **Arm AI Optimization Challenge 2026, Track 3 (Mobile AI)**.
The story is optimization: one codebase that probes its Arm silicon at runtime and adapts
across seven years of Arm CPUs — from a 2019 Samsung Galaxy A20e (Cortex-A73/A53, NEON only)
to a Neoverse N2-class CI runner (dotprod, i8mm, SVE2) — with honest before/after numbers
for every change.

**Status: Phase 0 — repo and CI rails.** See [docs/PROGRESS.md](docs/PROGRESS.md) for the
session-by-session log and [CLAUDE.md](CLAUDE.md) for the project constitution.

License: [MIT](LICENSE).
