package com.focusforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coach's trigger rules.
 *
 * These matter more than they look. A coach that speaks too often is not a mildly worse
 * coach — it is an app the user turns off, which makes every other number in this project
 * moot. So the tests are mostly about **silence**: the conditions under which it must not
 * speak, and the guarantee that two rules firing at once still produce one message.
 */
class CoachPolicyTest {

    private fun state(
        elapsedMs: Long,
        score: Int = 90,
        fatigue: Boolean = false,
        ready: Boolean = true,
    ) = FocusState(
        timestampMs = elapsedMs,
        elapsedMs = elapsedMs,
        score = score,
        rawScore = score.toDouble(),
        attention = 1.0,
        alertness = 1.0,
        steadiness = 1.0,
        fatigue = fatigue,
        fatigueEvidence = if (fatigue) 0.8 else 0.0,
        ready = ready,
    )

    private fun snapshot(elapsedMs: Long) = SignalSnapshot(
        timestampMs = elapsedMs,
        elapsedMs = elapsedMs,
        calibrated = true,
        faceVisible = true,
        eyeClosure = 0.1,
        eyesClosedNow = false,
        blinkCount = 10,
        blinkRatePerMin = 12.0,
        lastBlinkDurationMs = 150L,
        longClosureCount = 2,
        perclos = 0.02,
        perclosCoverageMs = 60_000L,
        gazeOnScreen = true,
        gazeOnScreenFraction = 0.8,
        headYawDevDeg = 1.0,
        headPitchDevDeg = 1.0,
        headRollDeg = 0.0,
        irisHorizontalDev = 0.0,
        headStabilityDeg = 3.0,
        headStable = true,
        yawnCount = 0,
        visionFps = 9.0,
        blinkRateValidity = BlinkRateValidity.UNDERSAMPLED,
    )

    /** Runs a session, returning every message the coach decided to send. */
    private fun run(
        durationMs: Long,
        stepMs: Long = 1_000L,
        policy: CoachPolicy = CoachPolicy(),
        at: (Long) -> FocusState,
    ): List<CoachContext> {
        val out = ArrayList<CoachContext>()
        var t = 0L
        while (t <= durationMs) {
            policy.update(at(t), snapshot(t))?.let { out += it }
            t += stepMs
        }
        return out
    }

    // ------------------------------------------------------------------ silence

    @Test
    fun `says nothing during the warm-up, however bad the numbers look`() {
        val messages = run(CoachThresholds.MIN_SESSION_MS - 1_000L) { t ->
            state(t, score = 5, fatigue = true, ready = false)
        }
        assertTrue(messages.isEmpty(), "spoke ${messages.size} times during warm-up")
    }

    @Test
    fun `says nothing at all during a good session before the first milestone`() {
        val messages = run(9 * 60_000L) { t -> state(t, score = 95) }
        assertTrue(messages.isEmpty(), "a focused session should be left alone, got $messages")
    }

    @Test
    fun `a brief dip below the low-focus line is not worth a message`() {
        // Down for 60 s, back up. The dwell is 2 minutes precisely so this is ignored.
        val messages = run(9 * 60_000L) { t ->
            state(t, score = if (t in 120_000L..180_000L) 30 else 90)
        }
        assertTrue(messages.isEmpty(), "reacted to a one-minute dip: $messages")
    }

    @Test
    fun `never speaks twice inside the minimum gap`() {
        // Everything wrong at once, for half an hour.
        val messages = run(30 * 60_000L) { t -> state(t, score = 10, fatigue = t > 90_000L) }
        assertTrue(messages.isNotEmpty(), "should have spoken at least once")
        val gaps = messages.zipWithNext { a, b -> b.elapsedMs - a.elapsedMs }
        for (gap in gaps) {
            assertTrue(
                gap >= CoachThresholds.MIN_GAP_MS,
                "spoke again after only ${gap / 1000}s; minimum is ${CoachThresholds.MIN_GAP_MS / 1000}s",
            )
        }
    }

    @Test
    fun `two rules firing together still produce one message`() {
        // A tired user also scores badly, so FATIGUE and LOW_FOCUS are true simultaneously.
        val policy = CoachPolicy()
        val messages = run(6 * 60_000L, policy = policy) { t ->
            state(t, score = 20, fatigue = t >= 180_000L)
        }
        val within = messages.filter { it.elapsedMs in 180_000L..185_000L }
        assertTrue(within.size <= 1, "sent ${within.size} messages for one event")
    }

    // ------------------------------------------------------------------ speaking

