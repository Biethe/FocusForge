package dev.aarchmage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The self-benchmark harness, driven by a **simulated device calibrated to the real one**.
 *
 * The simulation reproduces the A20e's measured behaviour — 59.2 / 41.7 / 32.0 ms per fresh
 * prompt token at 2 / 4 / 6 threads, a ~1.9 s model load, cache reuse of the shared prompt
 * opening — so these tests exercise the harness against numbers the phone actually produced
 * rather than against invented ones. A virtual clock makes a 60-second benchmark run in
 * microseconds and makes budget behaviour exactly reproducible.
 */
class SelfBenchmarkTest {

    /** A clock the test controls, so "60 seconds" costs no real time and never flakes. */
    private class FakeClock(var now: Long = 0) : PlatformSensors {
        var rss = 133L * 1024 * 1024
        override fun rssBytes() = rss
        override fun batteryPercent(): Double? = null
        override fun thermalStatus(): String? = null
        override fun nowMs() = now
    }

    /**
     * A device that behaves like the A20e.
     *
     * @param derateAfterMs generation slows by [deratePercent] after this much load, to
     *        simulate thermal throttling. Zero means a machine that never slows.
     */
    private class FakeDevice(
        val clock: FakeClock,
        val msPerFreshToken: Map<Int, Double> = mapOf(2 to 59.2, 4 to 41.7, 6 to 32.0, 1 to 110.0, 8 to 30.0),
        val decodeTokPerSec: Map<Int, Double> = mapOf(2 to 12.3, 4 to 14.0, 6 to 16.5, 1 to 7.0, 8 to 16.0),
        val loadMs: Long = 1900,
        val modelBytes: Long = 421L * 1024 * 1024,
        val failAtThreads: Set<Int> = emptySet(),
        val derateAfterMs: Long = 0,
        val deratePercent: Double = 0.0,
    ) : InferenceBackend {
        override var lastError: String = ""
        var opens = 0
        var generations = 0
        private var loadStartedAt = 0L

        override fun open(config: RunConfig): OpenModel? {
            if (config.threads in failAtThreads) {
                lastError = "simulated failure at ${config.threads} threads"
                return null
            }
            opens++
            clock.now += loadMs
            clock.rss = 133L * 1024 * 1024 + modelBytes
            loadStartedAt = clock.now
            return object : OpenModel {
                override val loadMs = this@FakeDevice.loadMs
                override val rssAfterLoadBytes = clock.rss
                private var cached = ""

                override fun generate(prompt: String, maxTokens: Int): GenerationResult? {
                    generations++
                    // Token counts stand in for real tokenisation: 4 characters per token is
                    // the same rule of thumb used elsewhere in this project.
                    val promptTokens = prompt.length / 4
                    val shared = prompt.commonPrefixWith(cached).length / 4
                    cached = prompt
                    val fresh = (promptTokens - shared).coerceAtLeast(1)
                    val perToken = msPerFreshToken[config.threads] ?: 60.0
                    var decode = decodeTokPerSec[config.threads] ?: 10.0
                    if (derateAfterMs > 0 && clock.now - loadStartedAt > derateAfterMs) {
                        decode *= (1.0 - deratePercent / 100.0)
                    }
                    val ttft = (fresh * perToken).toLong()
                    val decodeMs = (maxTokens * 1000.0 / decode).toLong()
                    clock.now += ttft + decodeMs
                    return GenerationResult(
                        ttftMs = ttft, decodeMs = decodeMs, tokens = maxTokens,
                        promptTokens = promptTokens, reusedTokens = shared,
                        rssBytes = clock.rss, text = "ok",
                    )
                }

                override fun close() {}
            }
        }
    }

    private val phone = CpuTopology(
        coreCount = 8,
        clusters = listOf(
            CpuCluster(listOf(6, 7), 1_560_000),
            CpuCluster((0..5).toList(), 1_352_000),
        ),
        features = "fp asimd aes sha1 sha2 crc32",
        totalRamBytes = 3L * 1024 * 1024 * 1024,
    )

    @Test
    fun `it measures every thread count and fits a model that matches the device`() {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        val report = SelfBenchmark(phone, device, clock,
            BenchmarkPlan(threadCounts = listOf(2, 4, 6))).run()

        assertTrue(report.complete, "notes: ${report.notes}")
        assertEquals(6, report.runs.size, "3 thread counts x 2 prompts")

        // The fitted model must recover the simulated device's constants.
        for ((threads, expected) in listOf(2 to 59.2, 4 to 41.7, 6 to 32.0)) {
            val cost = report.costModel.forThreads(threads)
            assertNotNull(cost, "no cost entry for $threads threads")
            assertEquals(expected, cost.msPerFreshToken, 3.0,
                "$threads threads: fitted ${cost.msPerFreshToken}, device is $expected")
        }
    }

    @Test
    fun `the second prompt reuses the shared opening`() {
        val clock = FakeClock()
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(threadCounts = listOf(6))).run()

