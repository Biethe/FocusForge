package com.focusforge.core

/**
 * "What fraction of the last N seconds was this true?"
 *
 * The camera does not deliver frames at a fixed rate — it drifts with light and CPU load,
 * and Phase 6 will deliberately duty-cycle it down to ~5 fps. Counting frames would
 * therefore silently reweight the answer whenever the frame rate changed. Instead every
 * sample carries the time since the previous sample, capped at [maxWeightMs] so that one
 * stall cannot be charged entirely to whichever frame happened to be last.
 *
 * Samples can also be marked *not counted* (e.g. PERCLOS during a frame with no face):
 * that time is excluded from the denominator rather than counted as false.
 */
class TimeWeightedWindow(
    private val windowMs: Long,
    private val maxWeightMs: Long = SignalThresholds.MAX_FRAME_WEIGHT_MS,
) {
    private class Entry(val timestampMs: Long, val weightMs: Long, val value: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var lastTimestampMs: Long? = null
    private var weightSum = 0L
    private var trueSum = 0L

    /** Cumulative totals over the whole run, never evicted — used by the replay summaries. */
    var totalWeightMs = 0L
        private set
    var totalTrueWeightMs = 0L
        private set

    /**
     * @param counted false = this slice of time is unmeasurable and is excluded entirely.
     */
    fun add(timestampMs: Long, value: Boolean, counted: Boolean = true) {
        val previous = lastTimestampMs
        lastTimestampMs = timestampMs
        if (previous == null) return // the first sample defines the clock, it spans no time
        val weight = (timestampMs - previous).coerceIn(0L, maxWeightMs)
        if (weight == 0L || !counted) {
            evictOlderThan(timestampMs)
            return
        }
        entries.addLast(Entry(timestampMs, weight, value))
        weightSum += weight
        if (value) trueSum += weight
        totalWeightMs += weight
        if (value) totalTrueWeightMs += weight
        evictOlderThan(timestampMs)
    }

    private fun evictOlderThan(nowMs: Long) {
        while (entries.isNotEmpty() && nowMs - entries.first().timestampMs > windowMs) {
            val e = entries.removeFirst()
            weightSum -= e.weightMs
            if (e.value) trueSum -= e.weightMs
        }
    }

    /** Fraction 0..1 over the rolling window, or 0.0 when no measurable time is in it. */
    fun fraction(): Double = if (weightSum <= 0L) 0.0 else trueSum.toDouble() / weightSum

    /** Measurable milliseconds currently inside the rolling window. */
    fun coverageMs(): Long = weightSum

    /** Fraction 0..1 over the entire run so far, ignoring the rolling window. */
    fun cumulativeFraction(): Double =
        if (totalWeightMs <= 0L) 0.0 else totalTrueWeightMs.toDouble() / totalWeightMs

    fun reset() {
        entries.clear()
        lastTimestampMs = null
        weightSum = 0L
        trueSum = 0L
        totalWeightMs = 0L
        totalTrueWeightMs = 0L
    }
}
