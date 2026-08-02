package dev.aarchmage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The governor loop.
 *
 * Most of these tests are about **restraint**, because the failure mode of a self-tuning
 * runtime is not that it tunes badly — it is that it never stops tuning. A component that
 * changes the frame rate every few seconds is worse than one that changes nothing, and the
 * user experiences it as an app that will not settle.
 */
class GovernorTest {

    private val runs = listOf(
        BenchmarkRun(threads = 2, promptTokens = 83, reusedTokens = 0,  ttftMs = 4913, decodeTokPerSec = 12.3),
        BenchmarkRun(threads = 2, promptTokens = 77, reusedTokens = 30, ttftMs = 2784, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 6, promptTokens = 83, reusedTokens = 0,  ttftMs = 2659, decodeTokPerSec = 17.1),
        BenchmarkRun(threads = 6, promptTokens = 77, reusedTokens = 30, ttftMs = 1481, decodeTokPerSec = 14.4),
    )

    private fun profile(visionFps: Double = 8.0, threads: Int = 2): DeviceProfile {
        val report = BenchmarkReport(
            topology = CpuTopology(8, listOf(CpuCluster(listOf(6, 7), 1_560_000),
                CpuCluster((0..5).toList(), 1_352_000)), "fp asimd", 3L * 1024 * 1024 * 1024),
            runs = runs,
            costModel = CostModel.fit(runs),
            memory = MemoryProbe(133L * 1024 * 1024, 554L * 1024 * 1024, 557L * 1024 * 1024, 1900),
            thermal = null, durationMs = 40_000, budgetMs = 60_000, complete = true,
        )
        return DeviceProfile(
            topology = report.topology,
            contract = PerformanceContract(),
            chosen = ChosenConfig(threads = threads, nCtx = 512, visionFpsBudget = visionFps),
            reasons = emptyList(),
            evidence = report,
        )
    }

    private fun ok(atMs: Long) = WindowMeasurement(
        elapsedMs = atMs, ttftMs = 1500, decodeTokPerSec = 14.0, visionFps = 8.0,
        rssBytes = 560L * 1024 * 1024)

    private fun slow(atMs: Long, ttft: Long = 9727) = WindowMeasurement(
        elapsedMs = atMs, ttftMs = ttft, decodeTokPerSec = 7.6, visionFps = 8.3,
        rssBytes = 580L * 1024 * 1024)

    // ------------------------------------------------------------------ restraint

    @Test
    fun `a compliant session is left completely alone`() {
        val g = Governor(profile())
        for (i in 1..40) assertNull(g.observe(ok(i * 30_000L)), "acted on a healthy session")
        assertTrue(g.decisions.isEmpty())
        assertEquals(8.0, g.current.visionFpsBudget, 0.01)
    }

    @Test
    fun `one bad window is not enough to act on`() {
        val g = Governor(profile())
        assertNull(g.observe(slow(30_000)), "a single slow window is noise, not a trend")
        assertTrue(g.decisions.isEmpty())
    }

    @Test
    fun `a sustained violation is acted on, once`() {
        val g = Governor(profile())
        assertNull(g.observe(slow(30_000)))
        val decision = g.observe(slow(60_000))
        assertNotNull(decision, "two consecutive violations should be acted on")
        assertEquals("visionFpsBudget", decision.knob)
        assertTrue(decision.applied)
        assertEquals(6.0, g.current.visionFpsBudget, 0.01, "8.0 minus one step of 2.0")

        // ...and then it waits, rather than turning the knob again immediately.
        assertNull(g.observe(slow(70_000)), "cooldown must suppress a second decision")
        assertNull(g.observe(slow(80_000)))
        assertEquals(1, g.decisions.size)
    }

    @Test
    fun `it changes exactly one knob per decision`() {
        val g = Governor(profile())
        repeat(20) { i -> g.observe(slow(i * 70_000L)) }
        assertTrue(g.decisions.isNotEmpty())
        // Every entry names a single knob; nothing bundles two changes together, because
        // then the next window could not attribute the effect.
        assertTrue(g.decisions.all { it.knob.isNotEmpty() && !it.knob.contains(",") })
        val applied = g.decisions.filter { it.applied }
        assertTrue(applied.all { it.knob == "visionFpsBudget" },
            "only the fps budget is wired up this cycle: ${applied.map { it.knob }}")
    }

    @Test
    fun `it will not lower the frame rate below the floor`() {
        val g = Governor(profile(visionFps = 8.0))
        repeat(30) { i -> g.observe(slow(i * 70_000L)) }
        assertTrue(g.current.visionFpsBudget >= 3.0,
            "went to ${g.current.visionFpsBudget}, below the floor where signals stop meaning anything")
    }

    // ------------------------------------------------------------------ the ladder

    @Test
    fun `once the frame rate is spent it moves down the ladder and says it did not apply it`() {
        val g = Governor(profile(visionFps = 3.0))   // already at the floor
        g.observe(slow(30_000))
        val decision = g.observe(slow(60_000))
        assertNotNull(decision)
        assertEquals("nCtx", decision.knob, "the next rung after the frame budget")
        assertFalse(decision.applied, "n_ctx is derived and logged this cycle, not applied")
        assertTrue(decision.note.contains("NOT APPLIED"), decision.note)
        assertTrue(decision.note.contains("live UI"), "the reason must be stated: ${decision.note}")
    }

