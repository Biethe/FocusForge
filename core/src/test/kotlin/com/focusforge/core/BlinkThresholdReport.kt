package com.focusforge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Not an assertion — a **report**, and the evidence behind `EYE_CLOSE_LEVEL` and
 * `EYE_OPEN_LEVEL`.
 *
 * Choosing a blink threshold has two failure directions and they pull against each other:
 *
 * - Too **high** and shallow blinks are missed. At 0.50 the focused recording produced
 *   5 blinks/min against 37 visible closure events — roughly half were invisible.
 * - Too **low an exit** and closures never end. The eye aspect ratio falls when the user
 *   looks *down*, not only when the lid closes, so during the distracted recording the
 *   resting closure sits at 0.192 (p75). With an exit level under that, a 200 ms blink
 *   never crosses back into "open" and runs on until the head comes up — arriving as a
 *   multi-second "long closure", which then feeds the fatigue flag. That is how a first
 *   attempt at this retune made the distracted session read as fatigued for 60% of its
 *   length.
 *
 * So the pair is swept together, through the real engine, over the real recordings.
 */
class BlinkThresholdReport {

    private val replayDir = File(System.getProperty("focusforge.replayDir") ?: "bench/replays")

    private fun load(label: String): LandmarkRecording? =
        replayDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.contains(label) }
            ?.minByOrNull { it.name }
            ?.let { ReplayJson.decode(it.readText()) }

    /**
     * The blink probe is the only recording in this project with **ground truth**: the
     * operator performed a counted 10 normal blinks and 3 one-second closures to protocol
     * (docs/SIGNALS.md §16.6). Everything else we have is unlabelled behaviour.
     *
     * The bounds are wide on purpose. At 8.4 fps a 150 ms blink is one or two frames and
     * some fall entirely between them, so detecting all 10 is not physically possible —
     * this asserts the pipeline is in the right range, not that it is exact.
     */
    @Test
    fun `the blink probe detects roughly what the operator performed`() {
        val probe = load("blinkprobe")
        Assumptions.assumeTrue(
            probe != null,
            "NOT MEASURED YET — no blinkprobe recording in ${replayDir.absolutePath}",
        )
        val totals = SignalReplay.summarize(probe!!)
        println("   blinkprobe: blinks=${totals.blinkCount} longClosures=${totals.longClosureCount} " +
            "(operator performed 10 blinks + 3 one-second closures)")
        assertTrue(
            totals.blinkCount in 5..15,
            "10 deliberate blinks were performed, ${totals.blinkCount} detected",
        )
        assertTrue(
            totals.longClosureCount in 2..5,
            "3 one-second closures were performed, ${totals.longClosureCount} detected",
        )
    }

    @Test
    fun `what the old thresholds would have made of the blink probe`() {
        val probe = load("blinkprobe") ?: return
        println("\n=== Blink probe: 10 performed blinks + 3 one-second closures")
        println("   %-24s %8s %8s".format("close/open (shared)", "blinks", "long"))
        for ((close, open) in listOf(0.50 to 0.35, 0.40 to 0.28, 0.30 to 0.18)) {
            val t = SignalReplay.summarize(probe, SignalConfig(
                eyeCloseLevel = close, eyeOpenLevel = open,
                longClosureLevel = close, longClosureOpenLevel = open,
            ))
            println("   %-24s %8d %8d".format(
                "%.2f / %.2f".format(close, open), t.blinkCount, t.longClosureCount))
        }
        val shipped = SignalReplay.summarize(probe)
        println("   %-24s %8d %8d".format("shipped (split)", shipped.blinkCount, shipped.longClosureCount))
    }

    @Test
    fun `sweep the close and open levels over the real recordings`() {
        val labels = listOf("focused", "distracted", "drowsy")
        val recordings = labels.associateWith { load(it) }
        Assumptions.assumeTrue(
            recordings.values.all { it != null },
            "NOT MEASURED YET — no operator recordings in ${replayDir.absolutePath}",
        )

        println("\n=== Blink threshold sweep (real engine, operator's recordings)")
        println("   Want: focused blink rate in a human 3-30/min band, long closures near")
        println("   zero when focused, clearly highest when drowsy, and not inflated when")
        println("   distracted (that would be a look-down artifact, not a closure).")
        println("   %-13s %26s %26s".format("", "blinks/min", "long closures"))
        println("   %-13s %8s %8s %8s %8s %8s %8s"
            .format("close/open", "focus", "distr", "drowsy", "focus", "distr", "drowsy"))

        val pairs = listOf(
            0.50 to 0.35, // what Phase 3 shipped
            0.45 to 0.30,
            0.40 to 0.28,
            0.35 to 0.25,
            0.35 to 0.20,
            0.30 to 0.20,
            0.30 to 0.18, // first attempt at this retune — breaks distracted
            0.25 to 0.15,
        )
        // Emulates the ORIGINAL single-machine design, where long closures shared the blink
        // thresholds. This is the evidence for splitting them: read the "distr" long-closure
        // column falling apart as the pair is lowered to catch shallow blinks.
        println("   -- one shared machine (the design this patch replaced)")
        for ((close, open) in pairs) {
            val config = SignalConfig(
                eyeCloseLevel = close, eyeOpenLevel = open,
                longClosureLevel = close, longClosureOpenLevel = open,
            )
            val totals = labels.map { label ->
                SignalReplay.summarize(recordings.getValue(label)!!, config)
            }
            println("   %-13s %8.1f %8.1f %8.1f %8d %8d %8d".format(
                "%.2f / %.2f".format(close, open),
                totals[0].blinkRatePerMin, totals[1].blinkRatePerMin, totals[2].blinkRatePerMin,
                totals[0].longClosureCount, totals[1].longClosureCount, totals[2].longClosureCount,
            ))
        }
        // The shipped design: blinks on the sensitive pair, long closures on their own
        // stricter pair, so lowering one cannot corrupt the other.
        println("   -- two machines (shipped): blinks %.2f/%.2f, long closures %.2f/%.2f"
            .format(SignalThresholds.EYE_CLOSE_LEVEL, SignalThresholds.EYE_OPEN_LEVEL,
                SignalThresholds.LONG_CLOSURE_LEVEL, SignalThresholds.LONG_CLOSURE_OPEN_LEVEL))
        val shipped = labels.map { SignalReplay.summarize(recordings.getValue(it)!!) }
        println("   %-13s %8.1f %8.1f %8.1f %8d %8d %8d".format(
            "shipped",
            shipped[0].blinkRatePerMin, shipped[1].blinkRatePerMin, shipped[2].blinkRatePerMin,
            shipped[0].longClosureCount, shipped[1].longClosureCount, shipped[2].longClosureCount,
        ))
    }
}
