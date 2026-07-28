package com.focusforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlinkDetectorTest {

    private val config = SignalConfig()

    /** Drives the detector at 10 ms resolution; [closureAt] gives the eye closure at each ms. */
    private fun run(durationMs: Long, closureAt: (Long) -> Double): BlinkDetector {
        val d = BlinkDetector(config)
        var t = 0L
        while (t <= durationMs) {
            d.update(t, closureAt(t), measurable = true)
            t += 10L
        }
        return d
    }

    private fun closedBetween(from: Long, to: Long): (Long) -> Double =
        { t -> if (t in from until to) 1.0 else 0.0 }

    @Test
    fun `a 200 ms closure is one blink`() {
        val d = run(3_000L, closedBetween(1_000L, 1_200L))
        assertEquals(1, d.blinkCount)
        assertEquals(0, d.longClosureCount)
        assertTrue((d.lastBlinkDurationMs ?: 0L) in 190L..220L, "got ${d.lastBlinkDurationMs}")
    }

    @Test
    fun `a 30 ms flicker is noise, not a blink`() {
        val d = run(3_000L, closedBetween(1_000L, 1_030L))
        assertEquals(0, d.blinkCount)
        assertEquals(0, d.longClosureCount)
    }

    @Test
    fun `an 800 ms closure is a long closure, not a blink`() {
        val d = run(3_000L, closedBetween(1_000L, 1_800L))
        assertEquals(0, d.blinkCount, "too long to be a blink")
        assertEquals(1, d.longClosureCount)
    }

    @Test
    fun `a score hovering on the threshold does not emit fake blinks`() {
        // Without hysteresis this alternation would count a blink every other sample.
        var i = 0
        val d = run(3_000L) { if (i++ % 2 == 0) 0.49 else 0.51 }
        assertEquals(0, d.blinkCount)
    }

    @Test
    fun `counts every blink in a train`() {
        val d = run(20_000L) { t -> if (t % 2_000L < 200L && t > 0) 1.0 else 0.0 }
        assertEquals(10, d.blinkCount)
    }

    @Test
    fun `losing the face abandons the closure instead of guessing its length`() {
        val d = BlinkDetector(config)
        d.update(0L, 0.0, measurable = true)
        d.update(100L, 1.0, measurable = true)   // eyes close
        d.update(200L, 0.0, measurable = false)  // face lost mid-closure
        d.update(300L, 0.0, measurable = true)   // back, eyes open
        assertEquals(0, d.blinkCount)
        assertEquals(0, d.longClosureCount)
    }

    @Test
    fun `blink rate is null until there is enough data, then correct`() {
        val d = BlinkDetector(config)
        var t = 0L
        while (t <= 60_000L) {
            // One 200 ms blink every 5 s = 12 blinks per minute.
            d.update(t, if (t % 5_000L in 100L until 300L) 1.0 else 0.0, measurable = true)
            if (t == 10_000L) assertNull(d.ratePerMinute(t, t), "10 s is too little to state a rate")
            t += 10L
        }
        val rate = d.ratePerMinute(60_000L, 60_000L)!!
        assertEquals(12.0, rate, 1.0, "expected ~12 blinks/min, got $rate")
    }
}

class YawnDetectorTest {

    private fun run(durationMs: Long, jawAt: (Long) -> Double): YawnDetector {
        val d = YawnDetector(SignalConfig())
        var t = 0L
        while (t <= durationMs) {
            d.update(t, jawAt(t), measurable = true)
            t += 50L
        }
        return d
    }

    @Test
    fun `a two second wide-open jaw is one yawn`() {
        val d = run(10_000L) { t -> if (t in 2_000L until 4_000L) 0.9 else 0.0 }
        assertEquals(1, d.yawnCount)
    }

    @Test
    fun `a short mouth opening while talking is not a yawn`() {
        val d = run(10_000L) { t -> if (t in 2_000L until 2_600L) 0.9 else 0.0 }
        assertEquals(0, d.yawnCount)
    }

    @Test
    fun `one long yawn is counted once, not repeatedly`() {
        val d = run(20_000L) { t -> if (t in 2_000L until 12_000L) 0.9 else 0.0 }
        assertEquals(1, d.yawnCount)
    }

    @Test
    fun `two separate yawns are counted twice`() {
        val d = run(30_000L) { t ->
            if (t in 2_000L until 4_000L || t in 20_000L until 22_000L) 0.9 else 0.0
        }
        assertEquals(2, d.yawnCount)
    }
}

class HeadStabilityWindowTest {

    @Test
    fun `a motionless head has zero spread`() {
        val w = HeadStabilityWindow(SignalConfig())
        for (t in 0L..5_000L step 100L) w.add(t, 3.0, -2.0)
        assertEquals(0.0, w.spreadDeg(), 1e-9)
    }

    @Test
    fun `looking around produces a spread above the stable threshold`() {
        val w = HeadStabilityWindow(SignalConfig())
        var i = 0
        for (t in 0L..5_000L step 100L) w.add(t, if (i++ % 2 == 0) -15.0 else 15.0, 0.0)
        assertTrue(
            w.spreadDeg() > SignalThresholds.HEAD_STABLE_MAX_DEG,
            "spread ${w.spreadDeg()} should exceed ${SignalThresholds.HEAD_STABLE_MAX_DEG}",
        )
    }

    @Test
    fun `old movement leaves the window`() {
        val w = HeadStabilityWindow(SignalConfig())
        var i = 0
        for (t in 0L..5_000L step 100L) w.add(t, if (i++ % 2 == 0) -20.0 else 20.0, 0.0)
        assertTrue(w.spreadDeg() > 10.0)
        // Now hold still for longer than the 10 s window.
        for (t in 5_100L..16_000L step 100L) w.add(t, 0.0, 0.0)
        assertEquals(0.0, w.spreadDeg(), 1e-9)
    }
}

class BaselineCalibratorTest {

    @Test
    fun `learns the users neutral pose, ignoring a brief outlier`() {
        val c = BaselineCalibrator(SignalConfig())
        for (t in 0L..6_000L step 100L) {
            // Sitting with the head turned 12 deg and tilted down 8 deg, plus one glance away.
            val yaw = if (t in 2_000L..2_300L) 60.0 else 12.0
            c.update(t, Orientation(yaw, -8.0, 0.0), irisH = 0.05)
        }
        assertTrue(c.calibrated)
        assertEquals(12.0, c.yawDeg, 0.01, "median ignores the glance")
        assertEquals(-8.0, c.pitchDeg, 0.01)
        assertEquals(0.05, c.irisRatio, 0.01)
    }

    @Test
    fun `is not calibrated on too few samples`() {
        val c = BaselineCalibrator(SignalConfig())
        c.update(0L, Orientation(0.0, 0.0, 0.0), null)
        c.update(9_000L, Orientation(0.0, 0.0, 0.0), null)
        assertTrue(!c.calibrated, "2 samples is not a baseline even after 9 s")
    }

    @Test
    fun `stops moving once calibrated`() {
        val c = BaselineCalibrator(SignalConfig())
        for (t in 0L..6_000L step 100L) c.update(t, Orientation(0.0, 0.0, 0.0), 0.0)
        val frozen = c.yawDeg
        for (t in 6_100L..20_000L step 100L) c.update(t, Orientation(45.0, 0.0, 0.0), 0.0)
        assertEquals(frozen, c.yawDeg, 1e-9)
    }
}
