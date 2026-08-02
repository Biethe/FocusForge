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

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(importButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(runButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

        // The A20e has 2x A73 + 6x A53. Two threads is the documented starting point
        // (CLAUDE.md §5); the Phase 6 governor will replace this constant with a measured
        // choice per cluster.
        const val THREADS = 2
        const val N_CTX = 512
        const val MAX_TOKENS = 20
        const val RSS_BUDGET_MB = 700.0
    }
}
