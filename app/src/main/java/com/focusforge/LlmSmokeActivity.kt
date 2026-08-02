package com.focusforge

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Phase 5 gate, and deliberately nothing more.
 *
 * The architect's amendment 1 is explicit: before any coach UI exists, prove that a GGUF
 * loads and generates tokens **on the A20e**, whose Exynos 7884B is Armv8.0-A with no
 * dotprod, no i8mm and no SVE. llama.cpp's Android examples ship armv8.2+ flags as a matter
 * of course, and such a binary does not run slowly on this phone — it SIGILLs.
 *
 * So this screen has one button and a text view. No persona, no styling, no session
 * integration. If it prints tokens and an RSS under 700 MB, the gate is passed and the
 * coach gets built on top. If it dies, the compile flags are the suspect, not the model.
 */
class LlmSmokeActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var runButton: Button
    private lateinit var benchButton: Button
    private val worker = Executors.newSingleThreadExecutor()

    private val modelFile: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "models/model.gguf")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val importButton = Button(this).apply {
            text = "1. Import .gguf model"
            setOnClickListener { pickModel() }
        }
        runButton = Button(this).apply {
            text = "2. Generate 20 tokens"
            setOnClickListener { runSmokeTest() }
        }
        benchButton = Button(this).apply {
            text = "3. Benchmark: threads x cache"
            setOnClickListener { runBenchmark() }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(importButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(runButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(benchButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        })

        showStatus()
    }

    private fun showStatus() {
        val model = modelFile
        output.text = buildString {
            appendLine("FocusForge LLM smoke test")
            appendLine("build: ${BuildConfig.VERSION_NAME}")
            appendLine()
            appendLine("native library: ${if (LlamaBridge.available) "loaded" else "FAILED"}")
            LlamaBridge.loadFailure?.let { appendLine("  $it") }
            appendLine(LlamaBridge.buildInfo())
            appendLine()
            if (model.exists()) {
                appendLine("model: ${model.name}  %.1f MB".format(Locale.US, model.length() / 1e6))
                appendLine("at ${model.absolutePath}")
                appendLine()
                appendLine("Tap \"Generate 20 tokens\".")
            } else {
                appendLine("model: NOT IMPORTED YET")
                appendLine()
                appendLine("Tap \"Import .gguf model\" and pick the SmolLM2 file you")
                appendLine("copied to the phone. It is copied into the app's own")
                appendLine("storage once; you only do this after a fresh install.")
            }
            appendLine()
            appendLine("app RSS now: %.0f MB".format(Locale.US, LlamaBridge.rssBytes() / 1e6))
        }
    }

    // ------------------------------------------------------------------ model import

    private fun pickModel() {
        // The app has no INTERNET permission and never will (CLAUDE.md §4.3), so the model
        // arrives through the system file picker. SAF hands us a read grant for exactly the
        // one file the user chose.
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQUEST_MODEL,
        )
    }

    @Deprecated("classic result callback is fine for a one-button test screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        output.text = "Copying model into app storage…"
        worker.execute {
            val result = runCatching {
                val target = modelFile
                target.parentFile?.mkdirs()
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "could not open the chosen file" }
                    target.outputStream().use { input.copyTo(it, 1 shl 20) }
                }
                target.length()
            }
            runOnUiThread {
                result.onSuccess {
                    toast("Imported %.1f MB".format(Locale.US, it / 1e6))
                    showStatus()
                }.onFailure {
                    output.text = "Import failed: ${it.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------ the gate itself

    private fun runSmokeTest() {
        val model = modelFile
        if (!model.exists()) {
            toast("Import a .gguf model first")
            return
        }
        runButton.isEnabled = false
        output.text = "Opening the model and generating twice…\n" +
            "Run 1 is cold, run 2 reuses the open model. That difference is the point."

        worker.execute {
            val rssBefore = LlamaBridge.rssBytes()
            val session = LlamaBridge.open(model.absolutePath, THREADS, N_CTX)
            if (session == null) {
                runOnUiThread {
                    runButton.isEnabled = true
                    output.text = "FAIL — could not open the model\n\n${LlamaBridge.lastError}"
                }
                return@execute
            }
            // Two generations on the same open model. The first pays for any lazily-faulted
            // pages of the mmapped weights; the second is the steady state a coach message
            // would actually see. Reporting only one of them would misrepresent the phone.
            val cold = LlamaBridge.generate(session, PROMPT, MAX_TOKENS)
            val warm = LlamaBridge.generate(session, PROMPT_2, MAX_TOKENS)
            LlamaBridge.close(session)

            runOnUiThread {
                runButton.isEnabled = true
                output.text = report(session, cold, warm, rssBefore)
            }
        }
    }

    private fun report(
        session: LlamaBridge.Session,
        cold: LlamaBridge.Result,
        warm: LlamaBridge.Result,
        rssBeforeBytes: Long,
    ): String = buildString {
        val pass = cold.ok && warm.ok
        appendLine("FocusForge LLM smoke test — ${if (pass) "PASS" else "FAIL"}")
        appendLine(LlamaBridge.buildInfo())
        appendLine("threads=$THREADS  n_ctx=$N_CTX  max_tokens=$MAX_TOKENS  mmap=yes")
        appendLine("model ${modelFile.name}  %.0f MB".format(Locale.US, modelFile.length() / 1e6))
        appendLine()

        appendLine("MODEL LOAD (once per session, not per message)")
        appendLine("  %d ms".format(session.loadMs))
        appendLine()

        for ((label, r) in listOf("RUN 1 (cold)" to cold, "RUN 2 (warm)" to warm)) {
            appendLine(label)
            if (!r.ok) {
                appendLine("  ERROR: ${r.error}")
                appendLine("  first token id ${r.firstTokenId}" +
                    if (r.firstWasEndOfGeneration) " (end-of-generation)" else "")
            } else {
                appendLine("  TTFT      %d ms   (model resident — no disk in this number)"
                    .format(r.ttftMs))
                appendLine("  decode    %.1f tok/s   (%d tokens in %d ms)"
                    .format(Locale.US, r.tokensPerSecond, r.tokens, r.decodeMs))
                appendLine("  prompt    %d tokens, chat template %s"
                    .format(r.promptTokens, if (r.usedChatTemplate) "applied" else "NOT APPLIED"))
                appendLine("  text: ${r.text.trim().take(120)}")
            }
            appendLine()
        }

        appendLine("MEMORY")
        appendLine("  before open   %.0f MB".format(Locale.US, rssBeforeBytes / 1e6))
        appendLine("  after load    %.0f MB".format(Locale.US, session.rssAfterLoadBytes / 1e6))
        appendLine("  after gen     %.0f MB".format(Locale.US, warm.rssAfterGenBytes / 1e6))
        val peakMb = maxOf(session.rssAfterLoadBytes, warm.rssAfterGenBytes) / 1e6
        if (peakMb > RSS_BUDGET_MB) {
            appendLine("  !! %.0f MB EXCEEDS the %.0f MB budget (CLAUDE.md 2)."
                .format(Locale.US, peakMb, RSS_BUDGET_MB))
            appendLine("  !! Report this before anything is built on top.")
        } else {
            appendLine("  within the %.0f MB budget (peak %.0f MB)"
                .format(Locale.US, RSS_BUDGET_MB, peakMb))
        }
        appendLine()
        appendLine("Contract targets: TTFT <= 3000 ms, decode >= 5 tok/s.")
        appendLine("Send this whole block to the architect, whatever it says.")
    }

    // ------------------------------------------------------------------ benchmark

    /**
     * Sweeps thread count against KV-cache reuse, on a prompt the size of a real coaching
     * one. This is the Phase 6 self-benchmark in miniature, done by hand: the governor will
     * run exactly these two axes automatically and write the winner into a device profile.
     *
     * It exists because the two questions cannot be answered from a normal session. Cache
     * reuse only pays from the *second* message onward, and the coach's five-minute quiet
     * rule means a short session can never produce two — so the optimisation shipped in
     * 0.5.6 was measured at zero benefit purely because it was never exercised.
     */
    private fun runBenchmark() {
        val model = modelFile
        if (!model.exists()) {
            toast("Import a .gguf model first")
            return
        }
        benchButton.isEnabled = false
        runButton.isEnabled = false
        output.text = "Benchmarking ${THREAD_SWEEP.joinToString(", ")} threads…\n" +
            "Each opens the model, generates once cold, then twice more with the cache warm.\n" +
            "This takes a couple of minutes. Leave the screen on."

        worker.execute {
            val report = StringBuilder()
            report.appendLine("FocusForge benchmark — threads x KV cache")
            report.appendLine(LlamaBridge.buildInfo())
            report.appendLine("model ${model.name}  %.0f MB   n_ctx=$N_CTX"
                .format(Locale.US, model.length() / 1e6))
            report.appendLine("prompt is a realistic coaching prompt, not the short smoke-test one")
            report.appendLine()
            report.appendLine("threads  load    run   prompt  reused  TTFT     decode")

            for (threads in THREAD_SWEEP) {
                val session = LlamaBridge.open(model.absolutePath, threads, N_CTX)
                if (session == null) {
                    report.appendLine("$threads: FAILED — ${LlamaBridge.lastError}")
                    continue
                }
                // Same prompt shape each time, different numbers — exactly what the coach
                // does, so the shared prefix is the real one rather than a flattering one.
                for ((i, prompt) in BENCH_PROMPTS.withIndex()) {
                    val r = LlamaBridge.generate(session, prompt, 20)
                    if (!r.ok) {
                        report.appendLine("%7d  %5d  %5d  FAILED: %s"
                            .format(threads, session.loadMs, i + 1, r.error))
                        continue
                    }
                    report.appendLine("%7d  %5d  %5d  %6d  %6d  %5d ms  %.1f tok/s".format(
                        Locale.US, threads, session.loadMs, i + 1,
                        r.promptTokens, r.cachedPrefixTokens, r.ttftMs, r.tokensPerSecond))
                }
                LlamaBridge.close(session)
                report.appendLine()
            }
            report.appendLine("Run 1 is cold (empty cache). Runs 2 and 3 reuse the shared")
            report.appendLine("instruction prefix — the 'reused' column says how much.")
            report.appendLine("Contract: TTFT <= 3000 ms, decode >= 5 tok/s.")

            runOnUiThread {
                benchButton.isEnabled = true
                runButton.isEnabled = true
                output.text = report.toString()
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private companion object {
        const val REQUEST_MODEL = 11

        /** Short and neutral: this measures the silicon, not the prompt. */
        const val PROMPT = "Give one short, encouraging tip for staying focused while studying."

        /** A second, different prompt so run 2 cannot be answered from a cached anything. */
        const val PROMPT_2 = "Give one short tip for resting your eyes during a long study session."

        // Measured, not assumed: 6 threads gives 1.85x the prefill of 2 on this device
        // (bench/results/a20e-threads-kvcache-20260802.json). The Phase 6 governor will
        // derive this per device instead of carrying a constant at all.
        const val THREADS = 6
        const val N_CTX = 512
        const val MAX_TOKENS = 20
        const val RSS_BUDGET_MB = 700.0

        /**
         * The A20e is 2x Cortex-A73 + 6x Cortex-A53. Two threads was the documented starting
         * point (CLAUDE.md 5); this sweep is how we find out whether it was the right one,
         * rather than assuming. Prompt processing is compute-bound and parallelises, and TTFT
         * on this device is almost entirely prompt processing.
         */
        val THREAD_SWEEP = listOf(2, 4, 6)

        /**
         * Three prompts with the same opening and different numbers — the shape the coach
         * actually produces. Using one identical prompt three times would reuse the entire
         * cache and report a benefit we would never see in practice.
         */
        val BENCH_PROMPTS = listOf(
            "Encourage someone studying. One message, max 40 words, speak to them directly, " +
                "no lists.\nLast minutes: focus 42/100, eyes on work 55%, 3 long eye closures, " +
                "head 12.0 deg, 23 min in.\nThey look tired: eyes closing longer than blinks.\n",
            "Encourage someone studying. One message, max 40 words, speak to them directly, " +
                "no lists.\nLast minutes: focus 61/100, eyes on work 74%, 1 long eye closures, " +
                "head 4.5 deg, 31 min in.\nTheir attention has been drifting.\n",
            "Encourage someone studying. One message, max 40 words, speak to them directly, " +
                "no lists.\nLast minutes: focus 88/100, eyes on work 91%, 0 long eye closures, " +
                "head 1.2 deg, 40 min in.\nRoutine check-in, nothing is wrong.\n",
        )
    }
}