    @Test
    fun `speaks when the fatigue flag rises`() {
        val messages = run(8 * 60_000L) { t -> state(t, score = 80, fatigue = t >= 120_000L) }
        assertTrue(messages.any { it.trigger == CoachTrigger.FATIGUE }, "got $messages")
        val first = messages.first { it.trigger == CoachTrigger.FATIGUE }
        assertTrue(first.elapsedMs in 120_000L..121_000L, "spoke at ${first.elapsedMs} ms")
    }

    @Test
    fun `speaks once attention has been drifting for the full dwell`() {
        val messages = run(8 * 60_000L) { t -> state(t, score = 30) }
        val low = messages.first { it.trigger == CoachTrigger.LOW_FOCUS }
        // The clock starts at the end of the warm-up, not at t=0.
        assertTrue(
            low.elapsedMs >= CoachThresholds.LOW_FOCUS_DWELL_MS,
            "spoke after only ${low.elapsedMs} ms of drifting",
        )
    }

    @Test
    fun `checks in on the ten-minute milestones of a good session`() {
        val messages = run(25 * 60_000L) { t -> state(t, score = 95) }
        assertTrue(messages.all { it.trigger == CoachTrigger.MILESTONE }, "got $messages")
        assertEquals(2, messages.size, "expected the 10 and 20 minute check-ins, got $messages")
    }

    @Test
    fun `a milestone missed inside the quiet window is dropped, not delivered late`() {
        // Fatigue at 9:30 speaks; the 10:00 milestone lands inside the 5-minute quiet gap.
        // The user should not be told about the ten-minute mark at minute fifteen.
        val messages = run(20 * 60_000L) { t ->
            state(t, score = 85, fatigue = t in 570_000L..600_000L)
        }
        val milestones = messages.filter { it.trigger == CoachTrigger.MILESTONE }
        assertTrue(
            milestones.none { it.elapsedMs in 600_000L..900_000L },
            "delivered a stale 10-minute milestone at ${milestones.map { it.elapsedMs }}",
        )
    }

    @Test
    fun `a fatigue episode that starts during the warm-up is still coached`() {
        // Regression, from a real session (2026-08-02): the fatigue flag rose at t=46 s —
        // 14 s before the warm-up ended — stayed up for 56% of the session, and the coach
        // never spoke. The old rising-edge test consumed the transition while the policy was
        // still returning null, so it could never fire afterwards.
        val messages = run(5 * 60_000L) { t ->
            state(t, score = 70, fatigue = t >= 46_000L)
        }
        val fatigue = messages.filter { it.trigger == CoachTrigger.FATIGUE }
        assertEquals(1, fatigue.size, "expected exactly one fatigue message, got $messages")
        assertTrue(
            fatigue.first().elapsedMs in 60_000L..62_000L,
            "should speak as soon as the warm-up ends, spoke at ${fatigue.first().elapsedMs} ms",
        )
    }

    @Test
    fun `a single fatigue episode is coached once, not repeatedly`() {
        // The flip side: the episode tracking must not turn a sustained flag into a nag.
        val messages = run(40 * 60_000L) { t -> state(t, score = 70, fatigue = t >= 90_000L) }
        assertEquals(
            1, messages.count { it.trigger == CoachTrigger.FATIGUE },
            "one continuous episode should produce one message, got $messages",
        )
    }

    @Test
    fun `a second, separate fatigue episode is coached again`() {
        val messages = run(40 * 60_000L) { t ->
            state(t, score = 70, fatigue = t in 90_000L..150_000L || t >= 20 * 60_000L)
        }
        assertEquals(
            2, messages.count { it.trigger == CoachTrigger.FATIGUE },
            "two distinct episodes should each be coached, got $messages",
        )
    }

    @Test
    fun `the message carries the numbers behind it`() {
        val messages = run(8 * 60_000L) { t -> state(t, score = 30) }
        val c = messages.first()
        assertEquals(30, c.recentMeanScore)
        assertEquals(80, c.gazeOnScreenPercent)
        assertEquals(2, c.longClosures)
        // Undersampled blink rate must not reach the model as if it were a measurement.
        assertNull(c.blinkRatePerMin, "an undersampled blink rate was passed to the coach")
    }
}

/** The prompt text itself — reviewable because it is data, not string-building in an Activity. */
class CoachPromptTest {

    private fun context(trigger: CoachTrigger = CoachTrigger.FATIGUE) = CoachContext(
        trigger = trigger,
        elapsedMs = 23 * 60_000L,
        recentMeanScore = 42,
        gazeOnScreenPercent = 55,
        longClosures = 3,
        headMovementDeg = 12.0,
        perclos = 0.09,
        blinkRatePerMin = null,
    )

