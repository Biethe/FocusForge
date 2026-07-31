package com.focusforge

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.focusforge.core.SessionJson
import com.focusforge.core.SessionRecording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves session exports and hands them to the share sheet.
 *
 * A session file carries scores and signals, one row per second — no landmarks, no
 * blendshapes, no image data. There is a test in :core asserting that the encoded text
 * cannot even mention them (CLAUDE.md §4.3).
 */
class SessionStore(private val context: Context) {

    private val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
            .apply { mkdirs() }

    fun save(session: SessionRecording): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "session-${deviceSlug()}-$stamp.json")
        file.writeText(SessionJson.encode(session))
        return file
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share session JSON",
        )
    }

    /**
     * The silicon facts that go into the export, flattened to strings so that :core never
     * needs to know what Android can report.
     */
    fun deviceInfo(probe: SiliconProbe): Map<String, String> {
        val s = probe.snapshot()
        return linkedMapOf(
            "model" to s.deviceModel,
            "android" to s.androidVersion,
            "app_version" to BuildConfig.VERSION_NAME,
            "abis" to s.abis.joinToString(","),
            "cores" to s.coreCount.toString(),
            "clusters" to s.clusters.joinToString("; ") { c ->
                "cpu${c.coreIds.joinToString(",")}@${c.maxFreqKhz ?: "?"}kHz"
            },
            "cpu_features" to s.cpuFeatures,
            "total_ram_bytes" to s.totalRamBytes.toString(),
            "thermal_status" to s.thermalStatus,
        )
    }

    private fun deviceSlug(): String =
        Build.MODEL.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "device" }

    private companion object {
        const val DIR_NAME = "sessions"
    }
}
