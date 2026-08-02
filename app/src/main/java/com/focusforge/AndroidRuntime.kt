package com.focusforge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.aarchmage.GenerationResult
import dev.aarchmage.InferenceBackend
import dev.aarchmage.LinuxSensors
import dev.aarchmage.OpenModel
import dev.aarchmage.RunConfig
import java.io.File

/**
 * The Android side of aarchmage's platform boundary.
 *
 * `:governor` cannot import anything from Android — that is what lets the same code run on
 * the CI runner — so the two things it genuinely cannot do for itself arrive here: running
 * the real language model, and reading sensors only the platform exposes.
 */

/** Drives the real llama.cpp backend through [LlamaBridge]. */
class AndroidInferenceBackend(private val modelFile: File) : InferenceBackend {

    override var lastError: String = ""

    override fun open(config: RunConfig): OpenModel? {
        if (!modelFile.exists()) {
            lastError = "no model imported at ${modelFile.absolutePath}"
            return null
        }
        val session = LlamaBridge.open(modelFile.absolutePath, config.threads, config.nCtx)
        if (session == null) {
            lastError = LlamaBridge.lastError
            return null
        }
        return object : OpenModel {
            override val loadMs = session.loadMs
            override val rssAfterLoadBytes = session.rssAfterLoadBytes

            override fun generate(prompt: String, maxTokens: Int): GenerationResult? {
                val r = LlamaBridge.generate(session, prompt, maxTokens)
                if (!r.ok) {
                    lastError = r.error
                    return null
                }
                return GenerationResult(
                    ttftMs = r.ttftMs,
                    decodeMs = r.decodeMs,
                    tokens = r.tokens,
                    promptTokens = r.promptTokens,
                    reusedTokens = r.cachedPrefixTokens,
                    rssBytes = r.rssAfterGenBytes,
                    text = r.text,
                )
            }

            override fun close() = LlamaBridge.close(session)
        }
    }
}

/**
 * RSS comes from `/proc/self/statm` exactly as it does on any Linux — inherited rather than
 * reimplemented, so the phone and the CI runner measure memory the same way. Battery and
 * thermal are the parts only Android can answer.
 */
class AndroidSensors(private val context: Context) : LinuxSensors() {

    override fun batteryPercent(): Double? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return 100.0 * level / scale
    }

    /**
     * Null below API 29, and that is the honest answer rather than a zero. The A20e may be on
     * API 28, which is exactly why the governor's thermal signal is measured throughput decay
     * instead of a vendor level.
     */
    override fun thermalStatus(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            else -> "unknown"
        }
    }

    override fun nowMs(): Long = System.currentTimeMillis()
}
