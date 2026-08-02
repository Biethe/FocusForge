package dev.aarchmage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Profile derivation.
 *
 * The tests are mostly about **what the profile says about itself**. A configuration file is
 * easy; the requirement here is that every choice carries the measurement behind it, that a
 * preference is never dressed up as a result, and that a machine which cannot meet the
 * contract is told so rather than handed its best option in silence.
 */
class DeviceProfileTest {

    private val phone = CpuTopology(
        coreCount = 8,
        clusters = listOf(
            CpuCluster(listOf(6, 7), 1_560_000),
            CpuCluster((0..5).toList(), 1_352_000),
        ),
        features = "fp asimd aes sha1 sha2 crc32",
        totalRamBytes = 3L * 1024 * 1024 * 1024,
    )

    /** The operator's real A20e measurements. */
    private val a20eRuns = listOf(
        BenchmarkRun(threads = 2, promptTokens = 83, reusedTokens = 0,  ttftMs = 4913, decodeTokPerSec = 12.3),
        BenchmarkRun(threads = 2, promptTokens = 77, reusedTokens = 30, ttftMs = 2784, decodeTokPerSec = 12.4),
        BenchmarkRun(threads = 4, promptTokens = 83, reusedTokens = 0,  ttftMs = 3458, decodeTokPerSec = 14.0),
        BenchmarkRun(threads = 4, promptTokens = 77, reusedTokens = 30, ttftMs = 1940, decodeTokPerSec = 14.1),
        BenchmarkRun(threads = 6, promptTokens = 83, reusedTokens = 0,  ttftMs = 2659, decodeTokPerSec = 17.1),
        BenchmarkRun(threads = 6, promptTokens = 77, reusedTokens = 30, ttftMs = 1481, decodeTokPerSec = 14.4),
    )

    private fun report(
        runs: List<BenchmarkRun> = a20eRuns,
        memory: MemoryProbe? = MemoryProbe(
            rssBeforeLoadBytes = 133L * 1024 * 1024,
            rssAfterLoadBytes = 554L * 1024 * 1024,
            rssAfterGenerateBytes = 557L * 1024 * 1024,
            loadMs = 1900,
        ),
        thermal: ThermalProbe? = null,
        complete: Boolean = true,
        notes: List<String> = emptyList(),
    ) = BenchmarkReport(
        topology = phone,
        runs = runs,
        costModel = CostModel.fit(runs),
        memory = memory,
        thermal = thermal,
        durationMs = 42_000,
        budgetMs = 60_000,
        complete = complete,
        notes = notes,
    )

    @Test
    fun `a cold prompt needs six threads on this device, and the profile says why`() {
        val profile = ProfileDeriver.derive(
            report(), PerformanceContract(), typicalPromptTokens = 83, typicalReusedTokens = 0)

        assertEquals(6, profile.chosen.threads)
        assertTrue(profile.chosen.meetsContract)

        val why = profile.reasons.single { it.knob == "threads" }.because
        assertTrue(why.contains("3000"), "the limit should be named: $why")
        assertTrue(why.contains("cheapest"), why)
        assertTrue(Regex("\\d+ ms").containsMatchIn(why), "the prediction should be stated: $why")
    }

    @Test
    fun `a warm prompt is satisfied by two threads, and it takes them rather than six`() {
        val profile = ProfileDeriver.derive(
            report(), PerformanceContract(), typicalPromptTokens = 77, typicalReusedTokens = 30)
        assertEquals(2, profile.chosen.threads,
            "six cores would be faster, but the contract is already met — spare cores on a " +
                "phone are battery and heat")
        assertTrue(profile.chosen.meetsContract)
    }

    @Test
    fun `when nothing can comply it says so instead of quietly picking the fastest`() {
        val profile = ProfileDeriver.derive(
            report(), PerformanceContract(ttftMsMax = 500),
            typicalPromptTokens = 83, typicalReusedTokens = 0)

        assertEquals(6, profile.chosen.threads, "it still gets the best available option")
        assertFalse(profile.chosen.meetsContract, "but it must not claim the contract is met")
        val why = profile.reasons.single { it.knob == "threads" }.because
        assertTrue(why.contains("NO configuration"), why)
    }

    @Test
    fun `every choice carries a measurement, not an assertion`() {
        val profile = ProfileDeriver.derive(
            report(thermal = ThermalProbe(6, 12, 20_000, 16.5, 16.2, 1.8)),
            PerformanceContract(), typicalPromptTokens = 83)

        for (knob in listOf("threads", "affinity", "visionFpsBudget", "memory")) {
            val reason = profile.reasons.firstOrNull { it.knob == knob }
            assertNotNull(reason, "no reason recorded for $knob")
            assertTrue(reason.because.length > 30,
                "$knob's reason is too thin to be evidence: '${reason.because}'")
        }
    }

