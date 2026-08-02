package dev.aarchmage

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the language model, for machines that do not have one.
 *
 * **This is not an LLM and the profiles it produces say so.** It is a deterministic,
 * CPU-bound kernel shaped like the work that dominates prompt processing: quantised
 * dot products with a floating-point accumulator, run across a chosen number of threads.
 *
 * It exists because the cross-silicon exhibit needs the *same discovery, cost model and
 * derivation* to run on a CI machine, and a CI job cannot sensibly fetch a 270 MB model and
 * complete a 60-second benchmark. What transfers between the two machines is the **shape**
 * of the answer — how throughput scales with threads, where the knee is, whether the machine
 * derates under sustained load. What does not transfer is milliseconds per *token*, and a
 * profile derived from this workload records that in as many words.
 *
 * Deterministic on purpose: the same inputs give the same arithmetic on any machine, so a
 * difference between two profiles is a difference between two CPUs and not between two
 * random number streams.
 */
object ReferenceWorkload {

    /**
     * Multiply-accumulates in one unit of work.
     *
     * Sized so a single unit takes milliseconds rather than microseconds even on a fast
     * desktop. The first version used 262 144 and a 16-thread run finished inside the clock's
     * resolution, which made least squares fit a **negative** cost per unit — an impossible
     * number produced entirely by timing noise. A benchmark whose unit is smaller than its
     * clock measures the clock.
     */
    const val MACS_PER_UNIT = 8_388_608

    private const val VECTOR = 1024
    private val weights: ByteArray = ByteArray(VECTOR * 64) { i ->
        // A fixed pattern rather than random: reproducible across machines and runs.
        ((i * 31 + 7) % 251 - 125).toByte()
    }
    private val activations: ByteArray = ByteArray(VECTOR) { i ->
        ((i * 17 + 3) % 251 - 125).toByte()
    }

    /**
     * Does [units] units of work spread over [threads] threads and returns a checksum.
     *
     * The checksum is returned and used so that nothing can be optimised away — a benchmark
     * whose result is discarded is a benchmark the JIT is entitled to delete.
     */
    fun run(units: Int, threads: Int, pool: ExecutorService? = null): Long {
        if (units <= 0) return 0
        // A pool created per call would put its own construction cost inside the
        // measurement, which at these durations is not negligible.
        val executor = pool ?: Executors.newFixedThreadPool(threads)
        try {
            val perThread = (units + threads - 1) / threads
            val futures = (0 until threads).map { t ->
                executor.submit<Long> {
                    val start = t * perThread
                    val end = minOf(units, start + perThread)
                    var acc = 0L
                    for (unit in start until end) acc += oneUnit(unit)
                    acc
                }
            }
            return futures.sumOf { it.get(10, TimeUnit.MINUTES) }
        } finally {
            if (pool == null) executor.shutdownNow()
        }
    }

    private fun oneUnit(seed: Int): Long {
        val rows = MACS_PER_UNIT / VECTOR
        var total = 0L
        for (row in 0 until rows) {
            val offset = ((seed + row) % 64) * VECTOR
            var sum = 0
            for (i in 0 until VECTOR) {
                sum += weights[offset + i] * activations[i]
            }
            total += sum
        }
        return total
    }
}

/**
 * An [InferenceBackend] over [ReferenceWorkload], so the CI machine can run the *same*
 * self-benchmark, cost model and profile derivation as the phone.
 *
 * "Prompt tokens" here are units of reference work. Every profile derived from this carries
 * `workload: reference-cpu-kernel` so that no reader can mistake its milliseconds for the
 * language model's.
 */
class ReferenceBackend(
    /** Simulates the cost of opening a model, so load time appears in the profile at all. */
    private val loadMs: Long = 0,
) : InferenceBackend {

    override var lastError: String = ""

    override fun open(config: RunConfig): OpenModel? {
        if (config.threads <= 0) {
            lastError = "thread count must be positive"
            return null
        }
        val t0 = System.currentTimeMillis()
        if (loadMs > 0) Thread.sleep(loadMs)
        val actualLoad = System.currentTimeMillis() - t0
        val rssAtOpen = LinuxSensors().rssBytes()

        // One pool for the life of the "model", exactly as a real backend keeps its threads.
        val pool = Executors.newFixedThreadPool(config.threads)

        return object : OpenModel {
            override val loadMs = actualLoad
            override val rssAfterLoadBytes = rssAtOpen
            private var cachedPrefix = 0

            override fun generate(prompt: String, maxTokens: Int): GenerationResult {
                // Prompt length in "tokens" mirrors the 4-chars-per-token rule used elsewhere.
                val promptUnits = (prompt.length / 4).coerceAtLeast(1)
                // Reuse works the same way as the real backend: the shared opening of two
                // prompts is not recomputed.
                val reused = minOf(cachedPrefix, promptUnits - 1).coerceAtLeast(0)
                cachedPrefix = promptUnits
                val fresh = promptUnits - reused

                val t1 = System.nanoTime()
                ReferenceWorkload.run(fresh, config.threads, pool)
                val ttft = (System.nanoTime() - t1) / 1_000_000

                val t2 = System.nanoTime()
                ReferenceWorkload.run(maxTokens, config.threads, pool)
                val decode = (System.nanoTime() - t2) / 1_000_000

                return GenerationResult(
                    ttftMs = ttft,
                    decodeMs = decode,
                    tokens = maxTokens,
                    promptTokens = promptUnits,
                    reusedTokens = reused,
                    rssBytes = LinuxSensors().rssBytes(),
                    text = "",
                )
            }

            override fun close() { pool.shutdownNow() }
        }
    }

    companion object {
        /** Recorded in every profile derived this way, so the substitution is never implicit. */
        const val WORKLOAD_NAME = "reference-cpu-kernel"
        const val WORKLOAD_NOTE =
            "Measured with a deterministic quantised-dot-product kernel, NOT a language " +
                "model. Thread scaling and sustained-load behaviour are comparable across " +
                "machines; milliseconds per unit are NOT comparable to the phone's " +
                "milliseconds per token."
    }
}
