package com.focusforge.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fusion, against inputs whose answer we know exactly.
 *
 * These build [SignalSnapshot]s directly rather than going through [SignalEngine], because
 * the thing under test is the *mixing rule* — feeding it real face geometry would test the
 * signal pipeline all over again and make it impossible to say which layer was wrong.
 */
class FocusScorerTest {

    private fun snapshot(
        timestampMs: Long,
        gaze: Double = 1.0,
        perclos: Double = 0.0,
        headSpread: Double = 0.0,
        longClosures: Int = 0,
        calibrated: Boolean = true,
        faceVisible: Boolean = true,
    ) = SignalSnapshot(
        timestampMs = timestampMs,
        elapsedMs = timestampMs,
        calibrated = calibrated,
        faceVisible = faceVisible,
        eyeClosure = 0.0,
        eyesClosedNow = false,
        blinkCount = 0,
        blinkRatePerMin = null,
        lastBlinkDurationMs = null,
        longClosureCount = longClosures,
        perclos = perclos,
        perclosCoverageMs = 60_000L,
        gazeOnScreen = gaze > 0.5,
        gazeOnScreenFraction = gaze,
        headYawDevDeg = 0.0,
        headPitchDevDeg = 0.0,
        headRollDeg = 0.0,
        irisHorizontalDev = 0.0,
        headStabilityDeg = headSpread,
        headStable = headSpread <= 6.0,
        yawnCount = 0,
    )

    /** Runs a constant condition for [durationMs] and returns the final state. */
    private fun settle(
        durationMs: Long = 120_000L,
        periodMs: Long = 100L,
        scorer: FocusScorer = FocusScorer(),
        at: (Long) -> SignalSnapshot,
    ): FocusState {
        var last: FocusState? = null
        for (t in 0L..durationMs step periodMs) last = scorer.update(at(t))
        return last!!
    }

    // ------------------------------------------------------------------ the score

    @Test
    fun `a perfect session scores 100 and a hopeless one scores 0`() {
        val best = settle { t -> snapshot(t, gaze = 1.0, perclos = 0.0, headSpread = 0.0) }
        assertEquals(100, best.score)

        val worst = settle { t -> snapshot(t, gaze = 0.0, perclos = 0.5, headSpread = 40.0) }
        assertEquals(0, worst.score)
    }

    @Test
    fun `each term contributes exactly its documented weight`() {
        // Attention alone at full, everything else at zero: 50 points.
        val attentionOnly = settle { t -> snapshot(t, gaze = 1.0, perclos = 0.5, headSpread = 40.0) }
        assertEquals(50, attentionOnly.score, "attention is weighted 0.50")

        val alertnessOnly = settle { t -> snapshot(t, gaze = 0.0, perclos = 0.0, headSpread = 40.0) }
        assertEquals(30, alertnessOnly.score, "alertness is weighted 0.30")

        val steadinessOnly = settle { t -> snapshot(t, gaze = 0.0, perclos = 0.5, headSpread = 0.0) }
        assertEquals(20, steadinessOnly.score, "steadiness is weighted 0.20")
    }

    @Test
    fun `the terms are reported alongside the score so the coach can explain it`() {
        val state = settle { t -> snapshot(t, gaze = 0.65, perclos = 0.055, headSpread = 8.5) }
        // Each is the midpoint of its documented ramp.
        assertEquals(0.5, state.attention, 0.01)
        assertEquals(0.5, state.alertness, 0.01)
        assertEquals(0.5, state.steadiness, 0.01)
        assertEquals(50, state.score)
    }

    @Test
    fun `the score is not ready until the neutral pose is calibrated`() {
        val warming = FocusScorer().update(snapshot(0L, calibrated = false))
        assertFalse(warming.ready)
        val ready = FocusScorer().update(snapshot(0L, calibrated = true))
        assertTrue(ready.ready)
    }

    // ------------------------------------------------------------------ smoothing

    @Test
    fun `the score does not jump when the signals do`() {
        val scorer = FocusScorer()
        for (t in 0L..60_000L step 100L) scorer.update(snapshot(t, gaze = 1.0))
        val before = scorer.update(snapshot(60_100L, gaze = 1.0)).score

        // A step change to the worst possible input, applied instantly.
        val oneFrameLater = scorer.update(snapshot(60_200L, gaze = 0.0, perclos = 0.5, headSpread = 40.0))
        assertTrue(
            before - oneFrameLater.score < 5,
            "one frame moved the score $before -> ${oneFrameLater.score}",
        )
        assertEquals(0.0, oneFrameLater.rawScore, 0.01, "the raw fusion did move immediately")
    }

