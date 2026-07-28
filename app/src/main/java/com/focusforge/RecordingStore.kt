package com.focusforge

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.focusforge.core.LandmarkRecording
import com.focusforge.core.ReplayJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves landmark recordings and hands them to the share sheet.
 *
 * Files land in the app's own external files directory, so the operator can also pull them
 * over USB without root. A recording contains derived numbers only — no image data ever
 * touches storage (CLAUDE.md §4.3).
 */
class RecordingStore(private val context: Context) {

    private val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
            .apply { mkdirs() }

    fun save(recording: LandmarkRecording): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // Name starts with the label: the replay tests pick recordings up by label.
        val file = File(directory, "${recording.label}-${deviceSlug()}-$stamp.json")
        file.writeText(ReplayJson.encode(recording))
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
            "Share landmark recording",
        )
    }

    private fun deviceSlug(): String =
        Build.MODEL.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "device" }

    private companion object {
        const val DIR_NAME = "replays"
    }
}
