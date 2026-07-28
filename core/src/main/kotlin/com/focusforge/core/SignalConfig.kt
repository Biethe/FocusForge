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
     * Above this, the eye counts as "closing". 0.50 is the midpoint of MediaPipe's
     * eyeBlink score: below it the lid is clearly up, above it clearly coming down.
     */
    const val EYE_CLOSE_LEVEL = 0.50

    /**
     * Below this, the eye counts as "open" again. The 0.15 gap under EYE_CLOSE_LEVEL is
     * hysteresis: without it a score hovering around 0.50 would emit dozens of fake
     * blinks per second.
     */
    const val EYE_OPEN_LEVEL = 0.35

    /**
     * Eye-aspect-ratio value treated as fully open when blendshapes are unavailable.
     * Typical open-eye EAR in the literature sits around 0.25-0.30.
     */
    const val EAR_OPEN_REF = 0.28

    /** EAR value treated as fully closed in the same fallback. Closed eyes measure ~0.10-0.15. */
    const val EAR_CLOSED_REF = 0.13

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

    // ---------------------------------------------------------------- PERCLOS

    /**
     * PERCLOS-P80, the standard definition: the fraction of time the eyes are at least
     * 80% closed. We use MediaPipe's eyeBlink score directly as the "percent closed".
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
    val earOpenRef: Double = SignalThresholds.EAR_OPEN_REF,
    val earClosedRef: Double = SignalThresholds.EAR_CLOSED_REF,
    val blinkMinMs: Long = SignalThresholds.BLINK_MIN_MS,
    val blinkMaxMs: Long = SignalThresholds.BLINK_MAX_MS,
    val blinkRateWindowMs: Long = SignalThresholds.BLINK_RATE_WINDOW_MS,
    val blinkRateMinCoverageMs: Long = SignalThresholds.BLINK_RATE_MIN_COVERAGE_MS,
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
