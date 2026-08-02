#!/usr/bin/env python3
"""Measure how deep and how long real blinks actually are, from a committed recording.

Why this exists
---------------
A session on 2026-07-31 counted 0 blinks in 64 s. Two things had to be separated:

1. Whether blink detection runs at camera rate at all (it does — see docs/SIGNALS.md 16.1).
2. Whether EYE_CLOSE_LEVEL = 0.50 is reachable by an ordinary blink on this camera.

The second is the same class of question as the PERCLOS bug (SIGNALS.md 5.1) and it gets
the same treatment: measure the amplitude distribution of real closures, then set the
threshold from it. This script replays a landmark recording at FULL frame rate, replicating
`SignalEngine.eyeClosure` exactly, and reports every closure event it can find.

It also demonstrates the 1 Hz sampling artifact: the session export stores one row per
second, so its `eyeClosure` column samples an instantaneous value that is almost never
caught mid-blink. A low maximum there is expected and is NOT evidence of a missed blink.

Usage:
    python3 bench/analyze_blinks.py bench/replays/focused-*.json
    python3 bench/analyze_blinks.py bench/replays/*.json --out bench/blinks.txt
"""

import argparse
import json
import math
import sys
from pathlib import Path

LEFT_EYE_EAR = [33, 160, 158, 133, 153, 144]
RIGHT_EYE_EAR = [362, 385, 387, 263, 373, 380]

# Mirrors core/.../SignalConfig.kt
EAR_OPEN_REF = 0.28
BASELINE_CALIBRATION_MS = 5000
EAR_OPEN_WINDOW_MS = 60000
BASELINE_MIN_SAMPLES = 10
BLINK_MIN_MS = 50
BLINK_MAX_MS = 500

CANDIDATE_LEVELS = [0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.60, 0.70, 0.80]


def percentile(values, pct):
    if not values:
        return float("nan")
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    pos = (len(s) - 1) * pct / 100.0
    lo, hi = math.floor(pos), math.ceil(pos)
    if lo == hi:
        return s[int(pos)]
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)


def eye_aspect_ratio(points, indices, width, height):
    try:
        p = [points[i] for i in indices]
    except KeyError:
        return None

    def d(a, b):
        return math.hypot((a[0] - b[0]) * width, (a[1] - b[1]) * height)

    w = d(p[0], p[3])
    if w <= 0:
        return None
    return (d(p[1], p[5]) + d(p[2], p[4])) / (2.0 * w)


def frames(recording):
    """(t_ms, mean eyeBlink blendshape, mean EAR) for every frame with a face."""
    names = recording["blendshapeNames"]
    indices = recording["landmarkIndices"]
    w, h = recording["imageWidth"], recording["imageHeight"]
    li, ri = names.index("eyeBlinkLeft"), names.index("eyeBlinkRight")
    out = []
    for f in recording["frames"]:
        if not f.get("faceVisible"):
            out.append((f["t"], None, None))
            continue
        bs, lm = f.get("blendshapes") or [], f.get("landmarks") or []
        blink = (bs[li] + bs[ri]) / 2.0 if len(bs) == len(names) else None
        ear = None
        if len(lm) == len(indices) * 3:
            pts = {idx: (lm[i * 3], lm[i * 3 + 1]) for i, idx in enumerate(indices)}
            vals = [v for v in (eye_aspect_ratio(pts, LEFT_EYE_EAR, w, h),
                                eye_aspect_ratio(pts, RIGHT_EYE_EAR, w, h)) if v is not None]
            ear = sum(vals) / len(vals) if vals else None
        out.append((f["t"], blink, ear))
    return out


def open_reference(rows):
    """Replicates BaselineCalibrator.earOpen: median EAR over a ROLLING window.

    This used to replicate the older behaviour — a median frozen after the first five
    seconds — and quietly kept doing so after the engine moved to a rolling window. The two
    disagree badly on some recordings (0.101 against 0.274 on one), and that stale
    replication produced a confident and wrong diagnosis on 2026-08-02. A script that
    mirrors engine behaviour has to be updated with it or it becomes a liar with a
    plausible manner.

    Returns the median of the rolling references, for a single summary figure. Use the
    engine itself when the per-frame value matters.
    """
    refs = []
    window = []
    for t, _, e in rows:
        if e is None or e <= 0:
            continue
        window.append((t, e))
        while window and t - window[0][0] > EAR_OPEN_WINDOW_MS:
            window.pop(0)
        if len(window) >= BASELINE_MIN_SAMPLES:
            refs.append(percentile(sorted(v for _, v in window), 50))
    if not refs:
        return EAR_OPEN_REF
    return percentile(sorted(refs), 50)


def closure_trace(rows, ear_open):
    """Replicates SignalEngine.eyeClosure: 1 - EAR/earOpen, clamped."""
    trace = []
    for t, blink, ear in rows:
        if ear is None or ear_open <= 0:
            trace.append((t, None, blink))
        else:
            trace.append((t, min(max(1.0 - ear / ear_open, 0.0), 1.0), blink))
    return trace


