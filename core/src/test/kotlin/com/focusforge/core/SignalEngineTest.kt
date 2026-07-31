package com.focusforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalEngineTest {

    private fun run(samples: List<FaceSample>): Pair<List<SignalSnapshot>, CumulativeSignals> {
        val engine = SignalEngine()
        val snapshots = samples.map { engine.update(it) }
        return snapshots to engine.cumulative()
    }

    // ------------------------------------------------------------------ PERCLOS

    @Test
    fun `wide-open eyes give a PERCLOS of zero`() {
        val (snapshots, total) = run(
            Synthetic.stream(90_000L) { t -> Synthetic.sample(t, closure = 0.02) },
        )
        assertEquals(0.0, total.perclos, 1e-9)
        assertEquals(0.0, snapshots.last().perclos, 1e-9)
    }

    @Test
    fun `eyes shut for a third of the run give a PERCLOS of about a third`() {
        val (_, total) = run(
            Synthetic.stream(90_000L) { t ->
                Synthetic.sample(t, closure = if (t in 30_000L until 60_000L) 1.0 else 0.02)
            },
        )
        assertEquals(1.0 / 3.0, total.perclos, 0.02, "got ${total.perclos}")
    }

    @Test
    fun `closing your eyes makes the live PERCLOS climb`() {
        // The operator's own verification step, as an automated test.
        val engine = SignalEngine()
        Synthetic.stream(30_000L) { t -> Synthetic.sample(t, closure = 0.02) }
            .forEach { engine.update(it) }
        val eyesOpen = engine.update(Synthetic.sample(30_100L, closure = 0.02)).perclos

        var t = 30_200L
        var last = 0.0
        while (t <= 60_000L) {
            last = engine.update(Synthetic.sample(t, closure = 1.0)).perclos
            t += 100L
        }
        assertTrue(last > eyesOpen + 0.4, "PERCLOS went $eyesOpen -> $last")
    }

    @Test
    fun `time with no face is excluded from PERCLOS rather than counted as eyes-open`() {
        val (_, total) = run(
            Synthetic.stream(60_000L) { t ->
                when {
                    t < 20_000L -> Synthetic.sample(t, closure = 1.0)   // eyes shut, visible
                    t < 40_000L -> Synthetic.sample(t, faceVisible = false) // walked away
                    else -> Synthetic.sample(t, closure = 1.0)
                }
            },
        )
        // 40 s measurable, all of it eyes-shut. The 20 s absence must not halve the answer.
        assertEquals(1.0, total.perclos, 0.02, "got ${total.perclos}")
        assertEquals(2.0 / 3.0, total.faceVisibleFraction, 0.02)
    }

    // ------------------------------------------------------------------ blinks

    @Test
    fun `counts blinks and reports a plausible rate`() {
        val (snapshots, total) = run(
            Synthetic.stream(120_000L, periodMs = 50L) { t ->
                // One 200 ms blink every 4 s = 15 per minute.
                Synthetic.sample(t, closure = if (t % 4_000L < 200L && t > 0L) 1.0 else 0.02)
            },
        )
        assertEquals(30, total.blinkCount)
        val rate = snapshots.last().blinkRatePerMin
        assertNotNull(rate)
        assertEquals(15.0, rate, 1.5, "got $rate blinks/min")
    }

    @Test
    fun `slow drowsy closures are long closures, not blinks`() {
        val (_, total) = run(
            Synthetic.stream(60_000L, periodMs = 50L) { t ->
                // One 1.5 s closure every 10 s starting at t=5 s — the "simulated
                // drowsiness" pattern. Six of them fit inside a minute.
                val closed = t >= 5_000L && (t - 5_000L) % 10_000L < 1_500L
                Synthetic.sample(t, closure = if (closed) 1.0 else 0.02)
            },
        )
        assertEquals(0, total.blinkCount, "1.5 s is far too long to be a blink")
        assertEquals(6, total.longClosureCount)
    }

    // ------------------------------------------------------------------ gaze

    @Test
    fun `facing the screen counts as gaze-on-screen`() {
        val (snapshots, total) = run(
            Synthetic.stream(60_000L) { t -> Synthetic.sample(t, closure = 0.02) },
        )
        assertTrue(snapshots.last().gazeOnScreen)
        assertEquals(1.0, total.gazeOnScreenFraction, 0.02)
    }

    @Test
    fun `turning your head away drops gaze-on-screen`() {
        // 10 s facing the screen (also the calibration window), then turned 45 deg away.
        val (snapshots, total) = run(
            Synthetic.stream(70_000L) { t ->
                Synthetic.sample(t, closure = 0.02, yawDeg = if (t < 10_000L) 0.0 else 45.0)
            },
        )
        assertFalse(snapshots.last().gazeOnScreen)
        assertTrue(total.gazeOnScreenFraction < 0.25, "got ${total.gazeOnScreenFraction}")
    }

    @Test
    fun `looking down at a phone in your lap drops gaze-on-screen`() {
        val (snapshots, _) = run(
            Synthetic.stream(70_000L) { t ->
                Synthetic.sample(t, closure = 0.02, pitchDeg = if (t < 10_000L) 0.0 else -35.0)
            },
        )
        assertFalse(snapshots.last().gazeOnScreen)
    }

    @Test
    fun `a side-glance with a still head drops gaze-on-screen`() {
        val (snapshots, _) = run(
            Synthetic.stream(70_000L) { t ->
                Synthetic.sample(t, closure = 0.02, irisRatio = if (t < 10_000L) 0.0 else 0.8)
            },
        )
        assertFalse(snapshots.last().gazeOnScreen, "eyes are off to the side")
    }

    @Test
    fun `no face at all counts as not looking at the screen`() {
        val (snapshots, total) = run(
            Synthetic.stream(70_000L) { t ->
                if (t < 10_000L) Synthetic.sample(t, closure = 0.02)
                else Synthetic.sample(t, faceVisible = false)
            },
        )
        assertFalse(snapshots.last().gazeOnScreen)
        assertTrue(total.gazeOnScreenFraction < 0.25, "got ${total.gazeOnScreenFraction}")
    }

    @Test
    fun `an ordinary blink does not punch a hole in the gaze trace`() {
        val (_, total) = run(
            Synthetic.stream(60_000L, periodMs = 50L) { t ->
                Synthetic.sample(t, closure = if (t % 4_000L < 200L && t > 0L) 1.0 else 0.02)
            },
        )
        assertTrue(total.gazeOnScreenFraction > 0.9, "got ${total.gazeOnScreenFraction}")
    }

    // ------------------------------------------------------------------ head pose

    @Test
    fun `a settled head reads as stable and a roaming head does not`() {
        val (settled, _) = run(
            Synthetic.stream(30_000L) { t -> Synthetic.sample(t, closure = 0.02, yawDeg = 3.0) },
        )
        assertTrue(settled.last().headStable)
        assertTrue(settled.last().headStabilityDeg < 1.0)

        var i = 0
        val (roaming, _) = run(
            Synthetic.stream(30_000L) { t ->
                Synthetic.sample(t, closure = 0.02, yawDeg = if (i++ % 2 == 0) -20.0 else 20.0)
            },
        )
        assertFalse(roaming.last().headStable)
    }

    @Test
    fun `the neutral pose is learned from the user, not assumed to be zero`() {
        // Phone low on a stand: the user sits with the head turned 15 deg and tilted down 12.
        val (snapshots, total) = run(
            Synthetic.stream(60_000L) { t ->
                Synthetic.sample(t, closure = 0.02, yawDeg = 15.0, pitchDeg = -12.0)
            },
        )
        val last = snapshots.last()
        assertTrue(last.calibrated)
        assertEquals(0.0, last.headYawDevDeg!!, 0.5, "neutral pose should read as zero deviation")
        assertEquals(0.0, last.headPitchDevDeg!!, 0.5)
        assertEquals(1.0, total.gazeOnScreenFraction, 0.02, "sitting normally is on-screen")
    }

    // ------------------------------------------------------------------ fallbacks

    @Test
    fun `eye closure is measured against this users own open eye`() {
        // Someone whose open eye is narrower than the 0.28 default. Once calibrated, their
        // wide-open eye must still read ~0 closed and their shut eye ~1 — the whole point
        // of a relative measure (docs/SIGNALS.md 3).
        val engine = SignalEngine()
        var open = 0.0
        for (t in 0L..6_000L step 100L) {
            open = engine.update(Synthetic.sample(t, ear = 0.20)).eyeClosure!!
        }
        val shut = engine.update(Synthetic.sample(6_100L, ear = 0.02)).eyeClosure!!
        assertTrue(open < 0.05, "this user's open eye should read as open, read $open")
        assertTrue(shut > 0.85, "this user's shut eye should read as shut, read $shut")
        assertTrue(shut >= SignalThresholds.PERCLOS_CLOSED_LEVEL,
            "a shut eye must be able to reach P80 — the bug that made PERCLOS read 0.000")
    }

    @Test
    fun `one opening frame cannot define the open eye`() {
        // A session that starts mid-blink must not calibrate "open" to a shut eye and then
        // report every closure as zero for the rest of the session.
        val shut = SignalEngine().update(Synthetic.sample(0L, ear = 0.03)).eyeClosure!!
        assertTrue(shut > 0.85, "a first frame with shut eyes read $shut")
    }

    @Test
    fun `eye closure falls back to the blendshape when the lid points are missing`() {
        val open = SignalEngine().update(
            Synthetic.sample(0L, closure = 0.05, withLandmarks = false),
        ).eyeClosure
        val shut = SignalEngine().update(
            Synthetic.sample(0L, closure = 0.95, withLandmarks = false),
        ).eyeClosure
        assertNotNull(open); assertNotNull(shut)
        assertEquals(0.05, open, 1e-6, "the blendshape average is used as-is")
        assertEquals(0.95, shut, 1e-6)
    }

    @Test
    fun `eye closure is null rather than zero when the eyes cannot be seen`() {
        val snapshot = SignalEngine().update(Synthetic.sample(0L, faceVisible = false))
        assertEquals(null, snapshot.eyeClosure)
        assertFalse(snapshot.faceVisible)
    }

    @Test
    fun `yawns reach the snapshot`() {
        val (snapshots, total) = run(
            Synthetic.stream(30_000L) { t ->
                Synthetic.sample(t, closure = 0.02, jawOpen = if (t in 5_000L until 8_000L) 0.9 else 0.05)
            },
        )
        assertEquals(1, total.yawnCount)
        assertEquals(1, snapshots.last().yawnCount)
    }

    @Test
    fun `reset clears everything`() {
        val engine = SignalEngine()
        Synthetic.stream(30_000L) { t -> Synthetic.sample(t, closure = 1.0) }
            .forEach { engine.update(it) }
        assertTrue(engine.cumulative().perclos > 0.9)
        engine.reset()
        val fresh = engine.cumulative()
        assertEquals(0, fresh.samples)
        assertEquals(0.0, fresh.perclos, 1e-9)
        assertEquals(0, fresh.blinkCount)
    }
}
