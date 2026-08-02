package com.focusforge.core

import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/** The recording format must survive a trip through disk without changing any answer. */
class ReplayJsonTest {

    @Test
    fun `a recording round-trips through JSON`() {
        val samples = Synthetic.stream(5_000L) { t ->
            Synthetic.sample(t, closure = 0.3, yawDeg = 10.0, pitchDeg = -5.0, irisRatio = 0.2)
        }
        val original = Synthetic.recording("focused", samples)
        val restored = ReplayJson.decode(ReplayJson.encode(original))

        assertEquals(original.frames.size, restored.frames.size)
        assertEquals(original.blendshapeNames, restored.blendshapeNames)
        assertEquals(original.landmarkIndices, restored.landmarkIndices)
        assertEquals(
            SignalReplay.summarize(original).perclos,
            SignalReplay.summarize(restored).perclos,
            1e-12,
        )
    }

    @Test
    fun `replaying a recording reproduces the live numbers`() {
        val samples = Synthetic.stream(30_000L) { t ->
            Synthetic.sample(t, closure = if (t % 5_000L < 200L && t > 0L) 1.0 else 0.02)
        }
        val live = SignalEngine().let { e -> samples.forEach { e.update(it) }; e.cumulative() }
        val replayed = SignalReplay.summarize(
            ReplayJson.decode(ReplayJson.encode(Synthetic.recording("focused", samples))),
        )
        assertEquals(live.blinkCount, replayed.blinkCount)
        assertEquals(live.perclos, replayed.perclos, 1e-6)
        assertEquals(live.gazeOnScreenFraction, replayed.gazeOnScreenFraction, 1e-6)
    }

    @Test
    fun `frames with no face carry no face data at all`() {
        val recording = Synthetic.recording(
            "focused",
            listOf(Synthetic.sample(0L, faceVisible = false)),
        )
        val frame = recording.frames.single()
        assertTrue(frame.blendshapes.isEmpty())
        assertTrue(frame.landmarks.isEmpty())
    }
}

/**
 * The ordering assertions the phase is really about, run against streams we generated
 * ourselves. These prove the *pipeline* separates the three behaviours; the operator's
 * real recordings (below) then prove it on a real face.
 */
class SyntheticOrderingTest {

    private fun focused(): LandmarkRecording = Synthetic.recording(
        "focused",
        Synthetic.stream(120_000L) { t ->
            Synthetic.sample(
                timestampMs = t,
                // Ordinary blinks: 200 ms every 5 s.
                closure = if (t % 5_000L < 200L && t > 0L) 1.0 else 0.02,
                // A person reading still drifts a couple of degrees.
                yawDeg = 2.0 * sin(t / 1_300.0),
                pitchDeg = 1.5 * sin(t / 1_700.0),
                irisRatio = 0.05 * sin(t / 900.0),
            )
        },
    )

    private fun distracted(): LandmarkRecording = Synthetic.recording(
        "distracted",
        Synthetic.stream(120_000L) { t ->
            // 10 s settling on the screen, then 8 s on a second phone / 4 s back, repeating.
            val away = t >= 10_000L && (t - 10_000L) % 12_000L >= 4_000L
            Synthetic.sample(
                timestampMs = t,
                closure = if (t % 5_000L < 200L && t > 0L) 1.0 else 0.02,
                yawDeg = if (away) 40.0 else 2.0 * sin(t / 1_300.0),
                pitchDeg = if (away) -30.0 else 1.5 * sin(t / 1_700.0),
                irisRatio = if (away) 0.7 else 0.05 * sin(t / 900.0),
            )
        },
    )

    private fun drowsy(): LandmarkRecording = Synthetic.recording(
        "drowsy",
        Synthetic.stream(120_000L) { t ->
            // Slow 1.5 s closures every 8 s, plus one yawn.
            val closed = t >= 4_000L && (t - 4_000L) % 8_000L < 1_500L
            Synthetic.sample(
                timestampMs = t,
                closure = if (closed) 1.0 else 0.02,
                yawDeg = 2.0 * sin(t / 1_300.0),
                pitchDeg = 1.5 * sin(t / 1_700.0),
                jawOpen = if (t in 60_000L until 63_000L) 0.9 else 0.05,
            )
        },
    )

    @Test
    fun `drowsy has more eye closure than focused`() {
        val f = SignalReplay.summarize(focused())
        val d = SignalReplay.summarize(drowsy())
        println("synthetic PERCLOS: focused=%.3f drowsy=%.3f".format(f.perclos, d.perclos))
        assertTrue(d.perclos > f.perclos, "drowsy=${d.perclos} focused=${f.perclos}")
    }

