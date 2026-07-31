#!/usr/bin/env python3
"""Measure what the eye signals actually look like on real recordings.

Why this exists
---------------
PERCLOS-P80 thresholds eye closure at 0.80, a number taken from research that measures
physical eyelid aperture. We feed it MediaPipe's `eyeBlink` score, which is a model
confidence on an arbitrary 0-1 scale. On the operator's A20e a fully held closure peaks at
0.73, so PERCLOS reads 0.000 forever (docs/SIGNALS.md 5.1).

Picking a replacement cutoff from that 0.73 would be picking it from a single-frame
extreme. This script reads the committed recordings and prints the *distributions* -
percentiles of both candidate measures, split into open-eye and closed-eye populations -
so the cutoff comes from measured data instead of from one peak or from a paper.

It reads only the numbers already in the recording; it computes nothing that the app does
not. Stdlib only, no dependencies.

Usage:
    python3 bench/analyze_eye_scale.py bench/replays/*.json
    python3 bench/analyze_eye_scale.py bench/replays/*.json --out bench/eye-scale.txt
"""

import argparse
import json
import math
import sys
from pathlib import Path

# Eye-aspect-ratio point order, mirroring core/.../LandmarkIndices.kt:
# outer corner, upper lid x2, inner corner, lower lid x2.
LEFT_EYE_EAR = [33, 160, 158, 133, 153, 144]
RIGHT_EYE_EAR = [362, 385, 387, 263, 373, 380]

PERCENTILES = [1, 5, 10, 25, 50, 75, 90, 95, 99]


def percentile(sorted_values, pct):
    """Linear-interpolated percentile of an already-sorted list."""
    if not sorted_values:
        return float("nan")
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = (len(sorted_values) - 1) * pct / 100.0
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return sorted_values[int(position)]
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (position - low)


def eye_aspect_ratio(points, indices, width, height):
    """Same formula as core EyeGeometry: pixels, not normalized units."""
    try:
        p = [points[i] for i in indices]
    except KeyError:
        return None

    def distance(a, b):
        return math.hypot((a[0] - b[0]) * width, (a[1] - b[1]) * height)

    eye_width = distance(p[0], p[3])
    if eye_width <= 0:
        return None
    return (distance(p[1], p[5]) + distance(p[2], p[4])) / (2.0 * eye_width)


def frame_values(recording):
    """Yield (t_ms, mean eyeBlink, mean EAR) for every frame that saw a face."""
    names = recording["blendshapeNames"]
    indices = recording["landmarkIndices"]
    width = recording["imageWidth"]
    height = recording["imageHeight"]
    try:
        left_col = names.index("eyeBlinkLeft")
        right_col = names.index("eyeBlinkRight")
    except ValueError:
        sys.exit("recording has no eyeBlink columns - wrong schema?")

    for frame in recording["frames"]:
        if not frame.get("faceVisible"):
            continue
        blendshapes = frame.get("blendshapes") or []
        landmarks = frame.get("landmarks") or []

        blink = None
        if len(blendshapes) == len(names):
            blink = (blendshapes[left_col] + blendshapes[right_col]) / 2.0

        ear = None
        if len(landmarks) == len(indices) * 3:
            points = {
                index: (landmarks[i * 3], landmarks[i * 3 + 1])
                for i, index in enumerate(indices)
            }
            left = eye_aspect_ratio(points, LEFT_EYE_EAR, width, height)
            right = eye_aspect_ratio(points, RIGHT_EYE_EAR, width, height)
            both = [v for v in (left, right) if v is not None]
            ear = sum(both) / len(both) if both else None

        yield frame["t"], blink, ear


def describe(label, values, invert=False):
    """Print the percentile spread of one measure."""
    if not values:
        print(f"  {label:<22} no data")
        return
    ordered = sorted(values)
    cells = "  ".join(f"p{p}={percentile(ordered, p):.3f}" for p in PERCENTILES)
    print(f"  {label:<22} n={len(ordered):<5} {cells}")
    if invert:
        print(f"  {'':<22} min={ordered[0]:.3f}  max={ordered[-1]:.3f}")


