package dev.aarchmage

import java.io.File
import kotlinx.serialization.Serializable

/**
 * What this machine's CPU actually is, read from the kernel rather than assumed.
 *
 * Deliberately reads `/sys` and `/proc` and nothing else, so the identical code runs on an
 * Android phone and on a Linux CI runner. That is the whole basis of the cross-silicon
 * exhibit: two very different Arm machines, one discovery path, two derived profiles.
 *
 * Nothing here is guessed. A file that cannot be read is reported as unknown, never
 * defaulted to a plausible-looking value.
 */
@Serializable
data class CpuCluster(
    /** Core ids that share a maximum frequency, ascending. */
    val cores: List<Int>,
    val maxFreqKhz: Long?,
) {
    val size: Int get() = cores.size

    /** A stable name for logs and profiles: "cpu0-5@1352MHz". */
    val label: String
        get() {
            val range = if (cores.size == 1) "cpu${cores.first()}"
            else "cpu${cores.first()}-${cores.last()}"
            return if (maxFreqKhz == null) range else "$range@${maxFreqKhz / 1000}MHz"
        }
}

@Serializable
data class CpuTopology(
    val coreCount: Int,
    /** Fastest cluster first. On a uniform machine there is exactly one. */
    val clusters: List<CpuCluster>,
    /** The raw `Features:` line from /proc/cpuinfo, or null if unreadable. */
    val features: String?,
    val totalRamBytes: Long?,
) {
    /** True for big.LITTLE and similar. A Neoverse server part reports one cluster. */
    val isHeterogeneous: Boolean get() = clusters.size > 1

    val bigCluster: CpuCluster? get() = clusters.firstOrNull()
    val littleCluster: CpuCluster? get() = if (isHeterogeneous) clusters.last() else null

    fun hasFeature(name: String): Boolean =
        features?.split(' ')?.any { it.equals(name, ignoreCase = true) } ?: false

    /**
     * The thread counts worth benchmarking on this machine.
     *
     * Not a fixed list: it is derived from the topology, so a 4-core uniform runner and an
     * 8-core big.LITTLE phone get sensible and *different* sweeps. Always includes the size
     * of each cluster and the total, because those are the physically meaningful boundaries.
     */
    fun candidateThreadCounts(): List<Int> {
        val candidates = sortedSetOf(1, 2)
        clusters.forEach { candidates += it.size }
        candidates += coreCount
        return candidates.filter { it in 1..coreCount }.sorted()
    }

    companion object {
        /** Reads the live machine. */
        fun detect(root: File = File("/")): CpuTopology {
            val cores = listCores(root)
            val freqs = cores.associateWith { readMaxFreqKhz(root, it) }
            return CpuTopology(
                coreCount = cores.size,
                clusters = groupIntoClusters(freqs),
                features = readFeatures(root),
                totalRamBytes = readTotalRamBytes(root),
            )
        }

        private fun listCores(root: File): List<Int> {
            val dirs = File(root, "sys/devices/system/cpu").listFiles { f: File ->
                f.isDirectory && f.name.matches(Regex("cpu\\d+"))
            }
            if (dirs == null || dirs.isEmpty()) {
                // Every Linux exposes this; if it is missing we are somewhere unexpected and
                // the JVM's own count is the only honest answer left.
                return (0 until Runtime.getRuntime().availableProcessors()).toList()
            }
            return dirs.map { it.name.removePrefix("cpu").toInt() }.sorted()
        }

        private fun readMaxFreqKhz(root: File, core: Int): Long? = runCatching {
            File(root, "sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                .readText().trim().toLong()
        }.getOrNull()

        /**
         * Cores sharing a maximum frequency are one cluster. This is a heuristic, and it is
         * the same one the Phase 1 probe used on the A20e, where it correctly separated the
         * 2 A73s from the 6 A53s. On a machine whose cpufreq is unreadable every core lands
         * in a single null-frequency cluster, which is the truthful answer rather than a
         * fabricated split.
         */
        private fun groupIntoClusters(freqs: Map<Int, Long?>): List<CpuCluster> =
            freqs.entries
                .groupBy({ it.value }, { it.key })
                .map { (freq, cores) -> CpuCluster(cores.sorted(), freq) }
                .sortedByDescending { it.maxFreqKhz ?: -1L }

        private fun readFeatures(root: File): String? = runCatching {
            File(root, "proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") || it.startsWith("flags") }
                    ?.substringAfter(':')?.trim()
            }
        }.getOrNull()

        private fun readTotalRamBytes(root: File): Long? = runCatching {
            File(root, "proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.filter { it.isDigit() }?.toLong()?.times(1024)
            }
        }.getOrNull()
    }
}
