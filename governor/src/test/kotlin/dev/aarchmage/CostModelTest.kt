package dev.aarchmage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cost model, tested against **the real device measurements it was derived from**.
 *
 * This is the strongest fixture available: the numbers come from the operator's A20e
 * (`bench/results/a20e-threads-kvcache-20260802.json`), so a regression here means the model
 * has stopped describing the actual phone, not merely that a synthetic expectation moved.
 */
class CostModelTest {

    /** The nine runs the operator measured, verbatim. */
    private val a20e = listOf(
        BenchmarkRun(threads = 2, promptTokens = 83, reusedTokens = 0,  ttftMs = 4913, decodeTokPerSec = 12.3),
        BenchmarkRun(threads = 2, promptTokens = 77, reusedTokens = 30, ttftMs = 2784, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 2, promptTokens = 81, reusedTokens = 30, ttftMs = 3035, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 4, promptTokens = 83, reusedTokens = 0,  ttftMs = 3458, decodeTokPerSec = 14.0),
        BenchmarkRun(threads = 4, promptTokens = 77, reusedTokens = 30, ttftMs = 1940, decodeTokPerSec = 14.1),
        BenchmarkRun(threads = 4, promptTokens = 81, reusedTokens = 30, ttftMs = 2102, decodeTokPerSec = 14.1),
        BenchmarkRun(threads = 6, promptTokens = 83, reusedTokens = 0,  ttftMs = 2659, decodeTokPerSec = 17.1),
        BenchmarkRun(threads = 6, promptTokens = 77, reusedTokens = 30, ttftMs = 1481, decodeTokPerSec = 14.4),
        BenchmarkRun(threads = 6, promptTokens = 81, reusedTokens = 30, ttftMs = 1632, decodeTokPerSec = 16.8),
    )

    @Test
    fun `the fitted model reproduces the device it was measured on`() {
        val model = CostModel.fit(a20e)
        assertEquals(3, model.perThreadCount.size)

        for (run in a20e) {
            val cost = model.forThreads(run.threads)!!
            val predicted = cost.predictTtftMs(run.promptTokens, run.reusedTokens)
            val errorPercent = 100.0 * (predicted - run.ttftMs) / run.ttftMs
            assertTrue(
                kotlin.math.abs(errorPercent) < 5.0,
                "threads=${run.threads} fresh=${run.promptTokens - run.reusedTokens}: " +
                    "predicted ${predicted.toInt()} ms vs measured ${run.ttftMs} ms " +
                    "(${"%.1f".format(errorPercent)}%)",
            )
        }
    }

    @Test
    fun `more threads means faster prefill on this device`() {
        val model = CostModel.fit(a20e)
        val two = model.forThreads(2)!!
        val six = model.forThreads(6)!!
        assertTrue(six.prefillTokPerSec > two.prefillTokPerSec)
        // Measured 16.9 -> 31.2 tok/s. Sub-linear: 3x the threads bought 1.85x the speed,
        // because six of the eight cores are A53s.
        assertTrue(two.prefillTokPerSec in 15.0..19.0, "got ${two.prefillTokPerSec}")
        assertTrue(six.prefillTokPerSec in 28.0..34.0, "got ${six.prefillTokPerSec}")
    }

    @Test
    fun `it picks the cheapest configuration that meets the contract, not the fastest`() {
        val model = CostModel.fit(a20e)
        val contract = PerformanceContract(ttftMsMax = 3000, decodeTokPerSecMin = 5.0)

        // A warm 47-token prompt: 2 threads is predicted at ~2780 ms, inside the contract.
        // The governor must take it and leave four cores alone rather than grabbing the
        // fastest option available.
        val warm = model.cheapestMeeting(contract, promptTokens = 77, reusedTokens = 30)
        assertNotNull(warm)
        assertEquals(2, warm.threads, "spare cores on a phone are battery and heat")

        // Cold, 83 fresh tokens: 2 threads is predicted at ~4900 ms and misses, so the
        // governor must step up — but only as far as it has to.
        val cold = model.cheapestMeeting(contract, promptTokens = 83, reusedTokens = 0)
        assertNotNull(cold)
        assertEquals(6, cold.threads, "2 and 4 threads both miss 3000 ms cold on this device")
    }