    @Test
    fun `a thirty second distraction visibly drops the score, and it recovers`() {
        // The operator's own acceptance test from docs/PROMPTS.md, as an automated one.
        val scorer = FocusScorer()
        var t = 0L
        while (t < 120_000L) { scorer.update(snapshot(t, gaze = 1.0)); t += 100L }
        val focused = scorer.update(snapshot(t, gaze = 1.0)).score

        val awayUntil = t + 30_000L
        var away = focused
        while (t < awayUntil) { away = scorer.update(snapshot(t, gaze = 0.2, headSpread = 12.0)).score; t += 100L }
        assertTrue(focused - away >= 20, "30 s away only moved $focused -> $away")

        val backUntil = t + 60_000L
        var back = away
        while (t < backUntil) { back = scorer.update(snapshot(t, gaze = 1.0)).score; t += 100L }
        assertTrue(back >= focused - 2, "the score did not recover: $back vs $focused")
    }

    @Test
    fun `smoothing settles at the same rate whatever the frame rate`() {
        // Phase 6 will duty-cycle the camera; the score must not feel different when it does.
        fun scoreAfterStep(periodMs: Long): Int {
            val scorer = FocusScorer()
            var t = 0L
            while (t < 60_000L) { scorer.update(snapshot(t, gaze = 1.0)); t += periodMs }
            var last = 0
            val until = t + 8_000L // exactly one time constant of the worst-case input
            while (t < until) { last = scorer.update(snapshot(t, gaze = 0.0, perclos = 0.5, headSpread = 40.0)).score; t += periodMs }
            return last
        }
        val fast = scoreAfterStep(33L)   // ~30 fps
        val slow = scoreAfterStep(200L)  // 5 fps
        assertTrue(abs(fast - slow) <= 2, "30 fps settled to $fast, 5 fps to $slow")
    }

    // ------------------------------------------------------------------ fatigue flag

    @Test
    fun `sustained eye closure raises the fatigue flag`() {
        val state = settle { t -> snapshot(t, perclos = 0.12, longClosures = (t / 10_000L).toInt()) }
        assertTrue(state.fatigue)
        assertTrue(state.fatigueEvidence >= FocusThresholds.FATIGUE_ON_LEVEL)
    }

    @Test
    fun `a focused session never raises it`() {
        val state = settle { t -> snapshot(t, gaze = 1.0, perclos = 0.0, longClosures = 0) }
        assertFalse(state.fatigue)
    }

    @Test
    fun `one deep blink does not raise it`() {
        // Evidence goes hard over the line, but only for 2 s of a 60 s session.
        val scorer = FocusScorer()
        var raised = false
        for (t in 0L..60_000L step 100L) {
            val drowsyNow = t in 30_000L..32_000L
            val s = snapshot(t, perclos = if (drowsyNow) 0.5 else 0.0, longClosures = if (t >= 30_000L) 1 else 0)
            if (scorer.update(s).fatigue) raised = true
        }
        assertFalse(raised, "a 2 s excursion must not trip a 15 s dwell")
    }

    @Test
    fun `the flag needs the full dwell time before it moves`() {
        val scorer = FocusScorer()
        var t = 0L
        // Evidence over the ON level from the first frame.
        while (t < FocusThresholds.FATIGUE_ON_DWELL_MS - 1_000L) {
            assertFalse(scorer.update(snapshot(t, perclos = 0.2)).fatigue, "raised early at $t ms")
            t += 100L
        }
        while (t < FocusThresholds.FATIGUE_ON_DWELL_MS + 1_000L) {
            scorer.update(snapshot(t, perclos = 0.2)); t += 100L
        }
        assertTrue(scorer.update(snapshot(t, perclos = 0.2)).fatigue, "never raised")
    }

