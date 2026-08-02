package com.focusforge.core

/**
 * Every threshold in the signal pipeline, in one place, each with the reason it has the
 * value it has (CLAUDE.md Phase 3: "every threshold must be a named constant with a
 * comment explaining the choice").
 *
 * These are *heuristics*, not calibrated science. We make no accuracy claim anywhere —
 * the only thing we assert is ordering between labelled recordings (drowsy has more
 * eye-closure than focused, and so on). See docs/SIGNALS.md.
 */
object SignalThresholds {

    // ---------------------------------------------------------------- eye closure

    /**
     * Above this, the eye counts as "closing" — i.e. this much of the eye's opening has
     * been lost.
     *
     * Measured, not chosen (2026-08-01, bench/blinks-20260801.txt): across the three
     * committed recordings, real blinks peak at a *median* depth of 0.51-0.65 with a p10
     * tail at 0.28. The previous value of 0.50 therefore sat exactly on the median blink,
     * and caught only about half of them — the focused recording contains 37 closure
     * events of which 0.50 detected 19. Open-eye frames sit under 0.134 (p90), so 0.30
     * clears the noise floor by better than 2x while catching the shallow tail.
     */
    const val EYE_CLOSE_LEVEL = 0.30

    /**
     * Below this, the eye counts as "open" again. The 0.12 gap under EYE_CLOSE_LEVEL is
     * hysteresis: without it a value hovering around the close level would emit dozens of
     * fake blinks per second. Still above the p90 of open-eye noise while reading (0.134).
     */
    const val EYE_OPEN_LEVEL = 0.18

    /**
     * Long closures — the drowsiness marker — use their **own, stricter pair**, and this is
     * not an accident of tuning. The two measurements have different jobs:
     *
     * - A blink is a transient dip. We only need to *see* it, so a sensitive threshold is
     *   right and a spurious one costs a rounding error on a rate.
     * - A long closure feeds the fatigue alarm. It must mean the eye was substantially
     *   closed for a substantial time.
     *
     * The exit level matters even more than the entry one here. The eye aspect ratio also
     * falls when the user looks **down**, not only when the lid closes: during the operator's
     * distracted recording (repeatedly glancing at a second phone) the resting closure sits
     * at 0.192 at p75. With an exit level below that, a 200 ms blink never crosses back into
     * "open" and runs on until the head comes up, arriving as a multi-second long closure.
     *
     * Measured consequence, from `BlinkThresholdReport` over the three recordings — long
     * closures for focused / distracted / drowsy:
     *
     * ```
     *   0.50 / 0.35   0 /  2 / 14      <- separation 7x, what long closures use
     *   0.40 / 0.28   0 /  7 / 15
     *   0.30 / 0.18   0 / 13 / 16      <- distracted approaching drowsy
     *   0.25 / 0.15   0 / 17 / 18      <- indistinguishable
     * ```
     *
     * With the levels shared, a first attempt at this retune fired the fatigue flag for 60%
     * of a *distracted* session. Split, blinks run at 0.30/0.18 and long closures stay at
     * 0.50/0.35: 16.4 blinks/min when focused, and long closures still 0 / 2 / 14.
     * Regenerate with `BlinkThresholdReport`.
     */
    const val LONG_CLOSURE_LEVEL = 0.50
    const val LONG_CLOSURE_OPEN_LEVEL = 0.35

    /**
     * Starting value for the open-eye aspect ratio, used only until the calibrator has
     * seen a face — after that, eye closure is measured against *this user's* own open eye
     * (BaselineCalibrator.earOpen), because eye shape and distance to the stand vary far
     * too much between people for a fixed number to mean anything.
     *
     * 0.28 is the typical open-eye EAR in the literature, and the operator's three
     * recordings calibrated to 0.274, 0.293 and 0.298 — close enough that a first frame
     * measured against it is not wildly wrong (bench/replays, 2026-07-31).
     */
    const val EAR_OPEN_REF = 0.28

    /**
     * The open-eye reference is a median over a **rolling** window rather than one frozen at
     * the end of calibration. The window is what changed; the median is deliberately kept.
     *
     * The bug it fixes: a user spends the first seconds of a session looking *down at the
     * phone* to start it, and a reference frozen from those seconds is wrong for the next
     * forty minutes. The three labelled recordings, made to a protocol that said "look at your
     * work normally first", calibrated to 0.274 / 0.293 / 0.298; a real session started by
     * simply tapping the button calibrated to **0.188** (2026-08-02) — a third low, which
     * scales every closure reading for the rest of the session. Rolling recovers within a
     * minute and then keeps following the user's posture.
     *
     * A high percentile (p90) was tried first, on the reasoning that an open eye is the upper
     * part of the distribution. It made things worse and was reverted: a higher reference
     * makes every frame read *more* closed, which amplifies the confound that eye aspect ratio
     * also falls when looking **down** (§16.3). On the distracted recording it took long
     * closures from 2 to 6 and started firing the fatigue flag on a session where the user was
     * merely distracted. The median sits below the look-down frames and is the robust choice.
     */
    const val EAR_OPEN_PERCENTILE = 50.0
    const val EAR_OPEN_WINDOW_MS = 60_000L

