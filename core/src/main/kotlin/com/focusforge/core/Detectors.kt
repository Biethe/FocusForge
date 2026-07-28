package com.focusforge.core

import kotlin.math.sqrt

/**
 * Counts blinks and long eye closures from the eye-closure trace, with hysteresis.
 *
 * A closure is a blink when it lasts between [SignalConfig.blinkMinMs] and
 * [SignalConfig.blinkMaxMs]; anything longer is a *long closure*, which is the interesting
 * one for fatigue. Closures shorter than the minimum are treated as detector noise.
 */
class BlinkDetector(private val config: SignalConfig) {

    private var eyesClosed = false
    private var closedSinceMs = 0L
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
            eyesClosed = false
            longClosureCounted = false
            return
        }
        if (!eyesClosed) {
            if (closure >= config.eyeCloseLevel) {
                eyesClosed = true
                closedSinceMs = timestampMs
                longClosureCounted = false
            }
            return
        }
        val durationMs = timestampMs - closedSinceMs
        if (!longClosureCounted && durationMs > config.blinkMaxMs) {
            longClosureCount++
            longClosureCounted = true
        }
        if (closure <= config.eyeOpenLevel) {
            eyesClosed = false
            if (durationMs in config.blinkMinMs..config.blinkMaxMs) {
                blinkCount++
                lastBlinkDurationMs = durationMs
                blinkTimesMs.addLast(timestampMs)
            }
        }
    }

    /** True while a closure is in progress (used by the live debug view). */
    val eyesCurrentlyClosed: Boolean get() = eyesClosed

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
        eyesClosed = false
        closedSinceMs = 0L
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
    private var firstTimestampMs: Long? = null

    var calibrated = false
        private set
    var yawDeg = 0.0
        private set
    var pitchDeg = 0.0
        private set
    var irisRatio = 0.0
        private set

    fun update(timestampMs: Long, orientation: Orientation?, irisH: Double?) {
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

        val enoughTime = timestampMs - start >= config.baselineCalibrationMs
        val enoughSamples = yaws.size >= config.baselineMinSamples
        if (enoughTime && enoughSamples) calibrated = true
    }

    fun reset() {
        yaws.clear(); pitches.clear(); irisRatios.clear()
        firstTimestampMs = null
        calibrated = false
        yawDeg = 0.0; pitchDeg = 0.0; irisRatio = 0.0
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
