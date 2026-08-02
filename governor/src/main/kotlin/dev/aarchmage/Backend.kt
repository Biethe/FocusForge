package dev.aarchmage

import java.io.File
import kotlinx.serialization.Serializable

/**
 * The configuration knobs a run can be given.
 *
 * These are the things the governor is allowed to change. They are arguments rather than
 * settings because llama.cpp fixes threads and context size when a context is created —
 * "change the thread count" therefore means "open a new model", and the benchmark has to
 * know that opening costs time.
 */
@Serializable
data class RunConfig(
    val threads: Int,
    val nCtx: Int = 512,
    /**
     * Cluster to pin to, or null to let the scheduler place threads freely.
     *
     * Unimplemented on the phone so far and therefore always null there. Kept in the type
     * because a profile that cannot express "I did not pin" separately from "I pinned to
     * nothing" would quietly lose the distinction.
     */
    val affinity: String? = null,
)

/** One generation, as the backend measured it. Nothing here is derived or rounded. */
@Serializable
data class GenerationResult(
    val ttftMs: Long,
    val decodeMs: Long,
    val tokens: Int,
    val promptTokens: Int,
    /** Prompt tokens served from cache rather than processed. */
    val reusedTokens: Int,
    val rssBytes: Long,
    val text: String = "",
) {
    val decodeTokPerSec: Double
        get() = if (decodeMs <= 0 || tokens <= 1) 0.0 else (tokens - 1) * 1000.0 / decodeMs
}

/** An open model. Closing it releases the weights and the KV cache. */
interface OpenModel : AutoCloseable {
    val loadMs: Long
    val rssAfterLoadBytes: Long
    /** Returns null on failure; the reason belongs in [InferenceBackend.lastError]. */
    fun generate(prompt: String, maxTokens: Int): GenerationResult?
}

/**
 * Whatever actually runs the model.
 *
 * An interface rather than a direct call into llama.cpp so that `:governor` stays free of
 * JNI and Android, and so the harness can be tested on a plain JVM against a simulated
 * device. It also means a different runtime could be dropped in without touching the
 * governor — which the architect's Aug 4 fallback plan would have required.
 */
interface InferenceBackend {
    fun open(config: RunConfig): OpenModel?
    val lastError: String
}

/**
 * Readings the host platform can take that a JVM cannot.
 *
 * Everything here is nullable because "this device cannot tell us" is a real answer that
 * must survive into the profile. The A20e may be on API 28, where Android exposes no thermal
 * status at all; recording null there is honest, recording 0 would be a lie.
 */
interface PlatformSensors {
    fun rssBytes(): Long
    fun batteryPercent(): Double?
    fun thermalStatus(): String?
    fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * The parts a plain JVM can do for itself — used on the CI runner, and as a base class for
 * the Android implementation so that RSS is read the same way in both places.
 */
open class LinuxSensors(private val root: File = File("/")) : PlatformSensors {

    override fun rssBytes(): Long = runCatching {
        val fields = File(root, "proc/self/statm").readText().trim().split(" ")
        fields[1].toLong() * pageSize
    }.getOrDefault(-1L)

    override fun batteryPercent(): Double? = runCatching {
        // Present on many Linux machines, absent on most servers. Null is the answer there.
        File(root, "sys/class/power_supply/BAT0/capacity").readText().trim().toDouble()
    }.getOrNull()

    override fun thermalStatus(): String? = null

    private val pageSize: Long by lazy {
        runCatching {
            ProcessBuilder("getconf", "PAGESIZE").start()
                .inputStream.bufferedReader().readText().trim().toLong()
        }.getOrDefault(4096L)
    }
}
