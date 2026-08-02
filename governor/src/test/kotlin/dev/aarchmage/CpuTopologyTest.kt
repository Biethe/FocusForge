package dev.aarchmage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovery, against fake `/sys` and `/proc` trees.
 *
 * Both machines this project targets are modelled here, because the cross-silicon exhibit
 * depends on one code path describing both correctly: the operator's big.LITTLE phone and
 * the uniform Neoverse CI runner. A synthetic tree is the only way to test the second from
 * the first, and vice versa.
 */
class CpuTopologyTest {

    private fun fakeRoot(
        freqsKhz: Map<Int, Long?>,
        features: String? = null,
        memTotalKb: Long? = null,
    ): File {
        val root = Files.createTempDirectory("aarchmage").toFile()
        for ((core, khz) in freqsKhz) {
            val dir = File(root, "sys/devices/system/cpu/cpu$core/cpufreq")
            dir.mkdirs()
            if (khz != null) File(dir, "cpuinfo_max_freq").writeText("$khz\n")
        }
        File(root, "proc").mkdirs()
        if (features != null) {
            File(root, "proc/cpuinfo").writeText(
                "processor\t: 0\nBogoMIPS\t: 26.00\nFeatures\t: $features\n")
        }
        if (memTotalKb != null) {
            File(root, "proc/meminfo").writeText("MemTotal:       $memTotalKb kB\n")
        }
        root.deleteOnExit()
        return root
    }

    @Test
    fun `it separates the A20e's big and LITTLE clusters`() {
        // Exynos 7884B as CLAUDE.md §2 describes it: 2x A73 @ 1.56 GHz + 6x A53 @ 1.35 GHz.
        val root = fakeRoot(
            freqsKhz = (0..5).associateWith { 1_352_000L } + (6..7).associateWith { 1_560_000L },
            features = "fp asimd aes pmull sha1 sha2 crc32",
            memTotalKb = 2_809_856L,
        )
        val topo = CpuTopology.detect(root)

        assertEquals(8, topo.coreCount)
        assertTrue(topo.isHeterogeneous)
        assertEquals(2, topo.clusters.size)

        // Fastest first: the two A73s.
        assertEquals(listOf(6, 7), topo.bigCluster!!.cores)
        assertEquals(1_560_000L, topo.bigCluster!!.maxFreqKhz)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), topo.littleCluster!!.cores)

        assertEquals("cpu6-7@1560MHz", topo.bigCluster!!.label)

        // The features that decide whether llama.cpp may use its fast kernels at all.
        assertTrue(topo.hasFeature("asimd"))
        assertFalse(topo.hasFeature("asimddp"), "the A20e has no dot product")
        assertFalse(topo.hasFeature("i8mm"))
        assertFalse(topo.hasFeature("sve"))
    }

    @Test
    fun `it describes a uniform server part as one cluster`() {
        // The ubuntu-24.04-arm runner: Neoverse-class, no big.LITTLE, and it *does* have the
        // instructions the phone lacks. This is the other half of the exhibit.
        val root = fakeRoot(
            freqsKhz = (0..3).associateWith { null },
            features = "fp asimd aes sha1 sha2 crc32 atomics asimddp sha512 sve i8mm sve2",
        )
        val topo = CpuTopology.detect(root)

        assertEquals(4, topo.coreCount)
        assertFalse(topo.isHeterogeneous, "a uniform machine must not be split into fake clusters")
        assertEquals(1, topo.clusters.size)
        assertNull(topo.littleCluster)
        assertTrue(topo.hasFeature("i8mm"))
        assertTrue(topo.hasFeature("sve2"))
    }

    @Test
    fun `the thread sweep follows the machine rather than a fixed list`() {
        val phone = CpuTopology.detect(
            fakeRoot((0..5).associateWith { 1_352_000L } + (6..7).associateWith { 1_560_000L }),
        )
        // 1, 2 always; plus each cluster size (2 and 6); plus every core.
        assertEquals(listOf(1, 2, 6, 8), phone.candidateThreadCounts())

        val quadUniform = CpuTopology.detect(fakeRoot((0..3).associateWith { 2_000_000L }))
        assertEquals(listOf(1, 2, 4), quadUniform.candidateThreadCounts())

        val dualCore = CpuTopology.detect(fakeRoot((0..1).associateWith { 1_000_000L }))
        assertEquals(listOf(1, 2), dualCore.candidateThreadCounts())
        assertTrue(
            dualCore.candidateThreadCounts().all { it <= 2 },
            "never benchmark more threads than the machine has cores",
        )
    }

    @Test
    fun `unreadable frequencies give one honest cluster, not an invented split`() {
        val topo = CpuTopology.detect(fakeRoot((0..7).associateWith { null }))
        assertEquals(1, topo.clusters.size)
        assertNull(topo.clusters.single().maxFreqKhz)
        assertFalse(topo.isHeterogeneous)
    }

    @Test
    fun `missing files are reported as unknown rather than defaulted`() {
        val topo = CpuTopology.detect(fakeRoot(mapOf(0 to 1_000_000L)))
        assertNull(topo.features, "an unreadable cpuinfo must not become an empty feature set")
        assertNull(topo.totalRamBytes)
        assertFalse(topo.hasFeature("asimd"), "unknown features must never answer yes")
    }

    @Test
    fun `it can read the machine actually running the tests`() {
        // No assertion on the values — this runs on x86 CI, arm64 CI and the developer's
        // laptop. The point is that detection completes and reports something coherent,
        // including on the arm64 runner where the exhibit is produced.
        val topo = CpuTopology.detect()
        assertTrue(topo.coreCount >= 1)
        assertTrue(topo.clusters.isNotEmpty())
        assertTrue(topo.candidateThreadCounts().isNotEmpty())
        println("host topology: ${topo.coreCount} cores, " +
            "${topo.clusters.joinToString(" | ") { it.label }}, " +
            "sweep ${topo.candidateThreadCounts()}, features=${topo.features?.take(80)}")
    }
}
