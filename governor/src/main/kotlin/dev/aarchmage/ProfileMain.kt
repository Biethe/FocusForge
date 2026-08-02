@file:JvmName("ProfileMain")

package dev.aarchmage

import java.io.File

/**
 * Runs the self-benchmark on whatever machine this is and writes a device profile.
 *
 * This is the cross-silicon exhibit's entry point: the same discovery, the same benchmark,
 * the same cost model and the same derivation that run on the phone, executed on a CI
 * runner. Two profiles side by side, produced by one code path.
 *
 *     ./gradlew -PcoreOnly :governor:deriveProfile
 *     java -cp ... dev.aarchmage.ProfileMain --out bench/profiles/host.device.profile.json
 */
fun main(args: Array<String>) {
    val out = argValue(args, "--out") ?: "device.profile.json"
    // Re-derive a profile from measurements taken elsewhere — specifically the phone's, which
    // this machine cannot reproduce because it has no camera, no GGUF and no Exynos. Same
    // derivation code, real evidence, no re-measurement.
    argValue(args, "--from-results")?.let { path ->
        deriveFromCommittedResults(File(path), File(out), argValue(args, "--label") ?: "device")
        return
    }
    val budgetMs = argValue(args, "--budget-ms")?.toLongOrNull() ?: 60_000
    val label = argValue(args, "--label") ?: "host"

    val topology = CpuTopology.detect()
    val sensors = LinuxSensors()

    println("aarchmage — profiling this machine")
    println("  cores      ${topology.coreCount}")
    println("  clusters   ${topology.clusters.joinToString(" | ") { it.label }}")
    println("  features   ${topology.features?.take(120) ?: "unreadable"}")
    println("  sweep      ${topology.candidateThreadCounts()}")
    println("  workload   ${ReferenceBackend.WORKLOAD_NAME}")
    println()

    val report = SelfBenchmark(
        topology = topology,
        backend = ReferenceBackend(),
        sensors = sensors,
        plan = BenchmarkPlan(budgetMs = budgetMs),
    ).run { p ->
        println("  [${p.step}/${p.totalSteps}] ${p.label}  (${p.elapsedMs / 1000}s of ${p.budgetMs / 1000}s)")
    }

    println()
    println("  ${report.runs.size} runs in ${report.durationMs / 1000}s, complete=${report.complete}")
    report.notes.forEach { println("  note: $it") }
    for (cost in report.costModel.perThreadCount) {
        println("  %2d threads: %6.2f ms per unit  (%.1f units/s)  decode %.1f/s".format(
            cost.threads, cost.msPerFreshToken, cost.prefillTokPerSec, cost.decodeTokPerSec))
    }
    report.thermal?.let {
        println("  sustained load: ${it.samples} samples over ${it.durationMs / 1000}s, " +
            "throughput lost ${"%.1f".format(it.deratingPercent)}%")
    }

    // A prompt the size the application actually sends, so the thread choice is made for the
    // real workload rather than an imaginary one.
    val profile = ProfileDeriver.derive(
        report = report,
        contract = PerformanceContract(),
        typicalPromptTokens = TYPICAL_PROMPT_TOKENS,
        typicalReusedTokens = TYPICAL_REUSED_TOKENS,
        device = buildMap {
            put("label", label)
            put("os", System.getProperty("os.name") ?: "?")
            put("arch", System.getProperty("os.arch") ?: "?")
            put("java", System.getProperty("java.version") ?: "?")
            put("workload", ReferenceBackend.WORKLOAD_NAME)
            put("workload_note", ReferenceBackend.WORKLOAD_NOTE)
            topology.totalRamBytes?.let { put("total_ram_bytes", it.toString()) }
        },
        nowEpochMs = System.currentTimeMillis(),
    )

    File(out).apply { parentFile?.mkdirs() }.writeText(profile.toJson())
    println()
    println("  chosen: ${profile.chosen.threads} threads, vision budget " +
        "${"%.1f".format(profile.chosen.visionFpsBudget)} fps, meetsContract=${profile.chosen.meetsContract}")
    profile.reasons.forEach { println("    ${it.knob} = ${it.value}: ${it.because}") }
    println()
    println("written to $out")
}

