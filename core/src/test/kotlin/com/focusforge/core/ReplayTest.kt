package com.focusforge.core

import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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