        val (first, second) = report.runs
        assertEquals(0, first.reusedTokens, "nothing is cached before the first generation")
        assertTrue(second.reusedTokens > 10,
            "the two prompts share an opening; got ${second.reusedTokens} reused")
        assertTrue(second.ttftMs < first.ttftMs, "reuse must make the second prompt cheaper")
    }

    @Test
    fun `it derives the thread sweep from the machine when not told`() {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        // No threadCounts given: [1, 2, 6, 8] for this topology, and a generous budget.
        val report = SelfBenchmark(phone, device, clock, BenchmarkPlan(budgetMs = 600_000)).run()
        assertEquals(listOf(1, 2, 6, 8), report.runs.map { it.threads }.distinct())
    }

    // ------------------------------------------------------------------ the budget

    @Test
    fun `it stops when the budget runs out and says so`() {
        val clock = FakeClock()
        val device = FakeDevice(clock, loadMs = 5_000)
        val report = SelfBenchmark(phone, device, clock,
            BenchmarkPlan(budgetMs = 20_000, threadCounts = listOf(2, 4, 6))).run()

        assertFalse(report.complete, "a truncated sweep must not report itself as complete")
        assertTrue(
            report.notes.any { it.startsWith("stopped after") },
            "the report must say why it stopped: ${report.notes}",
        )
        assertTrue(report.runs.isNotEmpty(), "it should still keep what it did measure")
        assertTrue(
            report.runs.map { it.threads }.distinct().size < 3,
            "it cannot have completed all three thread counts inside the budget",
        )
    }

    @Test
    fun `the first step always runs, because refusing on no evidence means never starting`() {
        val clock = FakeClock()
        // A budget far too small for even one step. The harness cannot know that until it
        // has measured something, so it must try once rather than return nothing at all.
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(budgetMs = 1, threadCounts = listOf(2, 4, 6))).run()
        assertTrue(report.runs.isNotEmpty(), "the first measurement must always be attempted")
    }

    @Test
    fun `a sixty second budget is enough for the real device's sweep`() {
        // The architect's spec is "about 60 s, one-time". This checks the plan is actually
        // affordable on a device that behaves like the operator's, rather than hoping.
        val clock = FakeClock()
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(budgetMs = 60_000, threadCounts = listOf(2, 4, 6))).run()
        assertTrue(report.complete, "did not fit in 60 s: ${report.notes}")
        assertTrue(report.durationMs <= 60_000,
            "took ${report.durationMs} ms of a ${report.budgetMs} ms budget")
    }

    // ------------------------------------------------------------------ the other probes

    @Test
    fun `memory is measured against the budget`() {
        val clock = FakeClock()
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(threadCounts = listOf(6))).run()

        val memory = report.memory
        assertNotNull(memory)
        assertEquals(1900, memory.loadMs)
        assertEquals(421L * 1024 * 1024, memory.modelCostBytes)
        assertTrue(memory.withinBudget(700L * 1024 * 1024), "peak ${memory.peakBytes()}")
        assertFalse(memory.withinBudget(400L * 1024 * 1024))
        assertTrue(memory.withinBudget(null), "a null limit is not a failed check")
    }

    @Test
    fun `sustained load that slows down is reported as derating`() {
        val clock = FakeClock()
        val device = FakeDevice(clock, derateAfterMs = 3_000, deratePercent = 30.0)
        val report = SelfBenchmark(phone, device, clock,
            BenchmarkPlan(budgetMs = 120_000, threadCounts = listOf(6),
                sustainMinMs = 5_000, sustainMaxMs = 30_000)).run()

        val thermal = report.thermal
        assertNotNull(thermal, "notes: ${report.notes}")
        assertTrue(thermal.samples >= 3)
        assertTrue(thermal.deratingPercent > 15.0,
            "a device that slowed 30% should report it, got ${thermal.deratingPercent}%")
        assertTrue(thermal.lastThirdTokPerSec < thermal.firstThirdTokPerSec)
        assertNull(thermal.vendorThermalStatus, "this platform exposes no thermal level")
    }

    @Test
    fun `a machine that does not slow down reports no derating`() {
        val clock = FakeClock()
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(budgetMs = 120_000, threadCounts = listOf(6),
                sustainMinMs = 5_000, sustainMaxMs = 30_000)).run()
        val thermal = report.thermal
        assertNotNull(thermal)
        assertEquals(0.0, thermal.deratingPercent, 1.0)
    }

    @Test
    fun `the sustain probe is skipped rather than rushed when time is short`() {
        val clock = FakeClock()
        val report = SelfBenchmark(phone, FakeDevice(clock), clock,
            BenchmarkPlan(budgetMs = 26_000, threadCounts = listOf(2, 4),
                sustainMinMs = 15_000)).run()
        assertNull(report.thermal, "a rushed sustain probe would be worse than none")
        assertTrue(report.notes.any { it.contains("skipped the sustain probe") }, "${report.notes}")
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a thread count that will not open is recorded and the sweep continues`() {
        val clock = FakeClock()
        val device = FakeDevice(clock, failAtThreads = setOf(4))
        val report = SelfBenchmark(phone, device, clock,
            BenchmarkPlan(threadCounts = listOf(2, 4, 6))).run()

        assertTrue(report.notes.any { it.contains("could not open") }, "${report.notes}")
        assertEquals(listOf(2, 6), report.runs.map { it.threads }.distinct(),
            "one bad configuration must not abandon the whole benchmark")
        assertNotNull(report.costModel.forThreads(6))
    }

    @Test
    fun `a device where nothing works produces an empty but honest report`() {
        val clock = FakeClock()
        val device = FakeDevice(clock, failAtThreads = setOf(1, 2, 4, 6, 8))
        val report = SelfBenchmark(phone, device, clock, BenchmarkPlan()).run()

        assertTrue(report.runs.isEmpty())
        assertFalse(report.complete)
        assertTrue(report.costModel.perThreadCount.isEmpty())
        assertNull(report.costModel.fastest())
        assertTrue(report.notes.any { it.contains("no successful runs") }, "${report.notes}")
    }
}
