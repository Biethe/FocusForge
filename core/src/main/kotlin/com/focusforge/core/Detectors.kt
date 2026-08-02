package com.focusforge.core

import kotlin.math.sqrt

/**
 * Counts blinks and long eye closures from the eye-closure trace, with hysteresis.
 *
 * **Two state machines, deliberately, on two different threshold pairs.** A blink is a
 * transient dip we only need to notice, so it runs on the sensitive pair
 * ([SignalConfig.eyeCloseLevel] / [SignalConfig.eyeOpenLevel]). A long closure feeds the
 * fatigue alarm, so it runs on the stricter pair ([SignalConfig.longClosureLevel] /
 * [SignalConfig.longClosureOpenLevel]) — sharing one machine forced a single compromise
 * that either missed half the blinks or turned "looking down at a second phone" into a
 * string of multi-second closures. The measurements behind both pairs are in SignalConfig
 * and docs/SIGNALS.md §16.
 *
 * A closure on the blink machine is a blink when it lasts between [SignalConfig.blinkMinMs]
 * and [SignalConfig.blinkMaxMs]. Anything shorter is detector noise.
 */
class BlinkDetector(private val config: SignalConfig) {

    /** One hysteresis state machine over the closure trace. */
    private class Closure(private val enter: Double, private val exit: Double) {
        var closed = false
            private set
        var sinceMs = 0L
            private set

        fun reset() { closed = false; sinceMs = 0L }

        /** Returns the closure's duration when one ends on this sample, else null. */
        fun update(timestampMs: Long, closure: Double): Long? {
            if (!closed) {
                if (closure >= enter) {
                    closed = true
                    sinceMs = timestampMs
                }
                return null
            }
            if (closure <= exit) {
                closed = false
                return timestampMs - sinceMs
            }
            return null
        }
    }

    private val blinkMachine = Closure(config.eyeCloseLevel, config.eyeOpenLevel)
    private val longMachine = Closure(config.longClosureLevel, config.longClosureOpenLevel)
    private var longClosureCounted = false
    private val blinkTimesMs = ArrayDeque<Long>()

    var blinkCount = 0
        private set
    var longClosureCount = 0
        private set
    var lastBlinkDurationMs: Long? = null
        private set

    /** @param measurable false when there is no face, or the eyes cannot be scored at all. */
    fun update(timestampMs: Long, closure: Double, measurable: Boolean) {
        if (!measurable) {
            // We cannot see the eyes; abandon any closure in progress rather than guess
            // how long it lasted.
            blinkMachine.reset()
            longMachine.reset()
            longClosureCounted = false
            return
        }

        blinkMachine.update(timestampMs, closure)?.let { durationMs ->
            if (durationMs in config.blinkMinMs..config.blinkMaxMs) {
                blinkCount++
                lastBlinkDurationMs = durationMs
                blinkTimesMs.addLast(timestampMs)
            }
        }

        // The long-closure machine counts as soon as the duration passes the blink ceiling,
        // rather than waiting for the eye to reopen, so a closure in progress is already
        // visible to the fatigue flag.
        if (!longMachine.closed) longClosureCounted = false
        longMachine.update(timestampMs, closure)
        if (longMachine.closed && !longClosureCounted &&
            timestampMs - longMachine.sinceMs > config.blinkMaxMs
        ) {
            longClosureCount++
            longClosureCounted = true
        }
    }

    /** True while a closure is in progress (used by the live debug view). */
    val eyesCurrentlyClosed: Boolean get() = blinkMachine.closed

    /**
     * Blinks per minute over the rolling window, or null while there is too little data to
     * state one honestly.
     */
    fun ratePerMinute(nowMs: Long, elapsedMs: Long): Double? {
        while (blinkTimesMs.isNotEmpty() && nowMs - blinkTimesMs.first() > config.blinkRateWindowMs) {
            blinkTimesMs.removeFirst()
        }
        val coverageMs = minOf(elapsedMs, config.blinkRateWindowMs)
        if (coverageMs < config.blinkRateMinCoverageMs) return null
        return blinkTimesMs.size * 60_000.0 / coverageMs
    }

    fun reset() {
        blinkMachine.reset()
        longMachine.reset()
        longClosureCounted = false
        blinkTimesMs.clear()
        blinkCount = 0
        longClosureCount = 0
        lastBlinkDurationMs = null
    }
}

/**
 * Optional fatigue signal: counts yawns as a wide-open jaw sustained past
 * [SignalConfig.yawnMinMs], with hysteresis on the way out and a refractory period so one
 * long yawn cannot be counted twice.
 */
class YawnDetector(private val config: SignalConfig) {

    private var jawOpen = false
    private var openSinceMs = 0L
    private var currentCounted = false
    private var lastYawnMs: Long? = null

    var yawnCount = 0
        private set

    fun update(timestampMs: Long, jawOpenScore: Double?, measurable: Boolean) {
        if (!measurable || jawOpenScore == null) {
            jawOpen = false
            currentCounted = false
            return
        }
        if (!jawOpen) {
            if (jawOpenScore >= config.yawnJawOpenLevel) {
                jawOpen = true
                openSinceMs = timestampMs
                currentCounted = false
            }
            return
        }
        val durationMs = timestampMs - openSinceMs
        val previous = lastYawnMs
        val outOfRefractory = previous == null || timestampMs - previous >= config.yawnRefractoryMs
        if (!currentCounted && durationMs >= config.yawnMinMs && outOfRefractory) {
            yawnCount++
            currentCounted = true
            lastYawnMs = timestampMs
        }
        if (jawOpenScore <= config.yawnJawCloseLevel) {
            jawOpen = false
        }
    }

