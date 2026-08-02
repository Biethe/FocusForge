package com.focusforge

import android.util.Log
import com.focusforge.core.CoachLanguage
import com.focusforge.core.CoachMessage
import com.focusforge.core.CoachPolicy
import com.focusforge.core.CoachPrompt
import com.focusforge.core.FocusState
import com.focusforge.core.SignalSnapshot
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the coach beside a live session: watches the fused state, and when [CoachPolicy]
 * says it is time, generates one short message on a background thread.
 *
 * The *decisions* — when to speak, what to say about — live in `:core` and are unit-tested
 * there. This class is the plumbing: an open model, one worker thread, and the rule that a
 * generation already in flight blocks another from starting.
 *
 * The model is opened once and held for the session. On the A20e that costs ~1.9 s and
 * 421 MB (q8_0, measured 2026-08-02); reopening it per message would put two seconds of
 * disk in front of every coaching line.
 */
class CoachRunner(
    private val modelFile: File,
    private val threads: Int = 2,
    private val nCtx: Int = 512,
    /** Called on a worker thread when a message has been generated. */
    private val onMessage: (CoachMessage, String) -> Unit,
    /** Called on a worker thread whenever the runner's state changes, for the UI line. */
    private val onStatus: (String) -> Unit,
    /** Stands the vision loop down while the model works. See [FacePipeline.paused]. */
    private val setVisionPaused: (Boolean) -> Unit = {},
) {
    @Volatile var language: CoachLanguage = CoachLanguage.ENGLISH

    private val policy = CoachPolicy()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val generating = AtomicBoolean(false)

    @Volatile private var session: LlamaBridge.Session? = null
    @Volatile private var state: State = State.IDLE

    enum class State { IDLE, LOADING, READY, GENERATING, UNAVAILABLE }

    val ready: Boolean get() = state == State.READY || state == State.GENERATING

    /** Opens the model in the background. Safe to call when no model has been imported. */
    fun start() {
        if (!modelFile.exists()) {
            state = State.UNAVAILABLE
            onStatus("coach off — no model imported")
            return
        }
        if (state != State.IDLE) return
        state = State.LOADING
        onStatus("coach — loading model…")
        worker.execute {
            val opened = LlamaBridge.open(modelFile.absolutePath, threads, nCtx)
            if (opened == null) {
                state = State.UNAVAILABLE
                onStatus("coach unavailable: ${LlamaBridge.lastError}")
                Log.e(TAG, "model open failed: ${LlamaBridge.lastError}")
            } else {
                session = opened
                state = State.READY
                onStatus("coach ready (model loaded in ${opened.loadMs} ms)")
                Log.i(TAG, "coach ready, load ${opened.loadMs} ms, RSS ${opened.rssAfterLoadBytes}")
            }
        }
    }

    /**
     * Feed every frame. Cheap when nothing is due — the policy is a handful of comparisons.
     *
     * A generation takes seconds, so if one is still running the trigger is **dropped, not
     * queued**: by the time a queued message arrived it would be describing a state the user
     * has already left.
     */
    fun onState(focus: FocusState, snapshot: SignalSnapshot) {
        val context = policy.update(focus, snapshot) ?: return
        val open = session ?: return
        if (!generating.compareAndSet(false, true)) {
            Log.i(TAG, "trigger ${context.trigger} dropped — still generating")
            return
        }
        val lang = language
        state = State.GENERATING
        onStatus("coach — thinking…")
        worker.execute {
            setVisionPaused(true)
            try {
                val prompt = CoachPrompt.build(context, lang)
                val result = LlamaBridge.generate(open, prompt, MAX_TOKENS)
                if (!result.ok) {
                    onStatus("coach failed: ${result.error}")
                    Log.e(TAG, "generation failed: ${result.error}")
                    return@execute
                }
                val text = CoachPrompt.trimToWords(result.text)
                val message = CoachMessage(
                    t = focus.elapsedMs,
                    trigger = context.trigger.name,
                    language = if (lang == CoachLanguage.FRENCH) "fr" else "en",
                    text = text,
                    ttftMs = result.ttftMs,
                    tokensPerSecond = result.tokensPerSecond,
                    tokens = result.tokens,
                    promptTokens = result.promptTokens,
                )
                onMessage(message, "%d ms to first word · %.1f tok/s"
                    .format(result.ttftMs, result.tokensPerSecond))
            } catch (e: Throwable) {
                Log.e(TAG, "coach generation threw", e)
                onStatus("coach error: ${e.message}")
            } finally {
                setVisionPaused(false)
                state = State.READY
                generating.set(false)
            }
        }
    }

    fun close() {
        worker.execute { session?.let { LlamaBridge.close(it) }; session = null }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "FocusCoach"

        /**
         * A 30-word message is roughly 45 tokens; 60 leaves room to finish a sentence, and
         * [CoachPrompt.trimToWords] enforces the promise if the model does not.
         *
         * Lowered from 80 after both on-device messages ran to the cap and were cut
         * mid-clause. A cap the model keeps hitting is a cap set too high for it.
         */
        const val MAX_TOKENS = 60
    }
}
