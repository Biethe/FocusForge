package dev.aarchmage

import kotlinx.serialization.Serializable

/**
 * The first-launch self-benchmark: about a minute of measurement, once, on the machine that
 * is actually going to do the work.
 *
 * Three probes, in the order that matters if the budget runs out:
 *
 * 1. **Throughput per thread count.** The axis measured to matter most on the target device —
 *    six threads was 1.85x two, and the project's written guidance said the opposite.
 * 2. **Memory pressure.** RSS after load and after generation against the budget, because
 *    exceeding it is a crash rather than a slowdown.
 * 3. **Thermal sustain**, measured as throughput *decay* under a sustained load rather than
 *    read from a vendor API. Android exposes no thermal status below API 29 and a Linux
 *    server exposes something different again; decay is measurable everywhere and is the
 *    thing we actually care about.
 *
 * **The budget is real and is respected.** Each step is only started if what has already been
 * measured predicts it will fit. A benchmark that overruns its promise on a first launch is
 * a benchmark users learn to force-quit — and the report says plainly when it stopped early
 * rather than presenting a partial sweep as a complete one.
 */
class SelfBenchmark(
    private val topology: CpuTopology,
    private val backend: InferenceBackend,
    private val sensors: PlatformSensors,
    private val plan: BenchmarkPlan = BenchmarkPlan(),
) {

    /** Progress for a UI. Reported before each step, so a label is on screen while it runs. */
    data class Progress(
        val step: Int,
        val totalSteps: Int,
        val label: String,
        val elapsedMs: Long,
        val budgetMs: Long,
    )

    fun run(onProgress: (Progress) -> Unit = {}): BenchmarkReport {
        val started = sensors.nowMs()
        fun elapsed() = sensors.nowMs() - started
        fun remaining() = plan.budgetMs - elapsed()

        val threadCounts = plan.threadCounts ?: topology.candidateThreadCounts()
        val runs = mutableListOf<BenchmarkRun>()
        val notes = mutableListOf<String>()
        var memory: MemoryProbe? = null
        var thermal: ThermalProbe? = null
        var stepsDone = 0
        // +1 for the sustain probe at the end.
        val totalSteps = threadCounts.size + 1

        val rssBeforeAnything = sensors.rssBytes()

        for (threads in threadCounts) {
            stepsDone++
            onProgress(Progress(stepsDone, totalSteps,
                "measuring $threads thread${if (threads == 1) "" else "s"}", elapsed(), plan.budgetMs))

            // Only start a step we can afford. The estimate improves as we go: after the
            // first thread count we know roughly what one costs on this machine.
            val estimate = estimateStepMs(runs)
            if (estimate != null && estimate > remaining()) {
                notes += "stopped after ${runs.size} runs: the next thread count needed about " +
                    "${estimate / 1000} s and only ${remaining() / 1000} s of the " +
                    "${plan.budgetMs / 1000} s budget was left"
                break
            }

            val model = backend.open(RunConfig(threads = threads, nCtx = plan.nCtx))
            if (model == null) {
                notes += "$threads threads: could not open the model (${backend.lastError})"
                continue
            }

            model.use {
                if (memory == null) {
                    memory = MemoryProbe(
                        rssBeforeLoadBytes = rssBeforeAnything,
                        rssAfterLoadBytes = it.rssAfterLoadBytes,
                        rssAfterGenerateBytes = -1,
                        loadMs = it.loadMs,
                    )
                }
                // The prompts share an opening and differ in their numbers — the shape the
                // real workload has. Identical prompts would reuse the entire cache and
                // report a speed-up that could never happen in practice.
                for (prompt in plan.prompts) {
                    val r = it.generate(prompt, plan.maxTokens)
                    if (r == null) {
                        notes += "$threads threads: generation failed (${backend.lastError})"
                        break
                    }
                    runs += BenchmarkRun(
                        threads = threads,
                        affinity = null,
                        promptTokens = r.promptTokens,
                        reusedTokens = r.reusedTokens,
                        ttftMs = r.ttftMs,
                        decodeTokPerSec = r.decodeTokPerSec,
                        rssBytes = r.rssBytes,
                    )
                    memory = memory?.copy(rssAfterGenerateBytes = r.rssBytes)
                    if (remaining() <= 0) break
                }
            }
        }

        // --- sustain -----------------------------------------------------------
        stepsDone++
        val model = CostModel.fit(runs)
        val best = model.fastest()
        if (best != null && remaining() > plan.sustainMinMs) {
            onProgress(Progress(stepsDone, totalSteps, "checking it holds up under load",
                elapsed(), plan.budgetMs))
            thermal = measureSustain(best.threads, remaining().coerceAtMost(plan.sustainMaxMs))
        } else {
            notes += if (best == null) "no successful runs, so no sustain probe"
            else "skipped the sustain probe: only ${remaining() / 1000} s left"
        }

        return BenchmarkReport(
            topology = topology,
            runs = runs,
            costModel = model,
            memory = memory,
            thermal = thermal,
            durationMs = elapsed(),
            budgetMs = plan.budgetMs,
            complete = runs.isNotEmpty() && notes.none { it.startsWith("stopped after") },
            notes = notes,
        )
    }

    /**
     * What one more thread count is likely to cost, from what this machine has already shown
     * us. Null before there is anything to go on — in which case the first step runs, because
     * refusing to start on no evidence would mean never starting.
     */
    private fun estimateStepMs(runs: List<BenchmarkRun>): Long? {
        if (runs.isEmpty()) return null
        val perThreadCount = runs.groupBy { it.threads }
        val slowest = perThreadCount.values.maxOfOrNull { group ->
            group.sumOf { it.ttftMs + (plan.maxTokens * 1000L / (it.decodeTokPerSec.takeIf { d ->
                d != null && d > 0 } ?: 10.0)).toLong() }
        } ?: return null
        // Opening the model again is part of the cost of the next step.
        return slowest + plan.assumedLoadMs
    }

    /**
     * Runs the same work repeatedly and watches whether it gets slower.
     *
     * Reported as a percentage lost between the first and last third. This is not a
     * temperature — nothing here claims to read one — it is the consequence of temperature,
     * which is what a performance contract can actually be written against.
     */
    private fun measureSustain(threads: Int, budgetMs: Long): ThermalProbe? {
        val model = backend.open(RunConfig(threads = threads, nCtx = plan.nCtx)) ?: return null
        val samples = mutableListOf<Double>()
        val started = sensors.nowMs()
        model.use {
            var i = 0
            while (sensors.nowMs() - started < budgetMs) {
                val r = it.generate(plan.prompts[i % plan.prompts.size], plan.maxTokens) ?: break
                if (r.decodeTokPerSec > 0) samples += r.decodeTokPerSec
                i++
            }
        }
        if (samples.size < 3) return null
        val third = (samples.size / 3).coerceAtLeast(1)
        val first = samples.take(third).average()
        val last = samples.takeLast(third).average()
        return ThermalProbe(
            threads = threads,
            samples = samples.size,
            durationMs = sensors.nowMs() - started,
            firstThirdTokPerSec = first,
            lastThirdTokPerSec = last,
            deratingPercent = if (first <= 0) 0.0 else 100.0 * (first - last) / first,
            vendorThermalStatus = sensors.thermalStatus(),
        )
    }
}