    @Test
    fun `drowsy has long closures where focused has blinks`() {
        val f = SignalReplay.summarize(focused())
        val d = SignalReplay.summarize(drowsy())
        assertTrue(f.blinkCount > 0, "focused should blink normally, got ${f.blinkCount}")
        assertTrue(
            d.longClosureCount > f.longClosureCount,
            "drowsy=${d.longClosureCount} focused=${f.longClosureCount}",
        )
    }

    @Test
    fun `focused looks at the screen more than distracted`() {
        val f = SignalReplay.summarize(focused())
        val x = SignalReplay.summarize(distracted())
        println(
            "synthetic gaze-on-screen: focused=%.3f distracted=%.3f"
                .format(f.gazeOnScreenFraction, x.gazeOnScreenFraction),
        )
        assertTrue(
            f.gazeOnScreenFraction > x.gazeOnScreenFraction,
            "focused=${f.gazeOnScreenFraction} distracted=${x.gazeOnScreenFraction}",
        )
    }

    @Test
    fun `distracted moves its head more than focused`() {
        val f = SignalReplay.summarize(focused())
        val x = SignalReplay.summarize(distracted())
        assertTrue(
            x.meanHeadStabilityDeg > f.meanHeadStabilityDeg,
            "distracted=${x.meanHeadStabilityDeg} focused=${f.meanHeadStabilityDeg}",
        )
    }

    @Test
    fun `the ordering survives a trip through the recording format`() {
        fun viaDisk(r: LandmarkRecording) = SignalReplay.summarize(ReplayJson.decode(ReplayJson.encode(r)))
        assertTrue(viaDisk(drowsy()).perclos > viaDisk(focused()).perclos)
        assertTrue(viaDisk(focused()).gazeOnScreenFraction > viaDisk(distracted()).gazeOnScreenFraction)
    }
}

/**
 * The same assertions against the operator's real 2-minute recordings from the phone.
 *
 * Until those files are committed under bench/replays/ these tests report as **skipped**,
 * not passed — the evidence rule (CLAUDE.md §4.1) forbids inventing data to make CI look
 * green. The recording protocol is in docs/SIGNALS.md.
 */
class RecordedOrderingTest {

    private val replayDir = File(
        System.getProperty("focusforge.replayDir") ?: "bench/replays",
    )

    private fun load(label: String): LandmarkRecording? =
        replayDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.contains(label) }
            ?.minByOrNull { it.name }
            ?.let { ReplayJson.decode(it.readText()) }

    private fun requireAll(): Triple<CumulativeSignals, CumulativeSignals, CumulativeSignals> {
        val focused = load("focused")
        val distracted = load("distracted")
        val drowsy = load("drowsy")
        Assumptions.assumeTrue(
            focused != null && distracted != null && drowsy != null,
            "NOT MEASURED YET — no operator recordings in ${replayDir.absolutePath}. " +
                "Follow the recording protocol in docs/SIGNALS.md, then commit the three " +
                "JSON files there.",
        )
        val f = SignalReplay.summarize(focused!!)
        val x = SignalReplay.summarize(distracted!!)
        val d = SignalReplay.summarize(drowsy!!)
        report("focused", f); report("distracted", x); report("drowsy", d)
        return Triple(f, x, d)
    }

    private fun report(label: String, s: CumulativeSignals) {
        println(
            ("%-11s  %5.1fs  face=%.2f  PERCLOS=%.3f  gaze=%.3f  blinks=%d (%.1f/min)  " +
                "longClosures=%d  headSpread=%.1fdeg  yawns=%d")
                .format(
                    label, s.durationMs / 1000.0, s.faceVisibleFraction, s.perclos,
                    s.gazeOnScreenFraction, s.blinkCount, s.blinkRatePerMin,
                    s.longClosureCount, s.meanHeadStabilityDeg, s.yawnCount,
                ),
        )
    }

    @Test
    fun `the recordings are usable`() {
        val (f, x, d) = requireAll()
        for ((label, s) in listOf("focused" to f, "distracted" to x, "drowsy" to d)) {
            assertTrue(s.durationMs >= 60_000L, "$label is only ${s.durationMs} ms long")
            assertTrue(
                s.faceVisibleFraction >= 0.5,
                "$label only saw a face ${s.faceVisibleFraction} of the time",
            )
        }
    }

    @Test
    fun `PERCLOS is higher when drowsy than when focused`() {
        val (f, _, d) = requireAll()
        assertTrue(d.perclos > f.perclos, "drowsy=${d.perclos} focused=${f.perclos}")
    }

    @Test
    fun `a focused reading session has a humanly plausible blink rate`() {
        // Deliberately loose. It is not a claim about blink physiology — it is a tripwire
        // for the failure that produced this test: on 2026-07-31 a session counted zero
        // blinks in 64 s and nothing in CI noticed, because every assertion here was a
        // comparison between two recordings and both were equally wrong.
        val (f, _, _) = requireAll()
        assertTrue(
            f.blinkRatePerMin in 3.0..30.0,
            "focused blink rate ${f.blinkRatePerMin}/min is not humanly plausible",
        )
        assertTrue(f.blinkCount > 0, "no blinks at all in a two-minute reading session")
    }

    @Test
    fun `gaze-on-screen is higher when focused than when distracted`() {
        val (f, x, _) = requireAll()
        assertTrue(
            f.gazeOnScreenFraction > x.gazeOnScreenFraction,
            "focused=${f.gazeOnScreenFraction} distracted=${x.gazeOnScreenFraction}",
        )
    }

    @Test
    fun `long eye closures are more frequent when drowsy than when focused`() {
        val (f, _, d) = requireAll()
        assertTrue(
            d.longClosureCount > f.longClosureCount,
            "drowsy=${d.longClosureCount} focused=${f.longClosureCount}",
        )
    }
}