    @Test
    fun `a memory violation with everything else exhausted reaches the thread rung`() {
        val g = Governor(profile(visionFps = 3.0, threads = 2),
            config = GovernorConfig(nCtxFloor = 512))   // n_ctx already at its floor
        g.observe(slow(30_000))
        val decision = g.observe(slow(60_000))
        assertNotNull(decision)
        assertEquals("threads", decision.knob)
        assertEquals("6", decision.to, "the cost model knows 6 threads is faster")
        assertFalse(decision.applied)
        assertTrue(decision.note.contains("reopening the model"), decision.note)
        assertTrue(decision.note.contains("31.2") || decision.note.contains("prefill"),
            "the predicted gain should be stated: ${decision.note}")
    }

    @Test
    fun `when nothing is left it says so instead of pretending to act`() {
        val g = Governor(
            profile(visionFps = 3.0, threads = 6),
            config = GovernorConfig(nCtxFloor = 512),
        )
        g.observe(slow(30_000))
        val decision = g.observe(slow(60_000))
        assertNotNull(decision)
        assertEquals("none", decision.knob)
        assertFalse(decision.applied)
        assertTrue(decision.note.contains("cannot be met by"), decision.note)
    }

    // ------------------------------------------------------------------ recovery

    @Test
    fun `it gives the frame budget back, but slowly`() {
        val g = Governor(profile())
        g.observe(slow(30_000)); g.observe(slow(60_000))
        assertEquals(6.0, g.current.visionFpsBudget, 0.01)

        // Recovery needs more consecutive good windows than a violation needs bad ones.
        var t = 130_000L
        repeat(4) { assertNull(g.observe(ok(t)), "relaxed too early"); t += 30_000 }
        val relax = g.observe(ok(t))
        assertNotNull(relax, "five good windows should earn something back")
        assertEquals(8.0, g.current.visionFpsBudget, 0.01)
        assertNull(relax.trigger, "a relaxation is not triggered by a violation")
        assertTrue(relax.note.contains("given back"), relax.note)
    }

    @Test
    fun `it never gives back more than the profile derived`() {
        val g = Governor(profile(visionFps = 8.0))
        var t = 30_000L
        repeat(60) { g.observe(ok(t)); t += 30_000 }
        assertEquals(8.0, g.current.visionFpsBudget, 0.01,
            "the profile's budget is a ceiling, not a starting point to grow from")
    }

    @Test
    fun `a value hovering on the limit does not make it oscillate`() {
        // The real risk: TTFT sitting right at 3000 ms, alternating either side.
        val g = Governor(profile())
        var t = 30_000L
        repeat(60) { i ->
            g.observe(if (i % 2 == 0) slow(t, ttft = 3100) else ok(t))
            t += 30_000
        }
        assertTrue(g.decisions.size <= 2,
            "made ${g.decisions.size} changes while the input hovered: ${g.decisions.map { it.knob }}")
    }

    // ------------------------------------------------------------------ the record

    @Test
    fun `every decision carries the measurement that caused it`() {
        val g = Governor(profile())
        g.observe(slow(30_000))
        val decision = g.observe(slow(60_000))!!

        val trigger = decision.trigger
        assertNotNull(trigger, "a reaction with no trigger is unauditable")
        assertEquals("ttftMs", trigger.term)
        assertEquals(9727.0, trigger.measured)
        assertEquals(3000.0, trigger.limit)
        assertFalse(trigger.satisfied)
        assertTrue(trigger.summary.contains("VIOLATES"), trigger.summary)
        assertTrue(decision.note.length > 40, "a decision needs a stated reason")
    }

    @Test
    fun `it answers the term that missed by the widest margin`() {
        val g = Governor(profile())
        // TTFT is 3.2x its limit; decode is only just under. TTFT is the one to answer.
        val window = WindowMeasurement(elapsedMs = 30_000, ttftMs = 9727,
            decodeTokPerSec = 4.9, visionFps = 8.0)
        g.observe(window)
        val decision = g.observe(window.copy(elapsedMs = 60_000))!!
        assertEquals("ttftMs", decision.trigger?.term)
    }

    @Test
    fun `an unmeasured window provokes nothing`() {
        val g = Governor(profile())
        repeat(10) { i ->
            assertNull(
                g.observe(WindowMeasurement(elapsedMs = i * 30_000L)),
                "a window with no measurements is not evidence of a problem",
            )
        }
    }

    @Test
    fun `the decision log round-trips for the session export`() {
        val g = Governor(profile())
        g.observe(slow(30_000)); g.observe(slow(60_000))
        val json = DeviceProfile.JSON.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(GovernorDecision.serializer()),
            g.decisions,
        )
        val restored = DeviceProfile.JSON.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GovernorDecision.serializer()), json)
        assertEquals(g.decisions, restored)
        assertTrue(json.contains("VIOLATES"), "the trigger must survive into the export")
    }
}
