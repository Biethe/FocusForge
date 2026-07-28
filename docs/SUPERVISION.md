# FocusForge — Operator's Guide (how to supervise without being an expert)

You don't need to understand AI optimization to keep this project on track. You need to do three
things well: **run the ritual, demand evidence, and relay between the two Claudes.** This file
teaches exactly that.

## 1. The triangle

- **Architect** (the Claude chat where this file came from): owns the plan, reviews evidence,
  adjusts prompts, makes technical decisions. Bring it problems and numbers.
- **Builder** (Claude Code on your PC): writes and tests the code, following `CLAUDE.md` — which
  it reads automatically at the start of every session — and `docs/PROMPTS.md`.
- **Operator** (you): runs sessions, verifies with your own eyes, does everything physical
  (phone installs, recordings, benchmarks), and ferries `PROGRESS.md` entries, JSONs, and
  screenshots to the architect.

You are not the weak link in this triangle. You are the only one with hands and eyes; both
Claudes are blind without you.

## 2. Setup (once)

1. Install Claude Code following the official quickstart: https://docs.claude.com/en/docs/claude-code/overview
   (you need a Claude Pro/Max subscription or Claude Console API access; `claude doctor` checks
   your install). It runs on macOS, Linux, and Windows.
2. Install the GitHub CLI (`gh`) — the builder will walk you through `gh auth login` in Phase 0.
3. Make a project folder, put `CLAUDE.md` at its root, and the other three files in `docs/`.
4. In a terminal, `cd` into the folder and run `claude`. You're live.

You can talk to the builder in French; ask it to keep all code, comments, and docs in English
(the judges read English).

## 3. The daily ritual (15–45 min most days; more on benchmark days)

1. Open the session, paste the **Kickoff** prompt from PROMPTS.md. Read the 6 bullets. If
   anything surprises you, ask; otherwise reply `GO`.
2. Let it work. Answer its questions. When it asks you to do something on the phone, do it and
   report exactly what you saw (including errors, word for word).
3. Paste the **Closeout** prompt. Then do the phase's **You verify** checks yourself.
4. Copy the newest `docs/PROGRESS.md` entry (plus any JSON/screenshot it references) into the
   architect chat. The architect replies with course corrections or the go-ahead for the next
   phase. This step is what makes your lack of expertise irrelevant — the architect audits so
   you don't have to.

## 4. The three magic questions (paste any of these, anytime, no expertise needed)

1. `Show me the exact command you ran and its real output.`
2. `Which committed file, test, or CI run proves this? Give me the link.`
3. `What are you least sure about right now, and what's the cheapest way to find out?`

If an answer to (1) or (2) doesn't exist, the claim doesn't exist yet. That single habit
catches most agent failure modes.

## 5. Red flags → what you do

| You notice | You respond |
|---|---|
| "This should work" / "I've verified" with no runnable proof | Magic question 1, then 2. |
| A benchmark number appears with no file behind it | `Every number must trace to bench/results/. Re-measure or mark NOT MEASURED YET.` |
| A result improves suspiciously fast | `Re-run it 3 times and show me all 3 numbers and the spread.` |
| It wants a bigger model, a new framework, or a restructure | `That conflicts with CLAUDE.md. Write the proposal in docs/DECISIONS.md; I'll ask the architect.` |
| A new dependency appears | `What's its license? Add it to docs/DECISIONS.md.` (Only MIT/Apache-2.0/BSD are allowed.) |
| Stuck >45 min on the same error | `Stop. Summarize the error in plain language in PROGRESS.md.` → bring to architect. |
| It asks for passwords, API keys, or to disable safety/permission prompts | Don't. The only auth this project needs is `gh auth login`, which **you** type yourself. |
| It wants to install system-wide things on your PC | Pause; ask the architect first. Project-local installs are fine. |
| Anything touching the five hard rules in CLAUDE.md (licenses, privacy, INTERNET permission, emotion wording, fake numbers) | Hard stop. These are non-negotiable. |

## 6. Your safety net: git

Everything is committed in small steps, so nothing is ever truly lost and you can always back out:

- `What changed in the last 5 commits? Explain in plain words.` — your project history, narrated.
- `Revert to the last commit where CI was green.` — the undo button.
- Before anything risky: `Create a branch for this experiment; don't touch main.`

## 7. Understanding what the builder says — mini-glossary

- **Quantization / Q4_K_M / GGUF**: shrinking a model's numbers from high precision to ~4 bits so
  it fits and runs on small devices; GGUF is the file format llama.cpp reads; Q4_K_M is one
  specific 4-bit recipe.
- **tokens/s (tok/s)**: how many word-pieces the LLM generates per second. Higher = snappier coach.
- **TTFT**: time to first token — the pause before the coach starts "typing". Lower is better.
- **NEON**: the SIMD (do-8-things-at-once) instructions every 64-bit Arm CPU has. Your A20e has
  only this.
- **dotprod / i8mm**: newer Arm instructions that massively speed up quantized AI math. Your phone
  lacks them; the CI server has them. That contrast is our demo.
- **big.LITTLE / affinity**: Arm's split between fast (A73) and efficient (A53) cores; affinity =
  telling a thread which cores to prefer. Our trick: vision on LITTLE, LLM on big.
- **PERCLOS**: % of time the eyes are ≥80% closed over a window — a validated drowsiness measure
  from driving research.
- **EAR / landmark / blendshape**: eye-aspect-ratio; a tracked face point; a 0–1 "how open/active
  is this facial feature" value MediaPipe outputs.
- **RSS**: how much RAM the app is actually using. Budget: 700 MB.
- **Thermal throttling**: the phone slowing itself down when hot — why sustained numbers matter
  more than 10-second bursts.
- **JNI / NDK**: the bridge letting our Kotlin app call C++ (llama.cpp); the toolkit that compiles
  that C++ for Arm.
- **mmap**: loading model weights by mapping the file instead of copying it — saves RAM.
- **CI / artifact / runner**: the robot on GitHub that builds and tests every push; a file it
  saves for you; the machine it runs on (ours is a real Arm server — free for public repos).
- **APK / sideload**: the Android install file; installing it directly instead of via the Play
  Store (expect a one-time warning — normal).

## 8. When to ping the architect (that chat, anytime)

Always after each phase (the ritual). Immediately if: a verification fails twice; any
license/privacy doubt; the builder proposes changing a locked decision; benchmark JSONs are ready
for review; and before you press Submit on Devpost — the architect does the final read of
SUBMISSION.md, RESULTS.md, and the video script.

Deadline anchor: **submission target Aug 12; hard stop Aug 14, 4 pm PDT = Aug 15, 1:00 am French
time.** If a day slips, say so in the architect chat the same day — the cut list in PROMPTS.md
exists for exactly that.