/**
 * The fused score against the operator's real recordings.
 *
 * The synthetic tests in FocusScorerTest prove the mixing rule does what it says. This
 * proves the rule produces sane answers when fed a real face — which is a different
 * question, and the one that would catch a weight chosen so badly that a genuinely focused
 * session scores in the middle.
 */
class RecordedFocusScoreTest {

    private val replayDir = File(System.getProperty("focusforge.replayDir") ?: "bench/replays")

    private fun load(label: String): LandmarkRecording? =
        replayDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.contains(label) }
            ?.minByOrNull { it.name }
            ?.let { ReplayJson.decode(it.readText()) }

    private class Result(val summary: SessionSummary, val everFatigued: Boolean)

    private fun score(recording: LandmarkRecording): Result {
        val engine = SignalEngine()
        val scorer = FocusScorer()
        var everFatigued = false
        recording.samples().forEach { sample ->
            if (scorer.update(engine.update(sample)).fatigue) everFatigued = true
        }
        return Result(scorer.summary(engine.cumulative()), everFatigued)
    }

    private fun all(): Triple<Result, Result, Result> {
        val focused = load("focused")
        val distracted = load("distracted")
        val drowsy = load("drowsy")
        Assumptions.assumeTrue(
            focused != null && distracted != null && drowsy != null,
            "NOT MEASURED YET — no operator recordings in ${replayDir.absolutePath}",
        )
        val f = score(focused!!); val x = score(distracted!!); val d = score(drowsy!!)
        for ((label, r) in listOf("focused" to f, "distracted" to x, "drowsy" to d)) {
            println(
                "%-11s  meanScore=%5.1f  min=%3d  max=%3d  fatigue=%.2f of session  everFatigued=%s"
                    .format(
                        label, r.summary.meanScore, r.summary.minScore, r.summary.maxScore,
                        r.summary.fatigueFraction, r.everFatigued,
                    ),
            )
        }
        return Triple(f, x, d)
    }

    @Test
    fun `a focused session scores higher than a distracted one`() {
        val (f, x, _) = all()
        assertTrue(
            f.summary.meanScore > x.summary.meanScore,
            "focused=${f.summary.meanScore} distracted=${x.summary.meanScore}",
        )
    }

    @Test
    fun `a focused session scores higher than a drowsy one`() {
        val (f, _, d) = all()
        assertTrue(
            f.summary.meanScore > d.summary.meanScore,
            "focused=${f.summary.meanScore} drowsy=${d.summary.meanScore}",
        )
    }

    @Test
    fun `the fatigue flag fires when drowsy and never when focused`() {
        val (f, x, d) = all()
        assertTrue(d.everFatigued, "the drowsy session never raised the fatigue flag")
        assertFalse(f.everFatigued, "the focused session raised the fatigue flag")
        assertFalse(x.everFatigued, "the distracted session raised the fatigue flag")
    }
}

/**
 * The orderings across **every** committed recording, not just one per label.
 *
 * §14.2 named "n = 1 person, 1 session per state" as the weakest thing about this project and
 * "repeat recordings on different days" as the cheapest way to improve it. The operator has
 * since produced three focused, two distracted and three drowsy sessions, so the claims can
 * now be tested against all of them rather than against whichever file sorts first.
 *
 * This is a genuinely harder test: the *worst* recording of one label must beat the *best* of
 * another. One flattering session can no longer carry an assertion.
 */
