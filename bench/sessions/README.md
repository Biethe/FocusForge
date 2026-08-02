# Session exports

Written by the Session screen's **Export session JSON** button: scores and signals, one row
per second, plus the device's silicon facts. Numbers only — no landmarks, no blendshapes,
no image data (docs/SIGNALS.md §15.7).

These are **not** replay fixtures. They cannot be replayed, because they carry no face
geometry — the pipeline cannot be re-run from one. They are kept as a record of what the app
actually reported on the phone, which is how the blink bug in §16 was first spotted.

Landmark recordings, which *can* be replayed frame by frame, live in `bench/replays/`.

| file | what it is |
|---|---|
| `session-sm-a202f-20260731-185007.json` | The 64 s session that reported 0 blinks and started the Phase 4.5 investigation. Recorded with app 0.4.0-phase4, before `earOpen` was added to the export. |
| `session-sm-a202f-20260802-172244.json` | First coach message on the phone, in French. TTFT 9102 ms on a 132-token prompt; the model replied with a refusal. Both facts drove the decision to stop writing French prompts (docs/DECISIONS.md). |
| `session-sm-a202f-20260802-172730.json` | Same build, English. TTFT 4836 ms on an 81-token prompt, decode 11.8 tok/s. With the 22-token/1240 ms smoke test these three points fit TTFT = 61 ms per prompt token with a near-zero intercept — which is what identified prompt processing as essentially all of TTFT. |
| `session-sm-a202f-20260802-174425.json`, `...-174710.json` | Two sessions on 0.5.6, one coach message each — so KV-cache reuse was never exercised and TTFT was unchanged at 4825/4883 ms. Not a failed optimisation: the coach's 5-minute quiet rule makes a second message impossible in a 2-minute session. This is why the threads x cache benchmark exists. Both replies also rambled to the token cap, and one had the model answering *as* the tired student. |
| `session-sm-a202f-20260802-155732.json` | First session with the coach (0.5.3). Found two bugs at once: the coach never spoke despite the fatigue flag being up for 56% of the session, and `earOpen` calibrated to 0.188 against a true ~0.28. Both fixed in 0.5.4; both have regression tests. This file is why the `earOpen` column was added to the export. |