def events(trace, level):
    """Contiguous runs at or above `level`, as (start, end, peak closure, peak blendshape)."""
    out, run = [], None
    for t, c, b in trace:
        if c is not None and c >= level:
            if run is None:
                run = [t, t, c, b if b is not None else 0.0]
            else:
                run[1] = t
                run[2] = max(run[2], c)
                if b is not None:
                    run[3] = max(run[3], b)
        elif run is not None:
            out.append(tuple(run))
            run = None
    if run is not None:
        out.append(tuple(run))
    return out


def analyze(path):
    rec = json.loads(Path(path).read_text())
    rows = frames(rec)
    if not rows:
        print(f"{path}: no frames")
        return
    duration_s = (rows[-1][0] - rows[0][0]) / 1000.0
    seen = [r for r in rows if r[2] is not None]
    fps = len(rows) / duration_s if duration_s else 0.0
    ear_open = open_reference(rows)
    trace = closure_trace(rows, ear_open)

    print(f"\n=== {Path(path).name}")
    print(f"  label {rec.get('label','?')}   {len(rows)} frames   {duration_s:.0f} s   "
          f"{fps:.1f} fps   calibrated open EAR {ear_open:.3f}")

    closures = [c for _, c, _ in trace if c is not None]
    blinks = [b for _, _, b in trace if b is not None]
    print("  closure (EAR-based, full frame rate):  "
          f"p50={percentile(closures,50):.3f}  p90={percentile(closures,90):.3f}  "
          f"p99={percentile(closures,99):.3f}  max={max(closures):.3f}")
    if blinks:
        print("  eyeBlink blendshape, full frame rate:  "
              f"p50={percentile(blinks,50):.3f}  p90={percentile(blinks,90):.3f}  "
              f"p99={percentile(blinks,99):.3f}  max={max(blinks):.3f}")

    # --- the 1 Hz sampling artifact -----------------------------------------
    sampled, last = [], None
    for t, c, _ in trace:
        if c is None:
            continue
        if last is None or t - last >= 1000:
            last = t
            sampled.append(c)
    if sampled:
        over = sum(1 for c in sampled if c > 0.1)
        print(f"  --- if sampled once per second, as the session export does:")
        print(f"      max={max(sampled):.3f}   samples over 0.1: {over} of {len(sampled)}")
        print(f"      (full rate max is {max(closures):.3f} — the 1 Hz column understates it "
              f"{max(closures)/max(max(sampled),1e-9):.1f}x)")

    # --- how many closures would each threshold catch? ----------------------
    print("  --- closure events by threshold (what EYE_CLOSE_LEVEL would catch)")
    print(f"      {'level':>6} {'events':>7} {'blink-length':>13} {'per min':>8}  peak-depth p50/p90")
    for level in CANDIDATE_LEVELS:
        evs = events(trace, level)
        blinkish = [e for e in evs if BLINK_MIN_MS <= (e[1] - e[0]) <= BLINK_MAX_MS]
        depths = [e[2] for e in evs]
        rate = len(blinkish) * 60.0 / duration_s if duration_s else 0.0
        print(f"      {level:>6.2f} {len(evs):>7} {len(blinkish):>13} {rate:>8.1f}  "
              f"{percentile(depths,50):.2f} / {percentile(depths,90):.2f}"
              if depths else
              f"      {level:>6.2f} {len(evs):>7} {len(blinkish):>13} {rate:>8.1f}  -")

    # --- the deepest events, which are the real blinks ----------------------
    evs = events(trace, 0.20)
    evs.sort(key=lambda e: -e[2])
    print(f"  --- deepest 10 closure events (threshold 0.20), of {len(evs)} total")
    for e in evs[:10]:
        print(f"      t={e[0]:>7} ms  duration={e[1]-e[0]:>4} ms  "
              f"peak closure={e[2]:.3f}  peak eyeBlink={e[3]:.3f}")
    if evs:
        depths = [e[2] for e in evs]
        durations = [e[1] - e[0] for e in evs]
        print(f"      depth:    p10={percentile(depths,10):.2f}  p50={percentile(depths,50):.2f}  "
              f"p90={percentile(depths,90):.2f}")
        print(f"      duration: p10={percentile(durations,10):.0f}  p50={percentile(durations,50):.0f}  "
              f"p90={percentile(durations,90):.0f} ms")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recordings", nargs="+")
    ap.add_argument("--out")
    args = ap.parse_args()

    captured = []
    if args.out:
        class Tee:
            def write(self, text):
                captured.append(text)
                sys.__stdout__.write(text)

            def flush(self):
                sys.__stdout__.flush()
        sys.stdout = Tee()

    print("Blink amplitude analysis — see docs/SIGNALS.md 16")
    for p in args.recordings:
        analyze(p)

    if args.out:
        sys.stdout = sys.__stdout__
        Path(args.out).write_text("".join(captured))
        print(f"\nwritten to {args.out}")


if __name__ == "__main__":
    main()