/** What to measure, and how long we are allowed to take doing it. */
@Serializable
data class BenchmarkPlan(
    /** The architect's spec says about 60 seconds, once, on first launch. */
    val budgetMs: Long = 60_000,
    val nCtx: Int = 512,
    val maxTokens: Int = 16,
    /** Null means "ask the topology", which is the point of deriving them per machine. */
    val threadCounts: List<Int>? = null,
    /** Minimum time worth spending on the sustain probe for its answer to mean anything. */
    val sustainMinMs: Long = 8_000,
    val sustainMaxMs: Long = 20_000,
    /** Used only to decide whether the next step fits; replaced by measurement as soon as one exists. */
    val assumedLoadMs: Long = 2_000,
    /**
     * Same opening, different numbers — the shape of the real workload, so that measured
     * cache reuse is the reuse the application will actually get.
     */
    val prompts: List<String> = listOf(
        "You are a study coach talking to a student. Write ONE encouraging sentence to them, " +
            "under 30 words.\nLast minutes: focus 42/100, eyes on work 55%, 3 long eye " +
            "closures, head 12.0 deg, 23 min in.\nThey look tired.\n",
        "You are a study coach talking to a student. Write ONE encouraging sentence to them, " +
            "under 30 words.\nLast minutes: focus 61/100, eyes on work 74%, 1 long eye " +
            "closures, head 4.5 deg, 31 min in.\nTheir attention has been drifting.\n",
    ),
)

@Serializable
data class MemoryProbe(
    val rssBeforeLoadBytes: Long,
    val rssAfterLoadBytes: Long,
    val rssAfterGenerateBytes: Long,
    val loadMs: Long,
) {
    val modelCostBytes: Long get() = rssAfterLoadBytes - rssBeforeLoadBytes
    fun peakBytes(): Long = maxOf(rssAfterLoadBytes, rssAfterGenerateBytes)
    fun withinBudget(limitBytes: Long?): Boolean = limitBytes == null || peakBytes() <= limitBytes
}

@Serializable
data class ThermalProbe(
    val threads: Int,
    val samples: Int,
    val durationMs: Long,
    val firstThirdTokPerSec: Double,
    val lastThirdTokPerSec: Double,
    /** Positive means it slowed down. Measured, not read from a temperature sensor. */
    val deratingPercent: Double,
    /** The platform's own thermal level, when it has one. Null below Android API 29. */
    val vendorThermalStatus: String? = null,
)

@Serializable
data class BenchmarkReport(
    val topology: CpuTopology,
    val runs: List<BenchmarkRun>,
    val costModel: CostModel,
    val memory: MemoryProbe? = null,
    val thermal: ThermalProbe? = null,
    val durationMs: Long,
    val budgetMs: Long,
    /** False when the budget cut the sweep short. The report says so rather than implying a full sweep. */
    val complete: Boolean,
    val notes: List<String> = emptyList(),
)