    // ---------------------------------------------------------------- blinks

    /**
     * Shorter closures than this are detector noise, not blinks. A real human blink lasts
     * roughly 100-400 ms; 50 ms is comfortably below the shortest of them.
     */
    const val BLINK_MIN_MS = 50L

    /**
     * Longer closures than this are not counted as blinks — they are "long closures",
     * which is the drowsiness signal. 500 ms is the usual dividing line: normal blinks
     * finish well inside it, microsleeps do not.
     */
    const val BLINK_MAX_MS = 500L

    /** Window used to report blinks per minute. One minute is the unit clinicians use. */
    const val BLINK_RATE_WINDOW_MS = 60_000L

    /**
     * Blink rate is reported as null until this much data exists. Extrapolating a rate
     * from 3 seconds of camera would be a made-up number.
     */
    const val BLINK_RATE_MIN_COVERAGE_MS = 20_000L

    /**
     * Below this vision frame rate, blink *counting* is systematically incomplete and the
     * rate is labelled `undersampled` rather than presented as a measurement.
     *
     * Derived, not chosen. Measured blink durations on this device: p10 58 ms, p50 132 ms,
     * p90 282 ms (docs/SIGNALS.md §16.7). Catching an event of duration d needs a frame
     * interval under d — two samples inside the median 132 ms blink means an interval of
     * 66 ms, i.e. **15 fps**. Below that, blinks close and reopen between frames.
     *
     * The A20e runs the vision loop at 8.4-9.6 fps measured, so on the operator's phone this
     * flag reads `undersampled` **always**, and Phase 6's duty-cycling will lower it
     * further. That is the honest answer, not a bug: the ground-truth probe detected 6 of
     * 10 performed blinks at 8.4 fps (§16.7). See the architect's ruling in
     * docs/DECISIONS.md, 2026-08-02.
     */
    const val BLINK_FULL_RATE_MIN_FPS = 15.0

    /**
     * Rolling window for the effective vision frame rate. Five seconds is long enough to be
     * stable and short enough to follow Phase 6 duty-cycling as it happens.
     */
    const val VISION_FPS_WINDOW_MS = 5_000L

    // ---------------------------------------------------------------- PERCLOS

    /**
     * PERCLOS-P80, the standard definition: the fraction of time the eyes are at least
     * 80% closed. The 0.80 is the literature's, unchanged and unfitted — what changed
     * (2026-07-31) is what it is applied to. It now tests a real aperture ratio measured
     * from the lid landmarks; it used to test MediaPipe's eyeBlink confidence score, which
     * peaks at 0.73 on the A20e and so made this cutoff unreachable. See docs/SIGNALS.md
     * §5.1 for the measurements and bench/analyze_eye_scale.py for the sweep.
     */
    const val PERCLOS_CLOSED_LEVEL = 0.80

    /** PERCLOS is defined over a rolling minute. */
    const val PERCLOS_WINDOW_MS = 60_000L

    /**
     * A single frame can never account for more than this much time in a rolling window.
     * Without the cap, one 10-second stall (app backgrounded, camera hiccup) would be
     * charged entirely to whatever the last frame happened to show.
     */
    const val MAX_FRAME_WEIGHT_MS = 500L

    // ---------------------------------------------------------------- gaze

    /**
     * How far the head may turn left/right from the user's own neutral pose and still
     * count as facing the screen. A phone on a desk stand at 40-70 cm subtends a small
     * angle, but the head wanders while reading; 25 deg keeps normal reading "on screen"
     * while a glance at a second phone beside the laptop falls outside it.
     */
    const val GAZE_MAX_YAW_DEV_DEG = 25.0

    /**
     * Same for up/down. Tighter than yaw because looking down at a phone in your lap —
     * the exact "distracted" case we care about — is mostly a pitch movement.
     */
    const val GAZE_MAX_PITCH_DEV_DEG = 20.0

    /**
     * How far the iris may sit from its neutral position along the eye opening, as a
     * fraction of half the eye width, before the eyes count as looking away even though
     * the head has not moved. 0.35 of a half-width is a clear, visible side-glance.
     */
    const val GAZE_MAX_IRIS_RATIO = 0.35

    /**
     * Eyes more closed than this cannot be "on screen". Set above EYE_CLOSE_LEVEL so an
     * ordinary blink does not punch holes in the gaze trace.
     */
    const val GAZE_MAX_EYE_CLOSURE = 0.60

    /** Rolling window for the reported gaze-on-screen fraction; same minute as PERCLOS. */
    const val GAZE_WINDOW_MS = 60_000L

    // ---------------------------------------------------------------- head stability

