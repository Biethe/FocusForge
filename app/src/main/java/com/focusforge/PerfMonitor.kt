package com.focusforge

import android.os.SystemClock
import java.io.File

/**
 * Rolling performance stats for the HUD: camera fps, landmark inference ms,
 * and the app's actual RSS read from /proc/self/status (VmRSS — real resident
 * memory, the number our 700 MB budget is written against).
 */
class PerfMonitor {

    private val frameTimesMs = ArrayDeque<Long>()
    private val inferenceTimesMs = ArrayDeque<Long>()

    @Synchronized
    fun onFrame() {
        val now = SystemClock.uptimeMillis()
        frameTimesMs.addLast(now)
        while (frameTimesMs.size > WINDOW) frameTimesMs.removeFirst()
    }

    @Synchronized
    fun onInference(ms: Long) {
        inferenceTimesMs.addLast(ms)
        while (inferenceTimesMs.size > WINDOW) inferenceTimesMs.removeFirst()
    }

    @Synchronized
    fun fps(): Double {
        if (frameTimesMs.size < 2) return 0.0
        val spanMs = frameTimesMs.last() - frameTimesMs.first()
        if (spanMs <= 0) return 0.0
        return (frameTimesMs.size - 1) * 1000.0 / spanMs
    }

    @Synchronized
    fun avgInferenceMs(): Double =
        if (inferenceTimesMs.isEmpty()) 0.0 else inferenceTimesMs.average()

    fun rssMb(): Double = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.replace(Regex("[^0-9]"), "")?.toDouble()?.div(1024.0)
        }
    }.getOrNull() ?: -1.0

    private companion object {
        const val WINDOW = 30
    }
}