    @Test
    fun `evidence hovering on the trigger does not flicker the flag`() {
        // The whole reason for hysteresis: an input sitting exactly on the ON level, with
        // noise either side of it, must not produce a blinking warning.
        val scorer = FocusScorer()
        var flips = 0
        var previous = false
        var t = 0L
        var i = 0
        while (t < 300_000L) {
            // perclos alternating around the value that puts evidence exactly on 0.60.
            val onTheLine = if (i++ % 2 == 0) 0.079 else 0.081
            val now = scorer.update(snapshot(t, perclos = onTheLine)).fatigue
            if (now != previous) flips++
            previous = now
            t += 100L
        }
        assertTrue(flips <= 1, "the flag changed $flips times while the input hovered")
    }

    @Test
    fun `clearing the flag is slower than raising it`() {
        val scorer = FocusScorer()
        var t = 0L
        while (t < 60_000L) { scorer.update(snapshot(t, perclos = 0.2)); t += 100L }
        assertTrue(scorer.update(snapshot(t, perclos = 0.2)).fatigue, "should be raised by now")

        // Evidence drops to zero. It must stay raised for the whole OFF dwell.
        val evidenceGoneAt = t
        var firstClearedAt: Long? = null
        while (t < evidenceGoneAt + 120_000L) {
            val state = scorer.update(snapshot(t, perclos = 0.0))
            if (!state.fatigue && firstClearedAt == null) firstClearedAt = t
            t += 100L
        }
        assertNotNull(firstClearedAt, "the flag never cleared")
        val heldMs = firstClearedAt - evidenceGoneAt
        assertTrue(
            heldMs >= FocusThresholds.FATIGUE_OFF_DWELL_MS,
            "cleared after only $heldMs ms, dwell is ${FocusThresholds.FATIGUE_OFF_DWELL_MS}",
        )
        assertTrue(
            FocusThresholds.FATIGUE_OFF_DWELL_MS > FocusThresholds.FATIGUE_ON_DWELL_MS,
            "clearing must be the slower edge",
        )
    }

    // ------------------------------------------------------------------ summary

    @Test
    fun `the session summary reports what happened`() {
        val engine = SignalEngine()
        val scorer = FocusScorer()
        var t = 0L
        while (t <= 120_000L) {
            // Half focused, half looking away.
            val s = snapshot(t, gaze = if (t < 60_000L) 1.0 else 0.0, headSpread = if (t < 60_000L) 0.0 else 40.0)
            scorer.update(s)
            t += 100L
        }
        val summary = scorer.summary(engine.cumulative())
        assertEquals(120_000L, summary.durationMs)
        assertTrue(summary.maxScore >= 99, "got ${summary.maxScore}")
        assertTrue(summary.minScore <= 31, "got ${summary.minScore}")
        assertTrue(
            summary.meanScore in 50.0..80.0,
            "half a good session should sit in the middle, got ${summary.meanScore}",
        )
        assertEquals(0.0, summary.fatigueFraction, 1e-9)
    }
}

/** The session export format must survive a trip through disk without changing a number. */
class SessionJsonTest {

    @Test
    fun `a session round-trips through JSON`() {
        val engine = SignalEngine()
        val scorer = FocusScorer()
        val builder = SessionBuilder(
            appVersion = "test",
            startedAtEpochMs = 1_700_000_000_000L,
            device = mapOf("model" to "synthetic", "abi" to "arm64-v8a"),
        )
        Synthetic.stream(60_000L) { t -> Synthetic.sample(t, closure = 0.02) }.forEach { sample ->
            val snapshot = engine.update(sample)
            builder.add(snapshot, scorer.update(snapshot))
        }
        val original = builder.build(scorer.summary(engine.cumulative()))
        val restored = SessionJson.decode(SessionJson.encode(original))

        assertEquals(original.samples.size, restored.samples.size)
        assertEquals(original.samples, restored.samples)
        assertEquals(original.summary, restored.summary)
        assertEquals(original.device, restored.device)
    }

    @Test
    fun `the timeline is thinned to one row per second`() {
        val engine = SignalEngine()
        val scorer = FocusScorer()
        val builder = SessionBuilder("test", 0L)
        // 60 s at 10 fps = 601 frames in, ~61 rows out.
        Synthetic.stream(60_000L, periodMs = 100L) { t -> Synthetic.sample(t, closure = 0.02) }
            .forEach { sample ->
                val snapshot = engine.update(sample)
                builder.add(snapshot, scorer.update(snapshot))
            }
        assertTrue(builder.sampleCount in 55..65, "got ${builder.sampleCount} rows")
    }

