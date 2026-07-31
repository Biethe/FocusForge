package com.focusforge.core

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Turns the Phase 3 signals into one 0-100 focus score and one fatigue flag.
 *
 * Every rule here is written out in plain language in docs/SIGNALS.md §15, and every
 * constant lives in [FocusThresholds] with the reason it has the value it has. The anchors
 * come from the operator's three labelled recordings (docs/SIGNALS.md §14.3) — which means
 * they are fitted to **one person on one device**, and the score is a comparison of you
 * against your own earlier minutes, not a measurement of anything absolute.
 */
object FocusThresholds {

    // ---------------------------------------------------------------- attention

    /**
     * Gaze-on-screen fraction at or below which the attention term is 0. The operator's
     * distracted session ranged 0.28-0.92 over rolling windows, so 0.40 sits inside the
     * distracted range rather than below it: looking away more than half the time should
     * already be scoring badly, not merely starting to.
     */
    const val GAZE_FLOOR = 0.40

    /**
     * ...and at or above which it is 1. The focused session never dropped below 0.98, and
     * the drowsy one topped out at 0.734, so 0.90 separates "reading" from both.
     */
    const val GAZE_CEILING = 0.90

    // ---------------------------------------------------------------- steadiness

    /** Head spread at or below which the steadiness term is 1. Focused measured 0.20-0.81 deg. */
    const val HEAD_STEADY_DEG = 2.0

    /**
     * ...and at or above which it is 0. The distracted session reached 29 deg; 15 is well
     * inside that, so repeatedly turning to a second phone drives the term to zero rather
     * than shaving a few points off it.
     */
    const val HEAD_ROAMING_DEG = 15.0

    // ---------------------------------------------------------------- alertness

    /**
     * PERCLOS at or below which the alertness term is 1. Focused measured a flat 0.000 and
     * distracted never exceeded 0.007, so anything under 0.01 is "eyes are open".
     */
    const val PERCLOS_ALERT = 0.01

    /**
     * ...and at or above which it is 0. The drowsy session's rolling windows ran
     * 0.061-0.142. Note our PERCLOS reads low by construction (§5.1): do not compare this
     * number to the ~0.15 figures in the driving literature.
     */
    const val PERCLOS_DROWSY = 0.10

    // ---------------------------------------------------------------- the mix

    /**
     * Weights, and they are a judgement call rather than a fitted result: being pointed at
     * the work is the most direct evidence of focus, eye closure is the fatigue axis the
     * coach exists to catch, and head movement is corroboration that on its own would
     * punish someone who simply fidgets. They sum to 1.
     */
    const val WEIGHT_ATTENTION = 0.50
    const val WEIGHT_ALERTNESS = 0.30
    const val WEIGHT_STEADINESS = 0.20

    /**
     * Exponential smoothing time constant for the displayed score: after this long, about
     * 63% of a step change has been absorbed. Eight seconds keeps the number from twitching
     * without hiding a real drop — and the inputs are themselves 60 s rolling windows, so
     * most of the smoothing already happened upstream.
     */
    const val SCORE_SMOOTHING_TAU_MS = 8_000L

    // ---------------------------------------------------------------- fatigue flag

    /** PERCLOS contributing no fatigue evidence, and the level contributing all of it. */
    const val FATIGUE_PERCLOS_LOW = 0.02
    const val FATIGUE_PERCLOS_HIGH = 0.12

    /**
     * Long closures per minute contributing no evidence, and full evidence. The drowsy
     * recording produced 16 in two minutes (8/min); the distracted one produced 3 (1.5/min)
     * and the focused one none at all.
     */
    const val FATIGUE_CLOSURE_RATE_LOW = 1.0
    const val FATIGUE_CLOSURE_RATE_HIGH = 6.0

    /** Window over which long closures are counted for that rate. */
    const val FATIGUE_CLOSURE_WINDOW_MS = 60_000L

    /**
     * Schmitt trigger. Evidence must reach [FATIGUE_ON_LEVEL] to raise the flag and fall
     * back under [FATIGUE_OFF_LEVEL] to clear it; the gap is what stops a value hovering on
     * the line from blinking the warning on and off.
     */
    const val FATIGUE_ON_LEVEL = 0.60
    const val FATIGUE_OFF_LEVEL = 0.35

    /**
     * ...and it must stay there this long. Fatigue is a state that builds over minutes, so
     * a single deep blink must never raise the flag. Clearing is slower than raising on
     * purpose: one alert stretch does not mean the tiredness has gone.
     */
    const val FATIGUE_ON_DWELL_MS = 15_000L
    const val FATIGUE_OFF_DWELL_MS = 30_000L
}

