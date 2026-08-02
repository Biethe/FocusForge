package com.focusforge.core

import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether the vision loop was fast enough for the blink *count* to mean anything.
 *
 * Blinks are events, not states: a 130 ms blink can open and close entirely between two
 * frames at 9 fps, so the count is a floor rather than a measurement (docs/SIGNALS.md
 * §16.7). PERCLOS, long closures, gaze and head stability are unaffected — they measure
 * states that persist across many frames, which is why the focus score rests on those.
 *
 * The evidence rule forbids presenting an undercount as a measurement, so this travels with
 * every export and greys the number out in the UI.
 */
@Serializable
enum class BlinkRateValidity {
    @SerialName("full-rate") FULL_RATE,
    @SerialName("undersampled") UNDERSAMPLED,
    ;

    val wire: String get() = if (this == FULL_RATE) "full-rate" else "undersampled"
}

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
    /**
     * The calibrated open-eye aspect ratio every closure is measured against (§3). Exposed
     * because it *scales* every eye number: if calibration happens while the user is
     * looking down or squinting, this lands low and every later closure reads shallow.
     * Having it in the export makes that failure visible instead of mysterious.
     */
    val earOpen: Double = 0.0,

    /** Effective vision frame rate over the last few seconds. 0 until two frames exist. */
    val visionFps: Double = 0.0,
    /**
     * Whether [blinkRatePerMin] is a measurement or a floor. Never present an
     * `UNDERSAMPLED` rate as a number without saying so (docs/DECISIONS.md 2026-08-02).
     */
    val blinkRateValidity: BlinkRateValidity = BlinkRateValidity.UNDERSAMPLED,
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
    /** Mean effective vision frame rate over the whole run. */
    val meanVisionFps: Double = 0.0,
    /** `UNDERSAMPLED` if the run ever ran too slow to count blinks properly. */
    val blinkRateValidity: BlinkRateValidity = BlinkRateValidity.UNDERSAMPLED,
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

    /** Frame arrival times, for the effective vision frame rate. */
    private val frameTimesMs = ArrayDeque<Long>()
    private var fpsSum = 0.0
    private var fpsSamples = 0
    private var everUndersampled = false

    fun update(sample: FaceSample): SignalSnapshot {
        val t = sample.timestampMs
        val start = firstTimestampMs ?: t.also { firstTimestampMs = it }
        val elapsed = t - start
        lastTimestampMs = t
        samples++

        val fps = updateVisionFps(t)
        val validity = if (fps >= config.blinkFullRateMinFps) {
            BlinkRateValidity.FULL_RATE
        } else {
            BlinkRateValidity.UNDERSAMPLED
        }
        // A run is only "full-rate" if it never dropped below the line — one duty-cycled
        // stretch is enough to make the whole run's blink count a floor.
        if (validity == BlinkRateValidity.UNDERSAMPLED && fpsSamples > 0) everUndersampled = true

        val orientation = HeadPose.fromMatrix(sample.matrix)
        val irisH = if (sample.faceVisible) {
            EyeGeometry.irisHorizontalRatio(sample.landmarks, sample.imageWidth)
        } else null
        val ear = if (sample.faceVisible) {
            EyeGeometry.meanEyeAspectRatio(sample.landmarks, sample.imageWidth, sample.imageHeight)
        } else null

        // The baseline sees this frame before closure is measured against it, so that the
        // very first frame already has an open-eye reference to divide by.
        if (sample.faceVisible) baseline.update(t, orientation, irisH, ear)
        val closure = eyeClosure(sample, ear)

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
            earOpen = baseline.earOpen,
            visionFps = fps,
            blinkRateValidity = validity,
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
            meanVisionFps = if (fpsSamples == 0) 0.0 else fpsSum / fpsSamples,
            blinkRateValidity = if (everUndersampled || fpsSamples == 0) {
                BlinkRateValidity.UNDERSAMPLED
            } else {
                BlinkRateValidity.FULL_RATE
            },
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
        frameTimesMs.clear()
        fpsSum = 0.0
        fpsSamples = 0
        everUndersampled = false
    }

    /**
     * Effective vision frame rate over a rolling window — what the loop is *actually*
     * delivering, not what the camera was asked for. Phase 6 duty-cycles this deliberately,
     * so it has to be measured rather than assumed.
     */
    private fun updateVisionFps(timestampMs: Long): Double {
        frameTimesMs.addLast(timestampMs)
        while (frameTimesMs.size > 2 &&
            timestampMs - frameTimesMs.first() > config.visionFpsWindowMs
        ) {
            frameTimesMs.removeFirst()
        }
        if (frameTimesMs.size < 2) return 0.0
        val spanMs = frameTimesMs.last() - frameTimesMs.first()
        if (spanMs <= 0L) return 0.0
        val fps = (frameTimesMs.size - 1) * 1000.0 / spanMs
        fpsSum += fps
        fpsSamples++
        return fps
    }

    /**
     * How closed the eyes are, as **the fraction of the eye's own opening that has been
     * lost**: 0 = open as wide as this user's calibrated neutral, 1 = shut.
     *
     * ```
     * eyeClosure = 1 - (eye aspect ratio now / this user's open eye aspect ratio)
     * ```
     *
     * Primary source is the geometry of the lid landmarks, because that is a *ratio of
     * physical aperture* — the quantity PERCLOS and the blink literature are defined
     * against. MediaPipe's eyeBlink blendshape is a model confidence on an arbitrary
     * scale, and measurement on the A20e showed it never exceeding 0.73 with the eyes
     * fully shut, which made the standard P80 cutoff unreachable (docs/SIGNALS.md §5.1).
     * It is kept as the fallback for frames where the lid points are missing but the
     * blendshapes are not; both eyes are averaged there so a one-eye glitch cannot fake a
     * blink. Null when neither source is available.
     *
     * Note that the two sources are not on the same scale — that is the entire point — so
     * a stream that switches between them mid-session will show a step. In practice the
     * detector emits both or neither.
     */
    private fun eyeClosure(sample: FaceSample, ear: Double?): Double? {
        if (!sample.faceVisible) return null
        if (ear != null && baseline.earOpen > 0.0) {
            return (1.0 - ear / baseline.earOpen).coerceIn(0.0, 1.0)
        }
        val left = sample.blendshapes[Blendshapes.EYE_BLINK_LEFT]?.toDouble()
        val right = sample.blendshapes[Blendshapes.EYE_BLINK_RIGHT]?.toDouble()
        val fromBlendshapes = when {
            left != null && right != null -> (left + right) / 2.0
            else -> left ?: right
        }
        return fromBlendshapes?.coerceIn(0.0, 1.0)
    }
}