    @Test
    fun `a session export carries no landmarks and no blendshapes`() {
        // The privacy claim, as a test: SessionSample has no field that could hold face
        // geometry, so the encoded text cannot contain one.
        val engine = SignalEngine()
        val scorer = FocusScorer()
        val builder = SessionBuilder("test", 0L)
        Synthetic.stream(3_000L) { t -> Synthetic.sample(t, closure = 0.02) }.forEach { sample ->
            val snapshot = engine.update(sample)
            builder.add(snapshot, scorer.update(snapshot))
        }
        val text = SessionJson.encode(builder.build(scorer.summary(engine.cumulative())))
        for (forbidden in listOf("landmark", "blendshape", "matrix", "eyeBlink", "jawOpen")) {
            assertFalse(text.contains(forbidden, ignoreCase = true), "export mentions $forbidden")
        }
    }
}

/**
 * The architect's ruling of 2026-08-02, as tests: blink rate is display and telemetry only,
 * and an undersampled rate is never presented as a measurement.
 */
class BlinkRateDemotionTest {

    private fun sample(t: Long, closure: Double = 0.02) =
        Synthetic.sample(t, closure = closure)

    /** A snapshot with everything the fusion reads held fixed, and the blink figures free. */
    private fun snapshot(t: Long, blinkRate: Double, blinks: Int) = SignalSnapshot(
        timestampMs = t, elapsedMs = t, calibrated = true, faceVisible = true,
        eyeClosure = 0.0, eyesClosedNow = false,
        blinkCount = blinks, blinkRatePerMin = blinkRate, lastBlinkDurationMs = 150L,
        longClosureCount = 0,
        perclos = 0.0, perclosCoverageMs = 60_000L,
        gazeOnScreen = true, gazeOnScreenFraction = 1.0,
        headYawDevDeg = 0.0, headPitchDevDeg = 0.0, headRollDeg = 0.0, irisHorizontalDev = 0.0,
        headStabilityDeg = 0.0, headStable = true, yawnCount = 0,
    )

    @Test
    fun `blink rate has no influence whatsoever on the focus score`() {
        // Everything the fusion is allowed to read is held identical; only the blink figures
        // differ, by a factor of ten. If blink rate carried any weight at all, the two
        // would diverge. (This deliberately does NOT run two synthetic faces through the
        // whole pipeline: blinking legitimately moves PERCLOS and gaze, and the ruling is
        // about the *fusion not reading blink rate*, not about blinking being invisible.)
        fun run(blinkRate: Double): SessionSummary {
            val scorer = FocusScorer()
            var t = 0L
            var blinks = 0
            while (t <= 120_000L) {
                blinks = (blinkRate * t / 60_000.0).toInt()
                scorer.update(snapshot(t, blinkRate, blinks))
                t += 100L
            }
            return scorer.summary(
                CumulativeSignals(
                    durationMs = t, samples = 1, faceVisibleFraction = 1.0, perclos = 0.0,
                    gazeOnScreenFraction = 1.0, blinkCount = blinks, blinkRatePerMin = blinkRate,
                    longClosureCount = 0, yawnCount = 0, meanHeadStabilityDeg = 0.0,
                ),
            )
        }
        val rare = run(3.0)
        val frequent = run(30.0)
        assertEquals(
            rare.meanScore, frequent.meanScore, 1e-9,
            "blink rate is weighted ${FocusThresholds.WEIGHT_BLINK_RATE} and must not move the score",
        )
        assertEquals(rare.minScore, frequent.minScore)
        assertEquals(rare.maxScore, frequent.maxScore)
    }