/** Runtime copy of [FocusThresholds], so a test can vary one value without global state. */
data class FocusConfig(
    val gazeFloor: Double = FocusThresholds.GAZE_FLOOR,
    val gazeCeiling: Double = FocusThresholds.GAZE_CEILING,
    val headSteadyDeg: Double = FocusThresholds.HEAD_STEADY_DEG,
    val headRoamingDeg: Double = FocusThresholds.HEAD_ROAMING_DEG,
    val perclosAlert: Double = FocusThresholds.PERCLOS_ALERT,
    val perclosDrowsy: Double = FocusThresholds.PERCLOS_DROWSY,
    val weightAttention: Double = FocusThresholds.WEIGHT_ATTENTION,
    val weightAlertness: Double = FocusThresholds.WEIGHT_ALERTNESS,
    val weightSteadiness: Double = FocusThresholds.WEIGHT_STEADINESS,
    val smoothingTauMs: Long = FocusThresholds.SCORE_SMOOTHING_TAU_MS,
    val fatiguePerclosLow: Double = FocusThresholds.FATIGUE_PERCLOS_LOW,
    val fatiguePerclosHigh: Double = FocusThresholds.FATIGUE_PERCLOS_HIGH,
    val fatigueClosureRateLow: Double = FocusThresholds.FATIGUE_CLOSURE_RATE_LOW,
    val fatigueClosureRateHigh: Double = FocusThresholds.FATIGUE_CLOSURE_RATE_HIGH,
    val fatigueClosureWindowMs: Long = FocusThresholds.FATIGUE_CLOSURE_WINDOW_MS,
    val fatigueOnLevel: Double = FocusThresholds.FATIGUE_ON_LEVEL,
    val fatigueOffLevel: Double = FocusThresholds.FATIGUE_OFF_LEVEL,
    val fatigueOnDwellMs: Long = FocusThresholds.FATIGUE_ON_DWELL_MS,
    val fatigueOffDwellMs: Long = FocusThresholds.FATIGUE_OFF_DWELL_MS,
)

/** The fused result for one instant. */
data class FocusState(
    val timestampMs: Long,
    val elapsedMs: Long,
    /** The number on screen: 0-100, smoothed. */
    val score: Int,
    /** Before smoothing — kept so a test can see the raw fusion and the export can log it. */
    val rawScore: Double,
    /** The three terms, 0..1, so the UI and the coach can say *why* the score is what it is. */
    val attention: Double,
    val alertness: Double,
    val steadiness: Double,
    /** True while the fatigue flag is raised. Hysteresis and dwell applied. */
    val fatigue: Boolean,
    /** 0..1 evidence behind that flag, before the trigger. */
    val fatigueEvidence: Double,
    /**
     * False until the neutral pose is calibrated. The score is still computed, but it is
     * measured against an origin that is still moving, so the UI says "warming up" rather
     * than showing a number that will shift under the user.
     */
    val ready: Boolean,
)

/** Whole-session totals for the dashboard and the export. */
data class SessionSummary(
    val durationMs: Long,
    val samples: Int,
    /** Time-weighted, so a frame-rate change cannot move it (same reasoning as §8). */
    val meanScore: Double,
    val minScore: Int,
    val maxScore: Int,
    /** Fraction of the session with the fatigue flag raised. */
    val fatigueFraction: Double,
    val signals: CumulativeSignals,
)

/**
 * Feeds [SignalSnapshot]s in, gets [FocusState]s out. Pure Kotlin, no clock of its own —
 * every timestamp comes from the caller, which is what lets a recorded session be replayed
 * in a test and produce identical numbers.
 */
class FocusScorer(private val config: FocusConfig = FocusConfig()) {

    private val fatigueWindow = ArrayDeque<Pair<Long, Int>>() // (timestamp, cumulative closures)

    private var firstTimestampMs: Long? = null
    private var lastTimestampMs: Long = 0L
    private var smoothed: Double? = null
    private var samples = 0

    private var fatigueRaised = false
    /** When the evidence first crossed the trigger it is currently heading towards. */
    private var crossingSinceMs: Long? = null

    // Session totals are accumulated as running weighted sums rather than kept as a list of
    // samples: a two-hour session at 9 fps would otherwise hold 65 000 entries for numbers
    // that only ever get averaged.
    private var scoreWeightSum = 0L
    private var scoreWeightedTotal = 0.0
    private var fatigueWeightSum = 0L
    private var minScore = 100
    private var maxScore = 0

