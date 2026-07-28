package com.focusforge.core

import kotlin.math.abs

/** Everything the pipeline knows at one instant. Phase 4 fuses these into a focus score. */
data class SignalSnapshot(
    val timestampMs: Long,
    val elapsedMs: Long,
    /** False until the per-user neutral head pose has been learned (first few seconds). */
    val calibrated: Boolean,
    val faceVisible: Boolean,

    /** 0 = eyes wide open, 1 = fully closed. Null when the eyes cannot be scored. */
    val eyeClosure: Double?,
    val eyesClosedNow: Boolean,
    val blinkCount: Int,
    /** Blinks per minute over the last minute; null until there is enough data to say. */
    val blinkRatePerMin: Double?,
    val lastBlinkDurationMs: Long?,
    /** Closures longer than a blink — the drowsiness marker. */
    val longClosureCount: Int,

    /** Fraction of the last minute with the eyes at least 80% closed (PERCLOS-P80). */
    val perclos: Double,
    /** Measurable milliseconds inside the PERCLOS window — how much to trust [perclos]. */
    val perclosCoverageMs: Long,

    val gazeOnScreen: Boolean,
    /** Fraction of the last minute spent looking at the screen. */
    val gazeOnScreenFraction: Double,

    /** Head angles relative to the learned neutral pose, in degrees. */
    val headYawDevDeg: Double?,
    val headPitchDevDeg: Double?,
    val headRollDeg: Double?,
    /** Iris offset relative to neutral, as a fraction of half the eye width. */
    val irisHorizontalDev: Double?,

    /** Combined yaw+pitch spread over the last 10 s, degrees. Smaller = steadier. */
    val headStabilityDeg: Double,
    val headStable: Boolean,

    val yawnCount: Int,
)

/** Whole-run totals, used by the replay summaries rather than the rolling snapshots. */
data class CumulativeSignals(
    val durationMs: Long,
    val samples: Int,
    val faceVisibleFraction: Double,
    /** PERCLOS over the entire run, not a rolling minute. */
    val perclos: Double,
    val gazeOnScreenFraction: Double,
    val blinkCount: Int,
    val blinkRatePerMin: Double,
    val longClosureCount: Int,
    val yawnCount: Int,
    val meanHeadStabilityDeg: Double,
)

/**
 * Feeds [FaceSample]s in, gets [SignalSnapshot]s out. Pure Kotlin, no Android, no threads,
 * no clock of its own — every timestamp comes from the caller, which is exactly what lets
 * a recorded stream be replayed in a unit test and produce identical numbers.
 *
 * The formulas and the reasoning behind every threshold are in docs/SIGNALS.md.
 */
class SignalEngine(private val config: SignalConfig = SignalConfig()) {

    private val blink = BlinkDetector(config)
    private val yawn = YawnDetector(config)
    private val headStability = HeadStabilityWindow(config)
    private val baseline = BaselineCalibrator(config)
    private val perclosWindow = TimeWeightedWindow(config.perclosWindowMs, config.maxFrameWeightMs)
    private val gazeWindow = TimeWeightedWindow(config.gazeWindowMs, config.maxFrameWeightMs)
    private val faceWindow = TimeWeightedWindow(config.gazeWindowMs, config.maxFrameWeightMs)

    private var firstTimestampMs: Long? = null
    private var lastTimestampMs: Long = 0L
    private var samples = 0
    private var stabilitySum = 0.0
    private var stabilitySamples = 0