def split_open_closed(rows, ear_open_reference):
    """Split frames into eyes-open and eyes-closed populations.

    Segmentation uses the *geometric* measure, not the blendshape, precisely because the
    blendshape's scale is the thing under investigation - using it to define "closed"
    would assume the answer. A frame counts as closed when the eye aperture has dropped
    below 40% of this recording's own open-eye reference (the 90th percentile of EAR,
    which is a stable stand-in for "this person's eyes, fully open, on this stand").
    """
    threshold = 0.40 * ear_open_reference
    open_rows, closed_rows = [], []
    for t, blink, ear in rows:
        if ear is None:
            continue
        (closed_rows if ear <= threshold else open_rows).append((t, blink, ear))
    return open_rows, closed_rows, threshold


def analyze(path):
    recording = json.loads(Path(path).read_text())
    rows = list(frame_values(recording))
    if not rows:
        print(f"\n{path}: no frames with a visible face")
        return

    label = recording.get("label", "?")
    duration_s = (rows[-1][0] - rows[0][0]) / 1000.0
    total_frames = len(recording["frames"])
    print(f"\n=== {Path(path).name}")
    print(f"  label {label}   device {recording.get('device', '?')}   "
          f"app {recording.get('appVersion', '?')}")
    print(f"  {total_frames} frames, {len(rows)} with a face "
          f"({100.0 * len(rows) / total_frames:.0f}%), {duration_s:.0f} s, "
          f"{len(rows) / duration_s:.1f} fps")

    ears = sorted(v for _, _, v in rows if v is not None)
    if not ears:
        print("  no usable landmarks - cannot segment")
        return
    ear_open_reference = percentile(ears, 90)
    open_rows, closed_rows, threshold = split_open_closed(rows, ear_open_reference)

    closed_ms = 0.0
    if closed_rows and len(rows) > 1:
        closed_ms = len(closed_rows) * (duration_s * 1000.0 / len(rows))
    print(f"  open-eye EAR reference (p90) {ear_open_reference:.3f}; "
          f"a frame counts as closed below {threshold:.3f}")
    print(f"  {len(closed_rows)} closed frames ~ {closed_ms / 1000.0:.1f} s "
          f"({100.0 * len(closed_rows) / len(rows):.1f}% of measurable time)")

    print("  --- eyes OPEN")
    describe("eyeBlink mean", [b for _, b, _ in open_rows if b is not None])
    describe("EAR", [e for _, _, e in open_rows if e is not None], invert=True)
    print("  --- eyes CLOSED")
    describe("eyeBlink mean", [b for _, b, _ in closed_rows if b is not None])
    describe("EAR", [e for _, _, e in closed_rows if e is not None], invert=True)

    closed_blinks = sorted(b for _, b, _ in closed_rows if b is not None)
    open_blinks = sorted(b for _, b, _ in open_rows if b is not None)
    if closed_blinks and open_blinks:
        print("  --- separation")
        print(f"  eyeBlink: open p95={percentile(open_blinks, 95):.3f}  "
              f"closed p5={percentile(closed_blinks, 5):.3f}  "
              f"closed median={percentile(closed_blinks, 50):.3f}")
        print("  A usable cutoff sits above the open p95 and below the closed p5.")
        if percentile(open_blinks, 95) >= percentile(closed_blinks, 5):
            print("  WARNING: the two populations overlap - no clean cutoff exists on "
                  "this measure for this recording.")


MAX_FRAME_WEIGHT_MS = 500  # mirrors SignalThresholds.MAX_FRAME_WEIGHT_MS
BASELINE_CALIBRATION_MS = 5000  # mirrors SignalThresholds.BASELINE_CALIBRATION_MS


def open_reference(rows):
    """The user's open-eye EAR, learned the way the app learns its baseline.

    Median of the calibration window rather than the mean, so a blink during those five
    seconds does not drag the reference down — same reasoning as BaselineCalibrator.
    """
    start = rows[0][0]
    window = sorted(e for t, _, e in rows
                    if e is not None and t - start <= BASELINE_CALIBRATION_MS)
    if not window:
        return None
    return percentile(window, 50)


