package dev.aarchmage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The silicon lockfile: what this machine is, what was measured on it, what was chosen as a
 * result, and **why**.
 *
 * The reasons are not decoration. A profile that says "threads: 6" is a configuration file;
 * a profile that says "threads: 6 because 2 threads was predicted at 4913 ms against a
 * 3000 ms contract" is evidence. The second can be disputed by anyone who reads it, which is
 * the only property that makes an automated choice trustworthy.
 *
 * The raw benchmark is embedded rather than referenced, so a profile pulled off a phone is
 * self-contained and can be re-derived, argued with, or replayed against a different contract
 * long after the run.
 */
@Serializable
data class DeviceProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Free-form identity from the host: model name, OS version, app build. */
    val device: Map<String, String> = emptyMap(),
    val generatedAtEpochMs: Long = 0,

    val topology: CpuTopology,
    /** The contract the choices were made against. Re-deriving under another one is fair game. */
    val contract: PerformanceContract,

    val chosen: ChosenConfig,
    /** Every choice, with the measurement behind it. */
    val reasons: List<ChoiceReason>,

    /** The raw evidence, embedded so the profile stands alone. */
    val evidence: BenchmarkReport,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): DeviceProfile = JSON.decodeFromString(serializer(), text)
    }
}

/** What the runtime should actually do on this machine. */
@Serializable
data class ChosenConfig(
    val threads: Int,
    val nCtx: Int,
    /** Null means the scheduler places threads. Never measured on the phone so far. */
    val affinity: String? = null,
    /** Frames per second the vision loop may use. The governor's one actuated knob. */
    val visionFpsBudget: Double,
    /**
     * Which model file to use, chosen from what is present on the device.
     *
     * Null when only one was available, or when nothing was measured well enough to choose —
     * in which case the host keeps whatever it already had rather than being told a
     * preference nobody established.
     */
    val modelFile: String? = null,
    /**
     * False when no benchmarked configuration was predicted to satisfy the contract. The
     * runtime still gets its best option, but it is told that its best is not good enough.
     */
    val meetsContract: Boolean = true,
)

/** One decision and the number that drove it. */
@Serializable
data class ChoiceReason(
    val knob: String,
    val value: String,
    val because: String,
)

/**
 * Turns a benchmark report into a configuration.
 *
 * The rules are deliberately conservative and are all visible here rather than spread across
 * the codebase:
 *
 * - **Cheapest that complies, not fastest.** Spare cores on a phone are battery and heat.
 * - **A prediction is preferred to an extrapolation.** Only benchmarked thread counts are
 *   considered; nothing is chosen that was never run.
 * - **A failure to comply is reported, not hidden.** If nothing meets the contract, the
 *   fastest option is chosen and `meetsContract` is false, with a reason saying so.
 */
object ProfileDeriver {