    @Test
    fun `the prompt states the numbers and the reason`() {
        val p = CoachPrompt.build(context(), CoachLanguage.ENGLISH)
        assertTrue(p.contains("42"), p)
        assertTrue(p.contains("55%"), p)
        assertTrue(p.contains("23 min"), p)
        assertTrue(p.contains("${CoachThresholds.MAX_WORDS} words"), p)
        assertTrue(p.contains("tired"), "the reason should be stated in words: $p")
    }

    @Test
    fun `an undersampled blink rate is simply absent, not reported as zero`() {
        // The *rate line* must be gone. The word "blinks" still appears in the fatigue
        // reason ("longer than normal blinks"), which is prose, not a measurement — an
        // earlier version of this test failed on exactly that and was testing the wrong thing.
        val p = CoachPrompt.build(context(), CoachLanguage.ENGLISH)
        assertFalse(p.contains("blinks/min"), "an undersampled rate reached the model: $p")

        val withRate = CoachPrompt.build(
            context().copy(blinkRatePerMin = 14.0), CoachLanguage.ENGLISH,
        )
        assertTrue(withRate.contains("14.0 blinks/min"), "a valid rate should be included: $withRate")
    }

    @Test
    fun `French asks for a French reply without paying for a French prompt`() {
        val fr = CoachPrompt.build(context(), CoachLanguage.FRENCH)
        val en = CoachPrompt.build(context(), CoachLanguage.ENGLISH)
        assertTrue(fr.contains("in French"), "must ask for French output: $fr")
        assertTrue(fr.contains("42"), fr)
        // The instruction stays in English on purpose. Measured on the A20e: a fully French
        // prompt tokenised to 132 tokens against 81, and at ~61 ms per prompt token that is
        // three seconds of latency bought for nothing — it also produced a refusal.
        assertTrue(
            fr.length < en.length + 30,
            "the French prompt is ${fr.length - en.length} chars longer than the English one; " +
                "prompt length is TTFT on this device",
        )
    }

    @Test
    fun `every trigger has its own stated reason in both languages`() {
        for (t in CoachTrigger.entries) {
            for (lang in CoachLanguage.entries) {
                val p = CoachPrompt.build(context(t), lang)
                assertTrue(p.length > 200, "prompt for $t/$lang looks truncated")
                assertNotNull(p.lines().last { it.isNotBlank() })
            }
        }
    }

    @Test
    fun `the prompt stays short, because its length is latency the user waits through`() {
        // Time to first token includes processing every prompt token. Measured on the A20e:
        // a 22-token prompt gave TTFT 1240 ms; a ~130-token prompt gave 9727 ms, over three
        // times the 3000 ms contract. This is a latency budget, not a style preference.
        for (trigger in CoachTrigger.entries) {
            for (lang in CoachLanguage.entries) {
                val p = CoachPrompt.build(context(trigger), lang)
                assertTrue(
                    p.length <= 400,
                    "prompt for $trigger/$lang is ${p.length} chars (~${p.length / 4} tokens); " +
                        "budget is 400 chars. Every added line is milliseconds of TTFT.",
                )
            }
        }
    }

    @Test
    fun `an overrunning model reply is trimmed to the promised length`() {
        val long = (1..120).joinToString(" ") { "word$it" }
        val trimmed = CoachPrompt.trimToWords(long, 40)
        assertEquals(40, trimmed.removeSuffix("…").split(' ').size)
        assertTrue(trimmed.endsWith("…"))
    }

    @Test
    fun `a rambling reply is cut back to the last finished sentence`() {
        // Both messages generated on the A20e ran to the token cap and stopped mid-clause.
        // A shorter finished thought reads better than a longer unfinished one.
        val rambling = "Take a short break and stretch. You have been at this a while and " +
            "your eyes are telling you something, so maybe consider whether the next twenty " +
            "minutes would really be"
        val trimmed = CoachPrompt.trimToWords(rambling, 30)
        assertTrue(trimmed.endsWith("."), "should end on a full stop, got: $trimmed")
        assertFalse(trimmed.endsWith("…"), trimmed)
        assertTrue(trimmed.startsWith("Take a short break"), trimmed)
    }

    @Test
    fun `an ellipsis is still used when the only full stop is too early`() {
        // Cutting to "Hi." would technically be a finished sentence and useless advice.
        val text = "Hi. " + (1..80).joinToString(" ") { "word$it" }
        val trimmed = CoachPrompt.trimToWords(text, 30)
        assertTrue(trimmed.endsWith("…"), "expected an ellipsis, got: $trimmed")
    }

    @Test
    fun `a reply already within the limit is left alone`() {
        val short = "Take a breath, look out of the window for twenty seconds, then come back."
        assertEquals(short, CoachPrompt.trimToWords(short, 40))
    }
}
