package dev.aarchmage

import kotlinx.serialization.Serializable

/**
 * How long this machine takes to answer, as a function of the work it is given.
 *
 * This is the difference between a governor that **explores** and one that **decides**. With
 * a cost model, "would 4 threads meet the contract?" is arithmetic; without one it is an
 * experiment the user has to sit through.
 *
 * The form is not invented. It was derived from measurements on the operator's A20e
 * (`bench/results/a20e-threads-kvcache-20260802.json`) and validated in the strongest way
 * available: the constant was fitted on the *cold* run of each thread count only, then used
 * to predict warm runs it had never seen. Worst error across six predictions was **1.7%**.
 *
 * ```
 * ttftMs = (promptTokens - reusedTokens) * msPerFreshToken(threads)
 * ```
 *
 * Time to first token on that device is essentially all prompt processing: the intercept
 * fitted to near zero, and generation speed does not enter it at all. On a machine where
 * that is not true, [intercept] carries the difference and the same code still applies.
 */
@Serializable
data class ThreadCost(
    val threads: Int,
    /** Milliseconds per prompt token that actually has to be processed. */
    val msPerFreshToken: Double,
    /** Fixed overhead per generation. Near zero on the A20e; measured, not assumed. */
    val interceptMs: Double = 0.0,
    /** Steady-state generation speed at this thread count. */
    val decodeTokPerSec: Double,
    /**
     * Ratio between the most and least expensive measurement at this thread count.
     *
     * 1.0 means every sample agreed. Anything much above that means the configuration is not
     * measurable consistently, and a cost fitted through it is a number with a false air of
     * precision.
     */
    val spreadRatio: Double = 1.0,
    /**
     * False when the samples disagreed too much to be trusted.
     *
     * Measured on the A20e (2026-08-02): two self-benchmark runs an hour apart agreed to
     * within 2% at 1 and 2 threads, and disagreed by **3x at 8** — one 78-token prompt took
     * 6720 ms where the others took 2185. Eight threads is every core on that phone, leaving
     * none for Android's own camera and interface work, so the benchmark ends up competing
     * with the device it is measuring. A configuration whose cost cannot be reproduced must
     * not be selected on the strength of its best sample.
     */
    val reliable: Boolean = true,
) {
    /** Predicted time to first token for a prompt of which [reusedTokens] are already cached. */
    fun predictTtftMs(promptTokens: Int, reusedTokens: Int = 0): Double {
        val fresh = (promptTokens - reusedTokens).coerceAtLeast(1)
        return interceptMs + fresh * msPerFreshToken
    }

    val prefillTokPerSec: Double
        get() = if (msPerFreshToken <= 0.0) 0.0 else 1000.0 / msPerFreshToken
}