    @Test
    fun `a normal blink rate does not by itself depress the score`() {
        // The indirect route the ruling leaves open: blinks are brief eye closures, so they
        // touch PERCLOS and gaze. Through the real pipeline at a human 15 blinks/min that
        // must not cost much, or "blinks a lot" would quietly read as "drowsy".
        //
        // The blink is shaped as a triangular dip rather than a square pulse, because that
        // is what the lid actually does: a 200 ms blink spends only its middle ~40 ms past
        // the 80% mark. Squaring it off puts five times as much time over the PERCLOS line
        // as a real blink does, and the operator's focused recording — 33 real blinks —
        // measured PERCLOS 0.000.
        val engine = SignalEngine()
        val scorer = FocusScorer()
        var t = 0L
        var last = 0
        while (t <= 120_000L) {
            val phase = t % 4_000L
            val closure = if (phase < 200L && t > 0L) {
                val p = phase / 200.0            // 0..1 across the blink
                (1.0 - abs(2.0 * p - 1.0)).coerceIn(0.02, 1.0)
            } else 0.02
            last = scorer.update(engine.update(sample(t, closure))).score
            t += 20L
        }
        assertTrue(last >= 95, "a normally blinking, focused session scored $last")
        assertTrue(
            engine.cumulative().perclos < 0.03,
            "ordinary blinking should barely register in PERCLOS, got ${engine.cumulative().perclos}",
        )
    }

    @Test
    fun `the fusion weights still sum to one without a blink term`() {
        assertEquals(
            1.0,
            FocusThresholds.WEIGHT_ATTENTION + FocusThresholds.WEIGHT_ALERTNESS +
                FocusThresholds.WEIGHT_STEADINESS + FocusThresholds.WEIGHT_BLINK_RATE,
            1e-9,
        )
        assertEquals(0.0, FocusThresholds.WEIGHT_BLINK_RATE, 1e-12)
    }

    @Test
    fun `a slow vision loop marks the blink rate undersampled`() {
        val engine = SignalEngine()
        var last: SignalSnapshot? = null
        var t = 0L
        while (t <= 30_000L) { last = engine.update(sample(t)); t += 120L } // ~8.3 fps
        assertEquals(BlinkRateValidity.UNDERSAMPLED, last!!.blinkRateValidity)
        assertTrue(last.visionFps in 7.0..10.0, "got ${last.visionFps} fps")
        assertEquals(BlinkRateValidity.UNDERSAMPLED, engine.cumulative().blinkRateValidity)
    }

    @Test
    fun `a fast vision loop marks it full-rate`() {
        val engine = SignalEngine()
        var last: SignalSnapshot? = null
        var t = 0L
        while (t <= 30_000L) { last = engine.update(sample(t)); t += 33L } // ~30 fps
        assertEquals(BlinkRateValidity.FULL_RATE, last!!.blinkRateValidity)
        assertTrue(last.visionFps in 25.0..35.0, "got ${last.visionFps} fps")
        assertEquals(BlinkRateValidity.FULL_RATE, engine.cumulative().blinkRateValidity)
    }

    @Test
    fun `one slow stretch makes the whole run undersampled`() {
        // Phase 6 duty-cycles the camera down and back up. A run that spent any time below
        // the line has an incomplete blink count for the whole run, and says so.
        val engine = SignalEngine()
        var t = 0L
        while (t <= 20_000L) { engine.update(sample(t)); t += 33L }   // fast
        while (t <= 40_000L) { engine.update(sample(t)); t += 200L }  // duty-cycled to 5 fps
        while (t <= 60_000L) { engine.update(sample(t)); t += 33L }   // fast again
        assertEquals(BlinkRateValidity.UNDERSAMPLED, engine.cumulative().blinkRateValidity)
    }

    @Test
    fun `the export carries the frame rate and the validity flag on every row`() {
        val engine = SignalEngine()
        val scorer = FocusScorer()
        val builder = SessionBuilder("test", 0L)
        var t = 0L
        while (t <= 30_000L) {
            val snapshot = engine.update(sample(t))
            builder.add(snapshot, scorer.update(snapshot))
            t += 120L // ~8.3 fps
        }
        val recording = builder.build(scorer.summary(engine.cumulative()))
        val rows = recording.samples.drop(1) // the first row spans no window
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            assertTrue(row.visionFps in 6.0..11.0, "row at ${row.t} ms reports ${row.visionFps} fps")
            assertEquals("undersampled", row.blinkRateValidity)
        }
        assertEquals("undersampled", recording.summary.blinkRateValidity)
        assertTrue(recording.summary.meanVisionFps in 7.0..10.0)

        // ...and it survives the round trip, because the architect reads the file.
        val restored = SessionJson.decode(SessionJson.encode(recording))
        assertEquals(recording.samples, restored.samples)
        assertEquals(recording.summary, restored.summary)
    }
}