class AllRecordingsOrderingTest {

    private val replayDir = File(System.getProperty("focusforge.replayDir") ?: "bench/replays")

    private class Scored(val name: String, val signals: CumulativeSignals, val meanScore: Double,
                         val everFatigued: Boolean)

    private fun load(label: String): List<Scored> =
        (replayDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("$label-") && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .map { file ->
                val recording = ReplayJson.decode(file.readText())
                val engine = SignalEngine()
                val scorer = FocusScorer()
                var fatigued = false
                recording.samples().forEach {
                    if (scorer.update(engine.update(it)).fatigue) fatigued = true
                }
                val signals = engine.cumulative()
                Scored(file.name, signals, scorer.summary(signals).meanScore, fatigued)
            }

    private fun require(vararg labels: String): Map<String, List<Scored>> {
        val loaded = labels.associateWith { load(it) }
        Assumptions.assumeTrue(
            loaded.values.all { it.isNotEmpty() },
            "NOT MEASURED YET — recordings missing from ${replayDir.absolutePath}",
        )
        return loaded
    }

    @Test
    fun `the focus score separates every focused session from every other one`() {
        val all = require("focused", "distracted", "drowsy")
        val focused = all.getValue("focused")
        val others = all.getValue("distracted") + all.getValue("drowsy")

        val worstFocused = focused.minBy { it.meanScore }
        val bestOther = others.maxBy { it.meanScore }
        assertTrue(
            worstFocused.meanScore > bestOther.meanScore,
            "the weakest focused session (${worstFocused.name}, ${worstFocused.meanScore}) must " +
                "still beat the strongest other (${bestOther.name}, ${bestOther.meanScore})",
        )
        // Reproducibility is the other half of the claim: three separate sessions of the same
        // behaviour should not produce three different answers.
        val spread = focused.maxOf { it.meanScore } - focused.minOf { it.meanScore }
        assertTrue(spread < 5.0, "focused sessions scored ${focused.map { it.meanScore }} — spread $spread")
    }

    @Test
    fun `the fatigue flag fires on every drowsy session and on no other`() {
        val all = require("focused", "distracted", "drowsy")
        for (s in all.getValue("drowsy")) {
            assertTrue(s.everFatigued, "${s.name} was recorded as drowsy but never raised the flag")
        }
        for (s in all.getValue("focused") + all.getValue("distracted")) {
            assertFalse(s.everFatigued, "${s.name} raised the fatigue flag but was not drowsy")
        }
    }

    @Test
    fun `gaze separates every focused session from every distracted one`() {
        val all = require("focused", "distracted")
        val worstFocused = all.getValue("focused").minBy { it.signals.gazeOnScreenFraction }
        val bestDistracted = all.getValue("distracted").maxBy { it.signals.gazeOnScreenFraction }
        assertTrue(
            worstFocused.signals.gazeOnScreenFraction > bestDistracted.signals.gazeOnScreenFraction,
            "${worstFocused.name} (${worstFocused.signals.gazeOnScreenFraction}) vs " +
                "${bestDistracted.name} (${bestDistracted.signals.gazeOnScreenFraction})",
        )
    }

    @Test
    fun `PERCLOS alone does NOT separate every drowsy session, and this records that`() {
        // A negative result, kept deliberately (CLAUDE.md §4.1).
        //
        // One of the three drowsy recordings measures PERCLOS 0.000 — identical to a focused
        // session — while still being unmistakably drowsy by long closures (5 against 0). Had
        // the fusion rested on PERCLOS alone, that session would have read as perfectly alert.
        // It is the clearest evidence available for the multi-signal design in §15.8, and it
        // only appeared because there was more than one recording per label.
        val all = require("focused", "drowsy")
        val drowsy = all.getValue("drowsy")
        val worstDrowsyPerclos = drowsy.minOf { it.signals.perclos }
        val worstFocusedPerclos = all.getValue("focused").maxOf { it.signals.perclos }

        assertTrue(
            worstDrowsyPerclos <= worstFocusedPerclos,
            "a drowsy session now beats every focused one on PERCLOS alone — if that is " +
                "genuinely true, this test and docs/SIGNALS.md §15.8 should be updated to say so",
        )
        // ...and the signal that does carry it must still be doing so.
        assertTrue(
            drowsy.all { it.signals.longClosureCount >= 5 },
            "long closures are what rescue the PERCLOS-blind session: " +
                "${drowsy.map { it.name to it.signals.longClosureCount }}",
        )
    }
}
