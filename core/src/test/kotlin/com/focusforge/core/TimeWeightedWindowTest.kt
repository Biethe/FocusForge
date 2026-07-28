package com.focusforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeWeightedWindowTest {

    @Test
    fun `always true is a fraction of one`() {
        val w = TimeWeightedWindow(windowMs = 10_000L)
        for (t in 0L..5_000L step 100L) w.add(t, true)
        assertEquals(1.0, w.fraction(), 1e-9)
    }

    @Test
    fun `half the time true is a fraction of one half`() {
        val w = TimeWeightedWindow(windowMs = 60_000L)
        for (t in 0L..10_000L step 100L) w.add(t, t >= 5_000L)
        assertEquals(0.5, w.fraction(), 0.02)
    }

    /**
     * The point of the whole class: Phase 6 will duty-cycle the camera between ~15 and
     * ~5 fps, and PERCLOS must not move just because the frame rate did.
     */
    @Test
    fun `the answer does not change with frame rate`() {
        fun fractionAt(periodMs: Long): Double {
            val w = TimeWeightedWindow(windowMs = 60_000L)
            for (t in 0L..30_000L step periodMs) w.add(t, (t / 1_000L) % 4L < 1L)
            return w.fraction()
        }
        val fast = fractionAt(33L)   // ~30 fps
        val slow = fractionAt(200L)  // 5 fps
        assertEquals(fast, slow, 0.02, "fast=$fast slow=$slow")
    }

    @Test
    fun `a stalled pipeline cannot charge one frame for ten seconds`() {
        val w = TimeWeightedWindow(windowMs = 60_000L, maxWeightMs = 500L)
        w.add(0L, true)
        w.add(100L, true)     // 100 ms of true
        w.add(10_100L, false) // a 10 s stall, capped to 500 ms of false
        assertEquals(100.0 / 600.0, w.fraction(), 1e-6)
    }

    @Test
    fun `unmeasurable time is excluded, not counted as false`() {
        val w = TimeWeightedWindow(windowMs = 60_000L)
        for (t in 0L..1_000L step 100L) w.add(t, true)
        // Face lost for a while: those samples must not dilute the fraction.
        for (t in 1_100L..5_000L step 100L) w.add(t, false, counted = false)
        assertEquals(1.0, w.fraction(), 1e-9)
        assertTrue(w.coverageMs() in 900L..1_100L, "coverage was ${w.coverageMs()}")
    }

    @Test
    fun `old samples leave the rolling window but stay in the cumulative total`() {
        val w = TimeWeightedWindow(windowMs = 10_000L)
        for (t in 0L..10_000L step 100L) w.add(t, true)
        for (t in 10_100L..25_000L step 100L) w.add(t, false)
        assertEquals(0.0, w.fraction(), 1e-9, "the true stretch has aged out")
        assertEquals(10_000.0 / 25_000.0, w.cumulativeFraction(), 0.01)
    }

    @Test
    fun `the very first sample spans no time`() {
        val w = TimeWeightedWindow(windowMs = 10_000L)
        w.add(0L, true)
        assertEquals(0L, w.coverageMs())
        assertEquals(0.0, w.fraction(), 1e-9)
    }
}