    fun update(snapshot: SignalSnapshot): FocusState {
        val t = snapshot.timestampMs
        val start = firstTimestampMs ?: t.also { firstTimestampMs = it }
        val previous = if (samples == 0) null else lastTimestampMs
        lastTimestampMs = t
        samples++

        // --- the three terms ------------------------------------------------------
        val attention = ramp(snapshot.gazeOnScreenFraction, config.gazeFloor, config.gazeCeiling)
        val steadiness = ramp(snapshot.headStabilityDeg, config.headRoamingDeg, config.headSteadyDeg)
        val alertness = ramp(snapshot.perclos, config.perclosDrowsy, config.perclosAlert)

        val raw = 100.0 * (
            config.weightAttention * attention +
                config.weightAlertness * alertness +
                config.weightSteadiness * steadiness
            )

        // --- smoothing ------------------------------------------------------------
        // Exponential, but with the coefficient derived from the actual gap between
        // frames rather than assumed: at 9 fps and at 3 fps the score must settle at the
        // same rate in *seconds*, or Phase 6's duty-cycling would change the UI's feel.
        val dtMs = if (previous == null) 0L else (t - previous).coerceAtLeast(0L)
        val current = smoothed
        val next = if (current == null || dtMs == 0L) {
            current ?: raw
        } else {
            val alpha = 1.0 - exp(-dtMs.toDouble() / config.smoothingTauMs)
            current + alpha * (raw - current)
        }
        smoothed = next
        val score = next.coerceIn(0.0, 100.0).roundToInt()

        // --- fatigue --------------------------------------------------------------
        val closureRate = longClosureRatePerMinute(t, snapshot.longClosureCount)
        val evidence = maxOf(
            ramp(snapshot.perclos, config.fatiguePerclosLow, config.fatiguePerclosHigh),
            ramp(closureRate, config.fatigueClosureRateLow, config.fatigueClosureRateHigh),
        )
        updateFatigueFlag(t, evidence)

        // --- session totals -------------------------------------------------------
        if (previous != null) {
            val weight = (t - previous).coerceIn(0L, SignalThresholds.MAX_FRAME_WEIGHT_MS)
            scoreWeightSum += weight
            scoreWeightedTotal += weight * next
            if (fatigueRaised) fatigueWeightSum += weight
        }
        if (score < minScore) minScore = score
        if (score > maxScore) maxScore = score

        return FocusState(
            timestampMs = t,
            elapsedMs = t - start,
            score = score,
            rawScore = raw,
            attention = attention,
            alertness = alertness,
            steadiness = steadiness,
            fatigue = fatigueRaised,
            fatigueEvidence = evidence,
            ready = snapshot.calibrated,
        )
    }

    fun summary(signals: CumulativeSignals): SessionSummary = SessionSummary(
        durationMs = firstTimestampMs?.let { lastTimestampMs - it } ?: 0L,
        samples = samples,
        meanScore = if (scoreWeightSum == 0L) 0.0 else scoreWeightedTotal / scoreWeightSum,
        minScore = if (samples == 0) 0 else minScore,
        maxScore = if (samples == 0) 0 else maxScore,
        fatigueFraction = if (scoreWeightSum == 0L) 0.0 else fatigueWeightSum.toDouble() / scoreWeightSum,
        signals = signals,
    )

    fun reset() {
        fatigueWindow.clear()
        firstTimestampMs = null
        lastTimestampMs = 0L
        smoothed = null
        samples = 0
        fatigueRaised = false
        crossingSinceMs = null
        scoreWeightSum = 0L
        scoreWeightedTotal = 0.0
        fatigueWeightSum = 0L
        minScore = 100
        maxScore = 0
    }

    /**
     * Long closures per minute over the last minute, from the cumulative count the engine
     * reports. Uses the *measured* span rather than a fixed 60 s, so the first minute of a
     * session does not report an artificially low rate.
     */
    private fun longClosureRatePerMinute(timestampMs: Long, cumulativeCount: Int): Double {
        fatigueWindow.addLast(timestampMs to cumulativeCount)
        while (fatigueWindow.size > 1 &&
            timestampMs - fatigueWindow.first().first > config.fatigueClosureWindowMs
        ) {
            fatigueWindow.removeFirst()
        }
        val oldest = fatigueWindow.first()
        val spanMs = timestampMs - oldest.first
        if (spanMs <= 0L) return 0.0
        return (cumulativeCount - oldest.second) * 60_000.0 / spanMs
    }

    /**
     * Schmitt trigger with a dwell time on each edge. The flag only moves when the evidence
     * has been past the relevant level *continuously* for long enough; any excursion back
     * across it restarts the clock, which is what keeps the warning from flickering.
     */
    private fun updateFatigueFlag(timestampMs: Long, evidence: Double) {
        val wantsRaise = !fatigueRaised && evidence >= config.fatigueOnLevel
        val wantsClear = fatigueRaised && evidence <= config.fatigueOffLevel
        if (!wantsRaise && !wantsClear) {
            crossingSinceMs = null
            return
        }
        val since = crossingSinceMs ?: timestampMs.also { crossingSinceMs = it }
        val dwellMs = if (fatigueRaised) config.fatigueOffDwellMs else config.fatigueOnDwellMs
        if (timestampMs - since >= dwellMs) {
            fatigueRaised = !fatigueRaised
            crossingSinceMs = null
        }
    }

    /**
     * Linear ramp from 0 at [atZero] to 1 at [atOne], clamped outside. Works in either
     * direction, so "more is worse" signals (PERCLOS, head spread) read the same way as
     * "more is better" ones.
     */
    private fun ramp(value: Double, atZero: Double, atOne: Double): Double {
        if (atOne == atZero) return if (value >= atOne) 1.0 else 0.0
        return ((value - atZero) / (atOne - atZero)).coerceIn(0.0, 1.0)
    }
}