    @Test
    fun `an unmeasured knob is recorded as unmeasured rather than left blank`() {
        val profile = ProfileDeriver.derive(report(), PerformanceContract(), typicalPromptTokens = 83)
        assertNull(profile.chosen.affinity)
        val why = profile.reasons.single { it.knob == "affinity" }.because
        assertTrue(why.contains("not been implemented or measured"), why)
    }

    @Test
    fun `a preference between model files is never described as a measurement`() {
        val profile = ProfileDeriver.derive(
            report(), PerformanceContract(), typicalPromptTokens = 83,
            availableModels = listOf("smollm2-q4_k_m.gguf", "smollm2-q8_0.gguf"))

        val why = profile.reasons.single { it.knob == "modelFile" }.because
        assertTrue(why.contains("NOT a measured"), "a guess must announce itself: $why")
        assertTrue(why.contains("preference"), why)
    }

    @Test
    fun `a machine that slows under load keeps its vision budget at the floor`() {
        val hot = ProfileDeriver.derive(
            report(thermal = ThermalProbe(6, 12, 20_000, 17.0, 11.0, 35.0)),
            PerformanceContract(), typicalPromptTokens = 83)
        assertEquals(5.0, hot.chosen.visionFpsBudget, 0.01)
        assertTrue(hot.reasons.single { it.knob == "visionFpsBudget" }.because.contains("35%"))

        val cool = ProfileDeriver.derive(
            report(thermal = ThermalProbe(6, 12, 20_000, 16.5, 16.4, 0.6)),
            PerformanceContract(), typicalPromptTokens = 83)
        assertTrue(cool.chosen.visionFpsBudget > 5.0, "a cool machine may have headroom")
    }

    @Test
    fun `a provisional choice made without a sustain probe admits it is provisional`() {
        val profile = ProfileDeriver.derive(
            report(thermal = null), PerformanceContract(), typicalPromptTokens = 83)
        val why = profile.reasons.single { it.knob == "visionFpsBudget" }.because
        assertTrue(why.contains("provisional"), why)
        assertTrue(why.contains("no sustain probe"), why)
    }

    @Test
    fun `an incomplete benchmark is flagged in the profile it produced`() {
        val profile = ProfileDeriver.derive(
            report(complete = false, notes = listOf("stopped after 2 runs: out of budget")),
            PerformanceContract(), typicalPromptTokens = 83)
        val why = profile.reasons.single { it.knob == "benchmark" }.because
        assertTrue(why.contains("partial picture"), why)
        assertTrue(why.contains("stopped after 2 runs"), "the note must survive: $why")
    }

    @Test
    fun `an empty benchmark yields a default that says it is a default`() {
        val profile = ProfileDeriver.derive(
            report(runs = emptyList(), memory = null), PerformanceContract(),
            typicalPromptTokens = 83)
        assertEquals(1, profile.chosen.threads)
        assertFalse(profile.chosen.meetsContract)
        val why = profile.reasons.single { it.knob == "threads" }.because
        assertTrue(why.contains("not a choice"), why)
    }

    @Test
    fun `memory over budget is stated in the profile`() {
        val profile = ProfileDeriver.derive(
            report(memory = MemoryProbe(133L * 1024 * 1024, 900L * 1024 * 1024,
                910L * 1024 * 1024, 1900)),
            PerformanceContract(), typicalPromptTokens = 83)
        val memory = profile.reasons.single { it.knob == "memory" }
        assertEquals("OVER BUDGET", memory.value)
        assertTrue(memory.because.contains("910 MB"), memory.because)
    }

    @Test
    fun `the profile is self-contained and survives a round trip`() {
        val profile = ProfileDeriver.derive(
            report(thermal = ThermalProbe(6, 10, 20_000, 16.5, 16.0, 3.0)),
            PerformanceContract(), typicalPromptTokens = 83,
            device = mapOf("model" to "SM-A202F", "android" to "11"),
            nowEpochMs = 1_700_000_000_000)

        val restored = DeviceProfile.fromJson(profile.toJson())
        assertEquals(profile, restored)
        // The raw evidence travels with it, so the choices can be re-derived elsewhere.
        assertEquals(6, restored.evidence.runs.size)
        assertEquals(phone.clusters, restored.topology.clusters)
        assertNotNull(restored.evidence.costModel.forThreads(6))
    }

    @Test
    fun `re-deriving under a different contract changes the answer without new measurements`() {
        // The point of embedding the evidence: a stricter promise can be tested against the
        // same run rather than requiring the user to sit through another benchmark.
        val r = report()
        val lenient = ProfileDeriver.derive(r, PerformanceContract(ttftMsMax = 6000),
            typicalPromptTokens = 83)
        val strict = ProfileDeriver.derive(r, PerformanceContract(ttftMsMax = 3000),
            typicalPromptTokens = 83)
        assertEquals(2, lenient.chosen.threads)
        assertEquals(6, strict.chosen.threads)
    }
}