    fun update(sample: FaceSample): SignalSnapshot {
        val t = sample.timestampMs
        val start = firstTimestampMs ?: t.also { firstTimestampMs = it }
        val elapsed = t - start
        lastTimestampMs = t
        samples++

        val closure = eyeClosure(sample)
        val orientation = HeadPose.fromMatrix(sample.matrix)
        val irisH = if (sample.faceVisible) {
            EyeGeometry.irisHorizontalRatio(sample.landmarks, sample.imageWidth)
        } else null

        if (sample.faceVisible) baseline.update(t, orientation, irisH)

        val yawDev = orientation?.let { it.yawDeg - baseline.yawDeg }
        val pitchDev = orientation?.let { it.pitchDeg - baseline.pitchDeg }
        val irisDev = irisH?.let { it - baseline.irisRatio }

        // --- eyes -----------------------------------------------------------------
        val eyesMeasurable = sample.faceVisible && closure != null
        blink.update(t, closure ?: 0.0, eyesMeasurable)
        // PERCLOS excludes time when we cannot see the eyes at all: reporting "eyes not
        // closed" for a frame with no face would quietly understate a user who walked off.
        perclosWindow.add(t, (closure ?: 0.0) >= config.perclosClosedLevel, counted = eyesMeasurable)

        // --- gaze -----------------------------------------------------------------
        // No face at all counts as "not looking at the screen" — that is a real answer,
        // not a missing measurement. A visible face with no usable head pose is excluded.
        val gazeMeasurable = !sample.faceVisible || orientation != null
        val onScreen = sample.faceVisible &&
            orientation != null &&
            (closure ?: 0.0) < config.gazeMaxEyeClosure &&
            abs(yawDev ?: 0.0) <= config.gazeMaxYawDevDeg &&
            abs(pitchDev ?: 0.0) <= config.gazeMaxPitchDevDeg &&
            (irisDev == null || abs(irisDev) <= config.gazeMaxIrisRatio)
        gazeWindow.add(t, onScreen, counted = gazeMeasurable)
        faceWindow.add(t, sample.faceVisible)

        // --- head -----------------------------------------------------------------
        if (yawDev != null && pitchDev != null) headStability.add(t, yawDev, pitchDev)
        val spread = headStability.spreadDeg()
        if (headStability.sampleCount() >= 2) {
            stabilitySum += spread
            stabilitySamples++
        }

        // --- jaw ------------------------------------------------------------------
        yawn.update(t, sample.blendshapes[Blendshapes.JAW_OPEN]?.toDouble(), sample.faceVisible)

        return SignalSnapshot(
            timestampMs = t,
            elapsedMs = elapsed,
            calibrated = baseline.calibrated,
            faceVisible = sample.faceVisible,
            eyeClosure = closure,
            eyesClosedNow = blink.eyesCurrentlyClosed,
            blinkCount = blink.blinkCount,
            blinkRatePerMin = blink.ratePerMinute(t, elapsed),
            lastBlinkDurationMs = blink.lastBlinkDurationMs,
            longClosureCount = blink.longClosureCount,
            perclos = perclosWindow.fraction(),
            perclosCoverageMs = perclosWindow.coverageMs(),
            gazeOnScreen = onScreen,
            gazeOnScreenFraction = gazeWindow.fraction(),
            headYawDevDeg = yawDev,
            headPitchDevDeg = pitchDev,
            headRollDeg = orientation?.rollDeg,
            irisHorizontalDev = irisDev,
            headStabilityDeg = spread,
            headStable = spread <= config.headStableMaxDeg,
            yawnCount = yawn.yawnCount,
        )
    }

    fun cumulative(): CumulativeSignals {
        val durationMs = firstTimestampMs?.let { lastTimestampMs - it } ?: 0L
        return CumulativeSignals(
            durationMs = durationMs,
            samples = samples,
            faceVisibleFraction = faceWindow.cumulativeFraction(),
            perclos = perclosWindow.cumulativeFraction(),
            gazeOnScreenFraction = gazeWindow.cumulativeFraction(),
            blinkCount = blink.blinkCount,
            blinkRatePerMin = if (durationMs <= 0L) 0.0 else blink.blinkCount * 60_000.0 / durationMs,
            longClosureCount = blink.longClosureCount,
            yawnCount = yawn.yawnCount,
            meanHeadStabilityDeg = if (stabilitySamples == 0) 0.0 else stabilitySum / stabilitySamples,
        )
    }

    fun reset() {
        blink.reset(); yawn.reset(); headStability.reset(); baseline.reset()
        perclosWindow.reset(); gazeWindow.reset(); faceWindow.reset()
        firstTimestampMs = null
        lastTimestampMs = 0L
        samples = 0
        stabilitySum = 0.0
        stabilitySamples = 0
    }

    /**
     * How closed the eyes are, 0..1.
     *
     * Primary source is MediaPipe's eyeBlink blendshape, averaged over both eyes so that a
     * one-eye glitch cannot fake a blink. If blendshapes are missing we fall back to the
     * eye aspect ratio computed straight from the lid landmarks, mapped onto the same 0..1
     * scale. Null when neither is available.
     */
    private fun eyeClosure(sample: FaceSample): Double? {
        if (!sample.faceVisible) return null
        val left = sample.blendshapes[Blendshapes.EYE_BLINK_LEFT]?.toDouble()
        val right = sample.blendshapes[Blendshapes.EYE_BLINK_RIGHT]?.toDouble()
        val fromBlendshapes = when {
            left != null && right != null -> (left + right) / 2.0
            else -> left ?: right
        }
        if (fromBlendshapes != null) return fromBlendshapes.coerceIn(0.0, 1.0)

        val ear = EyeGeometry.meanEyeAspectRatio(
            sample.landmarks, sample.imageWidth, sample.imageHeight,
        ) ?: return null
        val span = config.earOpenRef - config.earClosedRef
        if (span <= 0.0) return null
        return ((config.earOpenRef - ear) / span).coerceIn(0.0, 1.0)
    }
}