    @Test
    fun `when nothing can meet the contract it says so instead of guessing`() {
        val model = CostModel.fit(a20e)
        val impossible = PerformanceContract(ttftMsMax = 100)
        assertNull(
            model.cheapestMeeting(impossible, promptTokens = 83),
            "no configuration meets a 100 ms budget; returning the best of a bad set would " +
                "let a caller believe the contract was satisfied",
        )
        assertNotNull(model.fastest(), "the fastest option is still available to report")
        assertEquals(6, model.fastest()!!.threads)
    }

    @Test
    fun `a single measurement fits a slope but never invents an intercept`() {
        // One point cannot determine two parameters. Fitting an intercept from it would
        // produce a model that predicts confidently and wrongly.
        val model = CostModel.fit(
            listOf(BenchmarkRun(threads = 4, promptTokens = 80, reusedTokens = 0, ttftMs = 3200)),
        )
        val cost = model.forThreads(4)!!
        assertEquals(40.0, cost.msPerFreshToken, 0.01)
        assertEquals(0.0, cost.interceptMs, 1e-9)
    }

    @Test
    fun `cache reuse is worth exactly the tokens it saves`() {
        val model = CostModel.fit(a20e)
        val six = model.forThreads(6)!!
        val cold = six.predictTtftMs(promptTokens = 83, reusedTokens = 0)
        val warm = six.predictTtftMs(promptTokens = 83, reusedTokens = 30)
        assertEquals(30 * six.msPerFreshToken, cold - warm, 1.0)
    }
}

/** The contract, and the distinction between "unmeasured" and "bad". */
class ContractTest {

    @Test
    fun `an unmeasured term is not a violation`() {
        // "We did not look" and "we looked and it was bad" are different states. Conflating
        // them would have the governor reacting to nothing at all.
        val violations = ContractChecker.violations(
            PerformanceContract(),
            WindowMeasurement(elapsedMs = 60_000, visionFps = 8.4),
        )
        assertTrue(violations.isEmpty(), "got $violations")
    }

    @Test
    fun `a null limit records the measurement without enforcing it`() {
        // Battery has no measured baseline yet, so its limit is null on purpose.
        val checks = ContractChecker.check(
            PerformanceContract(),
            WindowMeasurement(elapsedMs = 60_000, batteryPercentPerHour = 42.0),
        )
        val battery = checks.single { it.term == "batteryPercentPerHour" }
        assertTrue(battery.recordedOnly)
        assertTrue(battery.satisfied, "an unenforced term must never be reported as violated")
        assertTrue(battery.summary.contains("not enforced"), battery.summary)
    }

    @Test
    fun `it catches the violation the coach actually produced`() {
        // The real Phase 5 failure: TTFT 9727 ms against a 3000 ms contract.
        val violations = ContractChecker.violations(
            PerformanceContract(),
            WindowMeasurement(elapsedMs = 60_000, ttftMs = 9727, decodeTokPerSec = 7.6,
                visionFps = 8.3, rssBytes = 580L * 1024 * 1024),
        )
        assertEquals(1, violations.size, "only TTFT should have failed: $violations")
        assertEquals("ttftMs", violations.single().term)
        assertTrue(violations.single().summary.contains("VIOLATES"), violations.single().summary)
    }

    @Test
    fun `the same window passes once the fixes are in`() {
        // TTFT 1481 ms, decode 14.4 tok/s — the 6-thread warm configuration.
        val violations = ContractChecker.violations(
            PerformanceContract(),
            WindowMeasurement(elapsedMs = 60_000, ttftMs = 1481, decodeTokPerSec = 14.4,
                visionFps = 8.3, rssBytes = 580L * 1024 * 1024),
        )
        assertTrue(violations.isEmpty(), "got $violations")
    }

    @Test
    fun `the contract round-trips through JSON`() {
        val original = PerformanceContract(ttftMsMax = 2500, batteryPercentPerHourMax = 12.0)
        assertEquals(original, PerformanceContract.fromJson(original.toJson()))
    }
}