    /**
     * @param typicalPromptTokens the size of prompt the application actually sends. Choosing
     *        a thread count without knowing this would be choosing for an imaginary workload.
     * @param typicalReusedTokens how much of that prompt is normally already cached.
     */
    fun derive(
        report: BenchmarkReport,
        contract: PerformanceContract,
        typicalPromptTokens: Int,
        typicalReusedTokens: Int = 0,
        device: Map<String, String> = emptyMap(),
        nowEpochMs: Long = 0,
        availableModels: List<String> = emptyList(),
    ): DeviceProfile {
        val reasons = mutableListOf<ChoiceReason>()

        // --- threads ---------------------------------------------------------
        val compliant = report.costModel.cheapestMeeting(
            contract, typicalPromptTokens, typicalReusedTokens)
        val fallback = report.costModel.fastest()
        val chosenCost = compliant ?: fallback
        val meets = compliant != null

        val threads = chosenCost?.threads ?: 1
        if (chosenCost == null) {
            reasons += ChoiceReason("threads", "1",
                "nothing was measured successfully, so this is a default and not a choice — " +
                    "the benchmark produced ${report.runs.size} usable runs")
        } else {
            val predicted = chosenCost.predictTtftMs(typicalPromptTokens, typicalReusedTokens)
            reasons += if (meets) {
                ChoiceReason("threads", "$threads",
                    "cheapest configuration predicted to satisfy the contract: " +
                        "${predicted.toInt()} ms for a $typicalPromptTokens-token prompt " +
                        "($typicalReusedTokens cached) against a limit of ${contract.ttftMsMax} ms; " +
                        "prefill measured at ${"%.1f".format(chosenCost.prefillTokPerSec)} tok/s. " +
                        "NOTE: measured with the application's other workloads idle, so live " +
                        "latency will be higher — on the A20e a session with the camera " +
                        "running came in 1.66x above this prediction (2026-08-02)")
            } else {
                ChoiceReason("threads", "$threads",
                    "NO configuration was predicted to satisfy the contract. This is the " +
                        "fastest of ${report.costModel.perThreadCount.size} measured: " +
                        "${predicted.toInt()} ms against a limit of ${contract.ttftMsMax} ms")
            }
        }

        report.costModel.perThreadCount.filterNot { it.reliable }.forEach { cost ->
            reasons += ChoiceReason("threads:${cost.threads}", "excluded",
                "measurements at ${cost.threads} threads disagreed by " +
                    "${"%.1f".format(cost.spreadRatio)}x, so this configuration is not " +
                    "reproducible and was not considered. On a phone this usually means the " +
                    "thread count leaves no cores for the operating system's own work")
        }

        // --- affinity --------------------------------------------------------
        reasons += ChoiceReason("affinity", "none",
            "thread pinning has not been implemented or measured; the scheduler places " +
                "threads. Recorded as an explicit 'not measured' rather than an empty field")

        // --- vision fps ------------------------------------------------------
        // The knob the governor actually turns. It starts at the contract's floor rather
        // than at whatever the camera can manage: the floor is what the signals need, and
        // anything above it is spent for no measured benefit.
        val fpsFloor = contract.visionFpsMin ?: 5.0
        val visionFps = if (report.thermal != null && report.thermal.deratingPercent > 10.0) {
            reasons += ChoiceReason("visionFpsBudget", "%.1f".format(fpsFloor),
                "held at the contract floor: sustained load slowed this machine by " +
                    "${"%.0f".format(report.thermal.deratingPercent)}%, so headroom is not free")
            fpsFloor
        } else {
            val budget = fpsFloor * 1.6
            reasons += ChoiceReason("visionFpsBudget", "%.1f".format(budget),
                if (report.thermal == null) {
                    "contract floor ${fpsFloor} plus headroom; no sustain probe ran, so this " +
                        "is provisional and the governor will lower it if the contract slips"
                } else {
                    "contract floor ${fpsFloor} plus headroom; sustained load cost only " +
                        "${"%.0f".format(report.thermal.deratingPercent)}% throughput"
                })
            budget
        }

        // --- model file ------------------------------------------------------
        val model = when {
            availableModels.size <= 1 -> {
                reasons += ChoiceReason("modelFile", availableModels.firstOrNull() ?: "unchanged",
                    if (availableModels.isEmpty()) "no model files were listed, so the host keeps what it has"
                    else "only one model file is present, so there was nothing to choose between")
                availableModels.firstOrNull()
            }
            else -> {
                // Still a preference, and now one with a warning attached. Measured on the
                // A20e (docs/RESULTS.md §1): Q4_K_M is 19-35% SLOWER than q8_0 despite being
                // 115 MB smaller, because unpacking a k-quant costs arithmetic that a CPU
                // with dotprod would hide behind its matrix multiply — and Armv8.0 has none.
                // Smaller is cheaper in memory and load time, not necessarily in latency.
                val pick = availableModels.minByOrNull { it.length }
                reasons += ChoiceReason("modelFile", pick ?: "unchanged",
                    "chosen from ${availableModels.size} available files by NAME LENGTH, which " +
                        "is a proxy for a smaller quantisation and NOT a measurement. Be " +
                        "careful with the assumption behind it: on Armv8.0 hardware a smaller " +
                        "k-quant measured 19-35% slower than q8_0, because unpacking it costs " +
                        "arithmetic that dotprod would otherwise hide. Smaller reliably buys " +
                        "memory and load time; it does not reliably buy speed")
                pick
            }
        }

        // --- memory ----------------------------------------------------------
        report.memory?.let { m ->
            val within = m.withinBudget(contract.rssBytesMax)
            reasons += ChoiceReason("memory", if (within) "within budget" else "OVER BUDGET",
                "peak RSS ${m.peakBytes() / (1024 * 1024)} MB against a limit of " +
                    "${contract.rssBytesMax?.div(1024 * 1024) ?: -1} MB; the model itself " +
                    "cost ${m.modelCostBytes / (1024 * 1024)} MB and took ${m.loadMs} ms to load")
        }

        if (!report.complete) {
            reasons += ChoiceReason("benchmark", "incomplete",
                "the sweep did not finish inside its ${report.budgetMs / 1000} s budget, so " +
                    "these choices were made from a partial picture: ${report.notes.joinToString("; ")}")
        }

        return DeviceProfile(
            device = device,
            generatedAtEpochMs = nowEpochMs,
            topology = report.topology,
            contract = contract,
            chosen = ChosenConfig(
                threads = threads,
                nCtx = 512,
                affinity = null,
                visionFpsBudget = visionFps,
                modelFile = model,
                meetsContract = meets,
            ),
            reasons = reasons,
            evidence = report,
        )
    }
}
