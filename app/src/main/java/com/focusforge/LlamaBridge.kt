package com.focusforge

/**
 * The Kotlin side of the llama.cpp JNI layer.
 *
 * **Every knob is a runtime argument** — threads, `n_ctx`, token budget — because the
 * Phase 6 governor is going to drive exactly this interface: benchmark each CPU cluster
 * with different thread counts, pick a configuration, and re-tune it mid-session when the
 * performance contract is violated (architect, 2026-08-02, amendment 4). Anything fixed at
 * build time would have to be torn back out.
 *
 * Affinity is not here yet. It belongs on this interface and will arrive with the
 * governor's per-cluster probe; the JNI signature is shaped to take it without a rewrite.
 */
object LlamaBridge {

    /** Result of one generation. Every figure is measured, none estimated. */
    data class Result(
        val ok: Boolean,
        val error: String,
        val text: String,
        /** Wall clock from the call to the first token out of the model. */
        val ttftMs: Long,
        val totalMs: Long,
        val tokens: Int,
        /** VmRSS right after the model is mapped — the 700 MB budget is against this. */
        val rssAfterLoadBytes: Long,
        /** ...and again after the first generation (architect amendment 2). */
        val rssAfterGenBytes: Long,
    ) {
        /** Decode tokens per second, excluding the prompt-processing time before TTFT. */
        val tokensPerSecond: Double
            get() {
                val decodeMs = totalMs - ttftMs
                return if (decodeMs <= 0 || tokens <= 1) 0.0
                else (tokens - 1) * 1000.0 / decodeMs
            }
    }

    @Volatile private var loadError: String? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loadError == null
        }

    val loadFailure: String? get() { ensureLoaded(); return loadError }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            System.loadLibrary("focusforge_llm")
        } catch (e: Throwable) {
            // A missing or unloadable .so must surface as a message on screen, not as a
            // crash the operator has to read a stack trace to understand.
            loadError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private var loaded = false

    /**
     * Loads the GGUF (mmap), generates up to [maxTokens], frees everything, and returns
     * what was measured. Blocking and CPU-heavy — never call it from the main thread.
     */
    fun generate(
        modelPath: String,
        prompt: String,
        threads: Int,
        nCtx: Int = 512,
        maxTokens: Int = 20,
    ): Result {
        ensureLoaded()
        loadError?.let {
            return Result(false, "native library not loaded: $it", "", -1, -1, 0, -1, -1)
        }
        val packed = try {
            nativeGenerate(modelPath, prompt, threads, nCtx, maxTokens)
        } catch (e: Throwable) {
            return Result(false, "${e.javaClass.simpleName}: ${e.message}", "", -1, -1, 0, -1, -1)
        }
        val f = packed.split('')
        if (f.size < 8) return Result(false, "malformed native reply", "", -1, -1, 0, -1, -1)
        return Result(
            ok = f[0] == "1",
            error = f[1],
            text = f[2],
            ttftMs = f[3].toLongOrNull() ?: -1,
            totalMs = f[4].toLongOrNull() ?: -1,
            tokens = f[5].toIntOrNull() ?: 0,
            rssAfterLoadBytes = f[6].toLongOrNull() ?: -1,
            rssAfterGenBytes = f[7].toLongOrNull() ?: -1,
        )
    }

    /**
     * What the .so was actually compiled for. If this ever names dotprod, i8mm or SVE, the
     * APK is mis-built and would SIGILL on the A20e — better read on screen than inferred
     * from a crash.
     */
    fun buildInfo(): String {
        ensureLoaded()
        loadError?.let { return "native library not loaded: $it" }
        return try { nativeBuildInfo() } catch (e: Throwable) { "unavailable: ${e.message}" }
    }

    fun rssBytes(): Long {
        ensureLoaded()
        if (loadError != null) return -1
        return try { nativeRssBytes() } catch (e: Throwable) { -1 }
    }

    private external fun nativeGenerate(
        modelPath: String, prompt: String, threads: Int, nCtx: Int, maxTokens: Int,
    ): String

    private external fun nativeBuildInfo(): String
    private external fun nativeRssBytes(): Long
}