    fun reset() {
        jawOpen = false
        openSinceMs = 0L
        currentCounted = false
        lastYawnMs = null
        yawnCount = 0
    }
}

/**
 * How much the head has been moving: the combined spread (standard deviation) of yaw and
 * pitch over the last [SignalConfig.headStabilityWindowMs]. Small number = settled into
 * the task; large number = looking around.
 */
class HeadStabilityWindow(private val config: SignalConfig) {

    private class Entry(val timestampMs: Long, val yawDeg: Double, val pitchDeg: Double)

    private val entries = ArrayDeque<Entry>()

    fun add(timestampMs: Long, yawDeg: Double, pitchDeg: Double) {
        entries.addLast(Entry(timestampMs, yawDeg, pitchDeg))
        while (entries.isNotEmpty() &&
            timestampMs - entries.first().timestampMs > config.headStabilityWindowMs
        ) {
            entries.removeFirst()
        }
    }

    /** sqrt(var(yaw) + var(pitch)) in degrees; 0 until there are at least two samples. */
    fun spreadDeg(): Double {
        if (entries.size < 2) return 0.0
        return sqrt(variance(entries.map { it.yawDeg }) + variance(entries.map { it.pitchDeg }))
    }

    fun sampleCount(): Int = entries.size

    fun reset() = entries.clear()

    private fun variance(values: List<Double>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}

/**
 * Learns what "facing the screen" means *for this user, on this stand*.
 *
 * There is no universal neutral head pose: it depends entirely on where the phone sits
 * and how tall the person is. Over the first [SignalConfig.baselineCalibrationMs] we take
 * the median yaw, pitch and iris offset and treat that as zero; every gaze threshold is
 * then a deviation from it. Median rather than mean so a blink or a stretch during
 * calibration does not shift the origin.
 */
class BaselineCalibrator(private val config: SignalConfig) {

    private val yaws = ArrayList<Double>()
    private val pitches = ArrayList<Double>()
    private val irisRatios = ArrayList<Double>()
    /** (timestamp, EAR) over the rolling window — see SignalThresholds.EAR_OPEN_WINDOW_MS. */
    private val ears = ArrayDeque<Pair<Long, Double>>()
    private var firstTimestampMs: Long? = null

    var calibrated = false
        private set
    var yawDeg = 0.0
        private set
    var pitchDeg = 0.0
        private set
    var irisRatio = 0.0
        private set

    /**
     * The eye aspect ratio of *this user's* open eye, which is what eye closure is measured
     * against (see [SignalEngine]). Eye shape varies enough between people, and with the
     * distance to the stand, that a fixed reference is meaningless; before any face has
     * been seen it holds [SignalConfig.earOpenRef] as a starting value.
     *
     * Median for the same reason as the pose values: the calibration window contains
     * blinks, and a mean would let them pull the open reference down.
     */
    var earOpen = config.earOpenRef
        private set

    /**
     * The pose baseline freezes once calibrated; [earOpen] does not, because it is a rolling
     * estimate that must survive a change of posture (and a session started while looking
     * down at the phone).
     */
    fun update(timestampMs: Long, orientation: Orientation?, irisH: Double?, ear: Double?) {
        updateEarOpen(timestampMs, ear)
        if (calibrated) return
        val start = firstTimestampMs ?: timestampMs.also { firstTimestampMs = it }
        if (orientation != null) {
            yaws += orientation.yawDeg
            pitches += orientation.pitchDeg
        }
        if (irisH != null) irisRatios += irisH

        yawDeg = median(yaws)
        pitchDeg = median(pitches)
        irisRatio = median(irisRatios)
        // One frame must not be allowed to define "open": a session that starts mid-blink
        // would set the reference to a shut eye and then read every closure as zero. Until
        // there are enough samples to be meaningful, the literature default stands.

        val enoughTime = timestampMs - start >= config.baselineCalibrationMs
        val enoughSamples = yaws.size >= config.baselineMinSamples
        if (enoughTime && enoughSamples) calibrated = true
    }

    /** Rolling high percentile of the eye aspect ratio — this user's open eye, continuously. */
    private fun updateEarOpen(timestampMs: Long, ear: Double?) {
        if (ear == null || ear <= 0.0) return
        ears.addLast(timestampMs to ear)
        while (ears.isNotEmpty() && timestampMs - ears.first().first > config.earOpenWindowMs) {
            ears.removeFirst()
        }
        // One frame must not define "open": a session starting mid-blink would set the
        // reference to a shut eye and then read every later closure as zero.
        if (ears.size >= config.baselineMinSamples) {
            earOpen = percentile(ears.map { it.second }, config.earOpenPercentile)
        }
    }

    private fun percentile(values: List<Double>, pct: Double): Double {
        if (values.isEmpty()) return config.earOpenRef
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * pct / 100.0).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    fun reset() {
        yaws.clear(); pitches.clear(); irisRatios.clear(); ears.clear()
        firstTimestampMs = null
        calibrated = false
        yawDeg = 0.0; pitchDeg = 0.0; irisRatio = 0.0
        earOpen = config.earOpenRef
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