    /**
     * Window over which head movement is measured. Ten seconds is long enough to tell
     * "settled into reading" from "shifting around", short enough to react in a session.
     */
    const val HEAD_STABILITY_WINDOW_MS = 10_000L

    /**
     * Combined yaw+pitch spread (standard deviation, degrees) below which the head counts
     * as steady. A person reading still drifts a few degrees; 6 deg allows that and
     * excludes looking around the room.
     */
    const val HEAD_STABLE_MAX_DEG = 6.0

    // ---------------------------------------------------------------- yawns (optional)

    /** jawOpen score above which the mouth counts as wide open. Speaking rarely exceeds this. */
    const val YAWN_JAW_OPEN_LEVEL = 0.60

    /** Below this the mouth counts as closed again (hysteresis, same reason as blinks). */
    const val YAWN_JAW_CLOSE_LEVEL = 0.35

    /**
     * The mouth must stay open this long to be a yawn. Yawns last 4-6 seconds in total
     * but the wide-open phase is shorter; 1.2 s excludes talking and laughing.
     */
    const val YAWN_MIN_MS = 1_200L

    /** Minimum gap between two counted yawns, so one long yawn is not counted twice. */
    const val YAWN_REFRACTORY_MS = 5_000L

    // ---------------------------------------------------------------- calibration

    /**
     * The user's own neutral head pose and neutral iris position are learned over the
     * first few seconds of a session, because "straight ahead" depends entirely on where
     * the phone sits on its stand. Five seconds is enough for a stable median and short
     * enough that the user does not notice a warm-up.
     */
    const val BASELINE_CALIBRATION_MS = 5_000L

    /** Ignore samples with no face during calibration; require at least this many good ones. */
    const val BASELINE_MIN_SAMPLES = 10
}

/**
 * Runtime copy of [SignalThresholds]. Defaults are the documented constants; tests (and
 * later phases) can vary one value without touching global state.
 */
data class SignalConfig(
    val eyeCloseLevel: Double = SignalThresholds.EYE_CLOSE_LEVEL,
    val eyeOpenLevel: Double = SignalThresholds.EYE_OPEN_LEVEL,
    val longClosureLevel: Double = SignalThresholds.LONG_CLOSURE_LEVEL,
    val longClosureOpenLevel: Double = SignalThresholds.LONG_CLOSURE_OPEN_LEVEL,
    val earOpenRef: Double = SignalThresholds.EAR_OPEN_REF,
    val earOpenPercentile: Double = SignalThresholds.EAR_OPEN_PERCENTILE,
    val earOpenWindowMs: Long = SignalThresholds.EAR_OPEN_WINDOW_MS,
    val blinkMinMs: Long = SignalThresholds.BLINK_MIN_MS,
    val blinkMaxMs: Long = SignalThresholds.BLINK_MAX_MS,
    val blinkRateWindowMs: Long = SignalThresholds.BLINK_RATE_WINDOW_MS,
    val blinkRateMinCoverageMs: Long = SignalThresholds.BLINK_RATE_MIN_COVERAGE_MS,
    val blinkFullRateMinFps: Double = SignalThresholds.BLINK_FULL_RATE_MIN_FPS,
    val visionFpsWindowMs: Long = SignalThresholds.VISION_FPS_WINDOW_MS,
    val perclosClosedLevel: Double = SignalThresholds.PERCLOS_CLOSED_LEVEL,
    val perclosWindowMs: Long = SignalThresholds.PERCLOS_WINDOW_MS,
    val maxFrameWeightMs: Long = SignalThresholds.MAX_FRAME_WEIGHT_MS,
    val gazeMaxYawDevDeg: Double = SignalThresholds.GAZE_MAX_YAW_DEV_DEG,
    val gazeMaxPitchDevDeg: Double = SignalThresholds.GAZE_MAX_PITCH_DEV_DEG,
    val gazeMaxIrisRatio: Double = SignalThresholds.GAZE_MAX_IRIS_RATIO,
    val gazeMaxEyeClosure: Double = SignalThresholds.GAZE_MAX_EYE_CLOSURE,
    val gazeWindowMs: Long = SignalThresholds.GAZE_WINDOW_MS,
    val headStabilityWindowMs: Long = SignalThresholds.HEAD_STABILITY_WINDOW_MS,
    val headStableMaxDeg: Double = SignalThresholds.HEAD_STABLE_MAX_DEG,
    val yawnJawOpenLevel: Double = SignalThresholds.YAWN_JAW_OPEN_LEVEL,
    val yawnJawCloseLevel: Double = SignalThresholds.YAWN_JAW_CLOSE_LEVEL,
    val yawnMinMs: Long = SignalThresholds.YAWN_MIN_MS,
    val yawnRefractoryMs: Long = SignalThresholds.YAWN_REFRACTORY_MS,
    val baselineCalibrationMs: Long = SignalThresholds.BASELINE_CALIBRATION_MS,
    val baselineMinSamples: Int = SignalThresholds.BASELINE_MIN_SAMPLES,
)
