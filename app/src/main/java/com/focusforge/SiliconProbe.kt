package com.focusforge

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the ground truth about the silicon we are running on.
 * Everything is read fresh on each call; anything unreadable is reported as such,
 * never guessed (CLAUDE.md evidence rule).
 */
class SiliconProbe(private val context: Context) {

    data class Cluster(val coreIds: List<Int>, val maxFreqKhz: Long?)

    data class Snapshot(
        val deviceModel: String,
        val androidVersion: String,
        val abis: List<String>,
        val coreCount: Int,
        val perCoreMaxFreqKhz: Map<Int, Long?>,
        val clusters: List<Cluster>,
        val cpuFeatures: String,
        val totalRamBytes: Long,
        val thermalStatus: String,
    )

    fun snapshot(): Snapshot {
        val coreIds = listCoreIds()
        val freqs = coreIds.associateWith { readMaxFreqKhz(it) }
        return Snapshot(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            abis = Build.SUPPORTED_ABIS.toList(),
            coreCount = coreIds.size,
            perCoreMaxFreqKhz = freqs,
            clusters = groupIntoClusters(freqs),
            cpuFeatures = readCpuFeatures(),
            totalRamBytes = readTotalRam(),
            thermalStatus = readThermalStatus(),
        )
    }

    private fun listCoreIds(): List<Int> {
        val dirs = File("/sys/devices/system/cpu").listFiles { f ->
            f.isDirectory && f.name.matches(Regex("cpu\\d+"))
        } ?: return (0 until Runtime.getRuntime().availableProcessors()).toList()
        return dirs.map { it.name.removePrefix("cpu").toInt() }.sorted()
    }

    private fun readMaxFreqKhz(coreId: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$coreId/cpufreq/cpuinfo_max_freq")
            .readText().trim().toLong()
    }.getOrNull()

    /** Cores sharing the same max frequency form one cluster (big.LITTLE heuristic). */
    private fun groupIntoClusters(freqs: Map<Int, Long?>): List<Cluster> =
        freqs.entries
            .groupBy({ it.value }, { it.key })
            .map { (freq, cores) -> Cluster(cores.sorted(), freq) }
            .sortedByDescending { it.maxFreqKhz ?: -1 }

    private fun readCpuFeatures(): String = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features") }
                ?.substringAfter(":")?.trim()
        }
    }.getOrNull() ?: "unreadable on this device"

    private fun readTotalRam(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(info)
        return info.totalMem
    }

    private fun readThermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "unavailable (needs Android 10+, this is API ${Build.VERSION.SDK_INT})"
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (val s = pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE (no throttling)"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT throttling"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE throttling"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE throttling"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN imminent"
            else -> "unknown status code $s"
        }
    }

    fun toJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        put("device_model", s.deviceModel)
        put("android_version", s.androidVersion)
        put("abis", JSONArray(s.abis))
        put("core_count", s.coreCount)
        put("per_core_max_freq_khz", JSONObject(
            s.perCoreMaxFreqKhz.entries.associate { (id, f) -> "cpu$id" to (f ?: JSONObject.NULL) }
        ))
        put("clusters", JSONArray(s.clusters.map { c ->
            JSONObject().apply {
                put("cores", JSONArray(c.coreIds))
                put("max_freq_khz", c.maxFreqKhz ?: JSONObject.NULL)
            }
        }))
        put("cpu_features", s.cpuFeatures)
        put("total_ram_bytes", s.totalRamBytes)
        put("thermal_status", s.thermalStatus)
        put("app_version", "0.1.0-phase1")
    }

    fun toDisplayText(s: Snapshot): String = buildString {
        fun ghz(khz: Long?) = if (khz == null) "?" else "%.2f GHz".format(khz / 1_000_000.0)

        appendLine("DEVICE")
        appendLine("  ${s.deviceModel}")
        appendLine("  ${s.androidVersion}")
        appendLine()
        appendLine("ABIs: ${s.abis.joinToString(", ")}")
        appendLine()
        appendLine("CPU: ${s.coreCount} cores in ${s.clusters.size} cluster(s)")
        s.clusters.forEachIndexed { i, c ->
            val label = if (i == 0 && s.clusters.size > 1) "big" else if (s.clusters.size > 1) "LITTLE" else "uniform"
            appendLine("  Cluster ${'A' + i} ($label): ${c.coreIds.size} cores @ ${ghz(c.maxFreqKhz)}  [cpu${c.coreIds.joinToString(",cpu")}]")
        }
        appendLine()
        appendLine("FEATURES (from /proc/cpuinfo)")
        appendLine("  ${s.cpuFeatures}")
        appendLine()
        val hasDotprod = " asimddp" in " ${s.cpuFeatures}"
        val hasI8mm = " i8mm" in " ${s.cpuFeatures}"
        appendLine("  dotprod (asimddp): ${if (hasDotprod) "YES" else "NO"}")
        appendLine("  i8mm:              ${if (hasI8mm) "YES" else "NO"}")
        appendLine()
        appendLine("RAM: %.1f GB total (%,d bytes)".format(s.totalRamBytes / 1e9, s.totalRamBytes))
        appendLine()
        appendLine("THERMAL: ${s.thermalStatus}")
    }
}
