package com.focusforge.core

import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions

/**
 * Not an assertion — a **report**, printed for a human to read.
 *
 * The ordering tests in ReplayTest compare one number per recording. That answers "does
 * drowsy score higher than focused", which is the weakest possible question: two single
 * numbers always order somehow. The question that matters for a live coach is whether a
 * *moment* can be classified — whether a rolling window taken from the drowsy session is
 * distinguishable from a rolling window taken from the focused one, or whether the two
 * spreads overlap so much that any instant is ambiguous.
 *
 * So this walks each recording, samples the rolling snapshots the app would actually show,
 * and prints the spread. Overlap between sessions is the honest measure of how much a
 * single reading can be trusted. No thresholds are asserted, because with one person and
 * one session per state there is nothing here to assert — see docs/SIGNALS.md §14.
 */
class SeparationReport {

    private val replayDir = File(System.getProperty("focusforge.replayDir") ?: "bench/replays")

    private fun load(label: String): LandmarkRecording? =
        replayDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.contains(label) }
            ?.minByOrNull { it.name }
            ?.let { ReplayJson.decode(it.readText()) }

    /** Snapshots from the second minute onwards, so every rolling window is full. */
    private fun settled(recording: LandmarkRecording): List<SignalSnapshot> =
        SignalReplay.snapshots(recording).filter { it.elapsedMs >= 60_000L }

    private fun spread(values: List<Double>): String {
        if (values.isEmpty()) return "no data"
        val s = values.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return "min=%.3f  p25=%.3f  median=%.3f  p75=%.3f  max=%.3f"
            .format(p(0.0), p(0.25), p(0.5), p(0.75), p(1.0))
    }

    private fun overlaps(a: List<Double>, b: List<Double>): String {
        if (a.isEmpty() || b.isEmpty()) return "?"
        val aMin = a.min(); val aMax = a.max(); val bMin = b.min(); val bMax = b.max()
        return if (aMin > bMax || bMin > aMax) "SEPARATED" else "OVERLAP"
    }

    @Test
    fun `rolling-window spread per session`() {
        val focused = load("focused")
        val distracted = load("distracted")
        val drowsy = load("drowsy")
        Assumptions.assumeTrue(
            focused != null && distracted != null && drowsy != null,
            "NOT MEASURED YET — no operator recordings in ${replayDir.absolutePath}",
        )
        val sessions = listOf(
            "focused" to settled(focused!!),
            "distracted" to settled(distracted!!),
            "drowsy" to settled(drowsy!!),
        )

        val measures = listOf<Pair<String, (SignalSnapshot) -> Double?>>(
            "PERCLOS" to { s -> s.perclos },
            "gazeOnScreenFrac" to { s -> s.gazeOnScreenFraction },
            "headStabilityDeg" to { s -> s.headStabilityDeg },
            "blinkRatePerMin" to { s -> s.blinkRatePerMin },
        )

        println("\n=== Rolling-window spread (second minute onwards, one window per frame)")
        for ((name, get) in measures) {
            println("  $name")
            val byLabel = sessions.associate { (label, snaps) ->
                label to snaps.mapNotNull(get)
            }
            for ((label, values) in byLabel) {
                println("    %-11s n=%-4d %s".format(label, values.size, spread(values)))
            }
            val f = byLabel.getValue("focused")
            val x = byLabel.getValue("distracted")
            val d = byLabel.getValue("drowsy")
            println("    focused vs drowsy: %s   focused vs distracted: %s   distracted vs drowsy: %s"
                .format(overlaps(f, d), overlaps(f, x), overlaps(x, d)))
        }
    }
}
