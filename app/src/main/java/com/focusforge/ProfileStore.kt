package com.focusforge

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.aarchmage.BenchmarkPlan
import dev.aarchmage.CpuTopology
import dev.aarchmage.DeviceProfile
import dev.aarchmage.PerformanceContract
import dev.aarchmage.ProfileDeriver
import dev.aarchmage.SelfBenchmark
import java.io.File

/**
 * Runs the first-launch self-benchmark on this phone and keeps the profile it derives.
 *
 * The profile lives beside the session exports, which means `scripts/sync-device.sh` pulls it
 * off the device with everything else — the silicon lockfile ends up next to the CI runner's
 * in `bench/profiles/` without anybody exporting anything by hand.
 */
class ProfileStore(private val context: Context) {

    private val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
            .apply { mkdirs() }

    val profileFile: File get() = File(directory, "device.profile.json")

    fun load(): DeviceProfile? = runCatching {
        if (!profileFile.exists()) null else DeviceProfile.fromJson(profileFile.readText())
    }.getOrNull()

    /**
     * Measures this device and writes the profile. Blocking and CPU-heavy — worker thread only.
     *
     * @param modelFile the imported GGUF. Without it there is nothing to benchmark, and a
     *        profile derived from a stand-in workload would describe the wrong thing entirely.
     */
    fun runSelfBenchmark(
        modelFile: File,
        appVersion: String,
        onProgress: (String) -> Unit,
    ): DeviceProfile? {
        if (!modelFile.exists()) return null
        val topology = CpuTopology.detect()
        val sensors = AndroidSensors(context)
        val report = SelfBenchmark(
            topology = topology,
            backend = AndroidInferenceBackend(modelFile),
            sensors = sensors,
            // The architect's spec is about a minute, once. Opening a 386 MB model costs
            // roughly two seconds per thread count on this device, which the harness knows
            // and budgets around.
            plan = BenchmarkPlan(budgetMs = 90_000, maxTokens = 12),
        ).run { p -> onProgress("[${p.step}/${p.totalSteps}] ${p.label} — ${p.elapsedMs / 1000}s") }

        val profile = ProfileDeriver.derive(
            report = report,
            contract = PerformanceContract(),
            // The coach's real prompt, measured on this device: 83 tokens, 30 of them reused
            // from the shared instruction once a session is under way.
            typicalPromptTokens = 83,
            typicalReusedTokens = 30,
            device = mapOf(
                "label" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "android" to (android.os.Build.VERSION.RELEASE ?: "?"),
                "app_version" to appVersion,
                "workload" to "llama.cpp + ${modelFile.name}",
                "workload_note" to "Real language-model measurements on the physical device.",
            ),
            nowEpochMs = System.currentTimeMillis(),
            availableModels = listOf(modelFile.name),
        )
        profileFile.writeText(profile.toJson())
        return profile
    }

    fun shareIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", profileFile)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, profileFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share device profile",
        )
    }

    private companion object {
        const val DIR_NAME = "profiles"
    }
}