/**
 * Reproducibility, from two real self-benchmark runs on the same phone an hour apart.
 *
 * The runs agreed to within 2% at 1 and 2 threads and disagreed by 3x at 8. A cost model that
 * treats both the same publishes a number with a false air of precision, and a governor that
 * selects on it is choosing the luckiest sample of a noisy configuration.
 */
class ReproducibilityTest {

    /** Verbatim from the phone's second self-benchmark, 2026-08-02. */
    private val run2 = listOf(
        BenchmarkRun(threads = 1, promptTokens = 78, reusedTokens = 0, ttftMs = 9091, decodeTokPerSec = 6.6),
        BenchmarkRun(threads = 1, promptTokens = 77, reusedTokens = 30, ttftMs = 5637, decodeTokPerSec = 6.1),
        BenchmarkRun(threads = 2, promptTokens = 78, reusedTokens = 0, ttftMs = 4592, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 2, promptTokens = 77, reusedTokens = 30, ttftMs = 2780, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 6, promptTokens = 78, reusedTokens = 0, ttftMs = 2897, decodeTokPerSec = 8.9),
        BenchmarkRun(threads = 6, promptTokens = 77, reusedTokens = 30, ttftMs = 1505, decodeTokPerSec = 14.9),
        // The outlier: 6720 ms where the other run measured 2185 for the same work.
        BenchmarkRun(threads = 8, promptTokens = 78, reusedTokens = 0, ttftMs = 6720, decodeTokPerSec = 3.4),
        BenchmarkRun(threads = 8, promptTokens = 77, reusedTokens = 30, ttftMs = 1448, decodeTokPerSec = 1.6),
    )

    @Test
    fun `a configuration that cannot be measured twice the same way is marked unreliable`() {
        val model = CostModel.fit(run2)
        assertTrue(model.forThreads(1)!!.reliable, "1 thread agreed to within 3%")
        assertTrue(model.forThreads(2)!!.reliable, "2 threads agreed to within 1%")
        assertFalse(
            model.forThreads(8)!!.reliable,
            "8 threads disagreed by ${model.forThreads(8)!!.spreadRatio}x and must not be trusted",
        )
        assertTrue(model.forThreads(8)!!.spreadRatio > 2.0)
    }

    @Test
    fun `an unreliable configuration is never selected`() {
        val model = CostModel.fit(run2)
        val contract = PerformanceContract(ttftMsMax = 3000, decodeTokPerSecMin = 1.0)
        val chosen = model.cheapestMeeting(contract, promptTokens = 83, reusedTokens = 30)
        assertNotNull(chosen)
        assertTrue(chosen.reliable, "picked a configuration it could not measure twice")
        assertTrue(chosen.threads != 8, "8 threads is the unreproducible one")
    }

    @Test
    fun `the fastest fallback prefers a reproducible configuration`() {
        val model = CostModel.fit(run2)
        val fastest = model.fastest()
        assertNotNull(fastest)
        assertTrue(
            fastest.reliable,
            "the fastest unreliable entry is usually just its luckiest sample",
        )
    }

    @Test
    fun `a profile records why a configuration was excluded`() {
        val report = BenchmarkReport(
            topology = CpuTopology(8, listOf(CpuCluster((0..7).toList(), 1_500_000)), "fp asimd", null),
            runs = run2, costModel = CostModel.fit(run2), memory = null, thermal = null,
            durationMs = 60_000, budgetMs = 90_000, complete = true,
        )
        val profile = ProfileDeriver.derive(report, PerformanceContract(), typicalPromptTokens = 83,
            typicalReusedTokens = 30)
        val excluded = profile.reasons.single { it.knob == "threads:8" }
        assertEquals("excluded", excluded.value)
        assertTrue(excluded.because.contains("not"), excluded.because)
        assertTrue(excluded.because.contains("operating system"), excluded.because)
    }

    @Test
    fun `a single sample is not called unreliable for lack of a second`() {
        // One measurement cannot disagree with itself. Absence of evidence about spread is
        // not evidence of noise.
        val model = CostModel.fit(
            listOf(BenchmarkRun(threads = 4, promptTokens = 80, reusedTokens = 0, ttftMs = 3200)))
        assertTrue(model.forThreads(4)!!.reliable)
        assertEquals(1.0, model.forThreads(4)!!.spreadRatio, 1e-9)
    }
}