/**
 * Builds a profile from a committed benchmark file rather than by running one.
 *
 * The A20e's numbers were produced by the operator on the physical device with the real
 * model. They cannot be reproduced on a build machine, and re-measuring them with the
 * reference workload would produce a different and less meaningful profile. So the same
 * [ProfileDeriver] is pointed at the committed evidence instead.
 */
private fun deriveFromCommittedResults(input: File, output: File, label: String) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val file = json.decodeFromString(CommittedResults.serializer(), input.readText())

    val runs = file.runs.map {
        BenchmarkRun(
            threads = it.threads,
            promptTokens = it.prompt_tokens,
            reusedTokens = it.reused_tokens,
            ttftMs = it.ttft_ms,
            decodeTokPerSec = it.decode_tok_per_s,
        )
    }
    val memory = file.memory_rss_mb?.let {
        MemoryProbe(
            rssBeforeLoadBytes = it.before_model_open * 1024L * 1024,
            rssAfterLoadBytes = it.after_model_load * 1024L * 1024,
            rssAfterGenerateBytes = it.after_generation * 1024L * 1024,
            loadMs = -1,
        )
    }
    val report = BenchmarkReport(
        topology = A20E_TOPOLOGY,
        runs = runs,
        costModel = CostModel.fit(runs),
        memory = memory,
        thermal = null,
        durationMs = -1,
        budgetMs = -1,
        complete = true,
        notes = listOf(
            "Derived from ${input.name} — measurements taken by the operator on the physical " +
                "device with the real model, not re-run here.",
            "No sustain probe: the committed benchmark did not include one.",
        ),
    )
    val profile = ProfileDeriver.derive(
        report = report,
        contract = PerformanceContract(),
        typicalPromptTokens = TYPICAL_PROMPT_TOKENS,
        typicalReusedTokens = TYPICAL_REUSED_TOKENS,
        device = mapOf(
            "label" to label,
            "model" to (file.device ?: "?"),
            "workload" to "llama.cpp + SmolLM2-360M-Instruct q8_0",
            "workload_note" to "Real language-model measurements on the physical device. " +
                "Milliseconds here are per PROMPT TOKEN and are directly meaningful.",
            "source" to input.name,
        ),
        nowEpochMs = System.currentTimeMillis(),
    )
    output.apply { parentFile?.mkdirs() }.writeText(profile.toJson())
    println("derived ${output.path} from ${input.name}: ${profile.chosen.threads} threads, " +
        "meetsContract=${profile.chosen.meetsContract}")
    profile.reasons.forEach { println("  ${it.knob} = ${it.value}: ${it.because}") }
}

@kotlinx.serialization.Serializable
private data class CommittedResults(
    val device: String? = null,
    val runs: List<CommittedRun>,
    val memory_rss_mb: CommittedMemory? = null,
)

@kotlinx.serialization.Serializable
private data class CommittedRun(
    val threads: Int,
    val prompt_tokens: Int,
    val reused_tokens: Int,
    val ttft_ms: Long,
    val decode_tok_per_s: Double? = null,
)

@kotlinx.serialization.Serializable
private data class CommittedMemory(
    val before_model_open: Long,
    val after_model_load: Long,
    val after_generation: Long,
)

/** The A20e as CLAUDE.md §2 describes it and the Phase 1 probe confirmed. */
private val A20E_TOPOLOGY = CpuTopology(
    coreCount = 8,
    clusters = listOf(
        CpuCluster(listOf(6, 7), 1_560_000),
        CpuCluster((0..5).toList(), 1_352_000),
    ),
    features = "fp asimd aes pmull sha1 sha2 crc32",
    totalRamBytes = 2_809_856L * 1024,
)

/** The coach's real prompt size on the phone, measured: 83 tokens cold, 30 of them reusable. */
private const val TYPICAL_PROMPT_TOKENS = 83
private const val TYPICAL_REUSED_TOKENS = 30

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