@Serializable
data class CostModel(
    /** One entry per benchmarked thread count, ascending. */
    val perThreadCount: List<ThreadCost>,
) {
    fun forThreads(threads: Int): ThreadCost? = perThreadCount.firstOrNull { it.threads == threads }

    /**
     * The cheapest configuration that is predicted to satisfy the contract.
     *
     * "Cheapest" means fewest threads, not fastest: on a phone, spare cores are battery and
     * heat. A governor that always picks the fastest option is not tuning, it is just
     * maximising — and Phase 6 exists because those are different things.
     *
     * Returns null when no benchmarked configuration is predicted to comply, which is a real
     * answer and must be reported rather than papered over with the best of a bad set.
     */
    fun cheapestMeeting(
        contract: PerformanceContract,
        promptTokens: Int,
        reusedTokens: Int = 0,
    ): ThreadCost? = perThreadCount
        .filter { it.reliable }
        .sortedBy { it.threads }
        .firstOrNull { cost ->
            val ttftOk = contract.ttftMsMax?.let {
                cost.predictTtftMs(promptTokens, reusedTokens) <= it
            } ?: true
            val decodeOk = contract.decodeTokPerSecMin?.let { cost.decodeTokPerSec >= it } ?: true
            ttftOk && decodeOk
        }

    /**
     * The fastest configuration measured, for when nothing meets the contract.
     *
     * Prefers a reproducible one: the fastest *unreliable* entry is usually just the luckiest
     * sample of a noisy configuration.
     */
    fun fastest(): ThreadCost? =
        perThreadCount.filter { it.reliable }.minByOrNull { it.msPerFreshToken }
            ?: perThreadCount.minByOrNull { it.msPerFreshToken }

    companion object {
        /**
         * Fits the model from benchmark runs.
         *
         * With two or more distinct fresh-token counts at a thread count, both slope and
         * intercept are fitted by least squares. With only one, the intercept is **held at
         * zero** rather than invented — one point cannot determine two parameters, and
         * pretending otherwise would produce a model that predicts confidently and wrongly.
         */
        fun fit(runs: List<BenchmarkRun>): CostModel {
            val perThread = runs.groupBy { it.threads }.mapNotNull { (threads, group) ->
                val points = group.mapNotNull { r ->
                    val fresh = r.promptTokens - r.reusedTokens
                    if (fresh > 0 && r.ttftMs > 0) fresh.toDouble() to r.ttftMs.toDouble() else null
                }
                if (points.isEmpty()) return@mapNotNull null

                val distinctX = points.map { it.first }.distinct().size
                val fitted = if (distinctX >= 2) leastSquares(points) else null
                // A negative or zero cost per token is not a measurement — it is noise that
                // happened to fit. Seen for real on a 16-core machine where the work finished
                // inside the clock's resolution. Fall back to the plain mean ratio, which
                // cannot go negative, rather than publishing an impossible constant.
                val (slope, intercept) = when {
                    fitted != null && fitted.first > 0.0 -> fitted
                    else -> points.map { it.second / it.first }.average() to 0.0
                }
                val decode = group.mapNotNull { it.decodeTokPerSec }.takeIf { it.isNotEmpty() }
                    ?.average() ?: 0.0

                // Per-sample cost, used to ask whether this configuration is reproducible at
                // all before its fitted constant is trusted.
                val perSample = points.map { it.second / it.first }
                val spread = if (perSample.min() > 0) perSample.max() / perSample.min() else 1.0

                ThreadCost(
                    threads = threads,
                    msPerFreshToken = slope,
                    interceptMs = intercept,
                    decodeTokPerSec = decode,
                    spreadRatio = spread,
                    reliable = perSample.size < 2 || spread <= MAX_RELIABLE_SPREAD,
                )
            }
            return CostModel(perThread.sortedBy { it.threads })
        }

        /**
         * How far two measurements of the same configuration may disagree and still be
         * believed. 1.5 admits ordinary jitter and rejects the 3x swing seen at 8 threads.
         */
        const val MAX_RELIABLE_SPREAD = 1.5

        private fun leastSquares(points: List<Pair<Double, Double>>): Pair<Double, Double> {
            val n = points.size
            val sx = points.sumOf { it.first }
            val sy = points.sumOf { it.second }
            val sxx = points.sumOf { it.first * it.first }
            val sxy = points.sumOf { it.first * it.second }
            val denom = n * sxx - sx * sx
            if (denom == 0.0) return (sy / sx) to 0.0
            val slope = (n * sxy - sx * sy) / denom
            val intercept = (sy - slope * sx) / n
            return slope to intercept
        }
    }
}

/** One generation performed by the self-benchmark. Raw evidence; never edited after the fact. */
@Serializable
data class BenchmarkRun(
    val threads: Int,
    /** Cluster label the threads were pinned to, or null when the scheduler chose freely. */
    val affinity: String? = null,
    val promptTokens: Int,
    val reusedTokens: Int,
    val ttftMs: Long,
    val decodeTokPerSec: Double? = null,
    val rssBytes: Long? = null,
)