def time_fraction(rows, is_closed):
    """Time-weighted fraction, the same arithmetic TimeWeightedWindow does.

    Frame-counting would answer a different question: the camera runs at ~9 fps here and
    Phase 6 will duty-cycle it lower still.
    """
    total = 0.0
    closed = 0.0
    previous_t = None
    for t, blink, ear in rows:
        if previous_t is None:
            previous_t = t
            continue
        weight = min(max(t - previous_t, 0), MAX_FRAME_WEIGHT_MS)
        previous_t = t
        verdict = is_closed(blink, ear)
        if verdict is None:  # unmeasurable: excluded, not counted as open
            continue
        total += weight
        if verdict:
            closed += weight
    return (closed / total) if total > 0 else float("nan")


def sweep(paths):
    """PERCLOS under every candidate definition, on every recording.

    Two families are compared. `blink>=C` thresholds MediaPipe's confidence score directly,
    which is what the code does today. `aperture>=P` is the literature's actual P-level
    definition — the eye counts as closed when its opening has lost P of the height it has
    when open — computed from the lid landmarks against the user's own calibrated open eye.
    """
    loaded = []
    for path in paths:
        recording = json.loads(Path(path).read_text())
        rows = list(frame_values(recording))
        reference = open_reference(rows) if rows else None
        if not rows or not reference:
            print(f"skipping {path}: no usable frames")
            continue
        loaded.append((recording.get("label", "?"), rows, reference))
    if not loaded:
        return

    labels = [label for label, _, _ in loaded]
    print("\n\n=== Candidate PERCLOS definitions")
    print("Fraction of measurable time counted as 'eyes closed', time-weighted.")
    for label, _, reference in loaded:
        print(f"  calibrated open-eye EAR, {label}: {reference:.3f}")
    print(f"\n  {'definition':<20}" + "".join(f"{label:>13}" for label in labels)
          + f"{'drowsy/focused':>16}")

    def row(name, is_closed):
        values = {}
        for label, rows, reference in loaded:
            values[label] = time_fraction(rows, lambda b, e: is_closed(b, e, reference))
        cells = "".join(f"{values[label]:>13.3f}" for label in labels)
        ratio = ""
        if values.get("focused", 0) > 0 and "drowsy" in values:
            ratio = f"{values['drowsy'] / values['focused']:>16.1f}x"
        elif "drowsy" in values:
            ratio = f"{'drowsy>0, focused=0':>16}"
        print(f"  {name:<20}{cells}{ratio}")

    for cutoff in [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.80]:
        row(f"blink >= {cutoff:.2f}",
            lambda b, e, ref, c=cutoff: None if b is None else b >= c)
    print()
    for level in [0.60, 0.65, 0.70, 0.75, 0.80, 0.85]:
        row(f"aperture >= {level:.2f}",
            lambda b, e, ref, p=level: None if e is None else (1.0 - e / ref) >= p)

    print("\n  Read the ratio column: a definition that cannot separate a drowsy session")
    print("  from a focused one is useless to us no matter how principled it looks.")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("recordings", nargs="+", help="recording JSON files")
    parser.add_argument("--out", help="also write this report to a file")
    parser.add_argument("--sweep", action="store_true",
                        help="also compare candidate PERCLOS definitions across the files")
    args = parser.parse_args()

    output = []
    if args.out:
        class Tee:
            def write(self, text):
                output.append(text)
                sys.__stdout__.write(text)

            def flush(self):
                sys.__stdout__.flush()

        sys.stdout = Tee()

    print("Eye-signal scale analysis - see docs/SIGNALS.md 5.1")
    print("Measured from committed recordings; no thresholds are applied or implied.")
    for path in args.recordings:
        analyze(path)
    if args.sweep:
        sweep(args.recordings)

    if args.out:
        sys.stdout = sys.__stdout__
        Path(args.out).write_text("".join(output))
        print(f"\nwritten to {args.out}")


if __name__ == "__main__":
    main()
