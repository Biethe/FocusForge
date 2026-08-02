package com.focusforge

/**
 * The Kotlin side of the llama.cpp JNI layer.
 *
 * **Every knob is a runtime argument** — threads, `n_ctx`, token budget — because the
 * Phase 6 governor is going to drive exactly this interface: benchmark each CPU cluster
 * with different thread counts, pick a configuration, and re-tune it mid-session when the
 * performance contract is violated (architect, 2026-08-02, amendment 4).
 *
 * **The model is held open across generations.** The first version loaded, generated and
 * freed in one call, which made the reported TTFT include mapping a 386 MB file off flash —
 * 3149 ms on the A20e, nearly all of it disk. Load and generation are now timed separately,
 * because they are separate things: load happens once per session, TTFT happens per message,
 * and the performance contract is about the second one.
 */
object LlamaBridge {

    /** An open model. Close it when done — it holds a mapped GGUF and a KV cache. */
    class Session internal constructor(
        internal val handle: Long,
        /** How long opening the model took. Once per session, not per message. */
        val loadMs: Long,
        /** VmRSS right after the model is mapped — the 700 MB budget is against this. */
        val rssAfterLoadBytes: Long,
    ) {
        @Volatile internal var closed = false
    }

    /** Result of one generation. Every figure is measured, none estimated. */
    data class Result(
        val ok: Boolean,
        val error: String,
        val text: String,
        /**
         * Time to first token **with the model already resident** — prompt processing plus
         * one token, no disk in the path. This is what the performance contract means.
         */
        val ttftMs: Long,
        /** Time spent generating after the first token, so tok/s is not diluted by the prompt. */
        val decodeMs: Long,
        val tokens: Int,
        val rssAfterGenBytes: Long,
        val promptTokens: Int = 0,
        val firstTokenId: Int = -1,
        val firstWasEndOfGeneration: Boolean = false,
        val usedChatTemplate: Boolean = false,
    ) {
        val tokensPerSecond: Double
            get() = if (decodeMs <= 0 || tokens <= 1) 0.0
                    else (tokens - 1) * 1000.0 / decodeMs
    }

    class LoadFailure(val error: String)

    @Volatile private var loadError: String? = null
    private var loaded = false

    val available: Boolean get() { ensureLoaded(); return loadError == null }
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

    /**
     * Opens a model. Blocking and slow (hundreds of ms to seconds) — never on the main
     * thread. Returns null and fills [lastError] on failure.
     */
    fun open(modelPath: String, threads: Int, nCtx: Int = 512): Session? {
        ensureLoaded()
        loadError?.let { lastError = "native library not loaded: $it"; return null }
        val packed = try {
            nativeLoad(modelPath, threads, nCtx)
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            return null
        }
        val f = packed.split(SEP)
        if (f.size < 5) { lastError = "malformed native reply from load"; return null }
        if (f[0] != "1") { lastError = f[1]; return null }
        return Session(
            handle = f[2].toLongOrNull() ?: 0L,
            loadMs = f[3].toLongOrNull() ?: -1,
            rssAfterLoadBytes = f[4].toLongOrNull() ?: -1,
        )
    }

    @Volatile var lastError: String = ""
        private set

    /** Generates from an open session. Blocking and CPU-heavy — never on the main thread. */
    fun generate(session: Session, prompt: String, maxTokens: Int = 20): Result {
        if (session.closed) return failed("session already closed")
        val packed = try {
            nativeGenerate(session.handle, prompt, maxTokens)
        } catch (e: Throwable) {
            return failed("${e.javaClass.simpleName}: ${e.message}")
        }
        val f = packed.split(SEP)
        if (f.size < 11) return failed("malformed native reply from generate")
        return Result(
            ok = f[0] == "1",
            error = f[1],
            text = f[2],
            ttftMs = f[3].toLongOrNull() ?: -1,
            decodeMs = f[4].toLongOrNull() ?: -1,
            tokens = f[5].toIntOrNull() ?: 0,
            rssAfterGenBytes = f[6].toLongOrNull() ?: -1,
            promptTokens = f[7].toIntOrNull() ?: 0,
            firstTokenId = f[8].toIntOrNull() ?: -1,
            firstWasEndOfGeneration = f[9] == "1",
            usedChatTemplate = f[10] == "1",
        )
    }

    @Synchronized
    fun close(session: Session) {
        if (session.closed) return
        session.closed = true
        try { nativeFree(session.handle) } catch (_: Throwable) { }
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

    private fun failed(message: String) =
        Result(false, message, "", -1, -1, 0, -1)

    /** ASCII unit separator — the field delimiter the JNI layer packs with. */
    private const val SEP = '\u001F'

    private external fun nativeLoad(modelPath: String, threads: Int, nCtx: Int): String
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
    private external fun nativeBuildInfo(): String
    private external fun nativeRssBytes(): Long
}
