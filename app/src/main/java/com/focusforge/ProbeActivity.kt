package com.focusforge

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only screen in Phase 1: live ground truth about this phone's silicon,
 * refreshed every second, exportable as JSON via the share sheet.
 * UI is built in code on purpose — zero dependencies, minimal APK.
 */
class ProbeActivity : Activity() {

    private lateinit var probe: SiliconProbe
    private lateinit var probeText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            probeText.text = probe.toDisplayText(probe.snapshot())
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = SiliconProbe(applicationContext)

        val pad = (16 * resources.displayMetrics.density).toInt()
        probeText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(pad, pad, pad, pad)
        }
        val shareButton = Button(this).apply {
            text = "Share probe JSON"
            setOnClickListener { shareJson() }
        }
        val sessionButton = Button(this).apply {
            text = "Start focus session"
            setOnClickListener {
                startActivity(Intent(this@ProbeActivity, SessionActivity::class.java))
            }
        }
        val llmButton = Button(this).apply {
            text = "LLM smoke test (Phase 5 gate)"
            setOnClickListener {
                startActivity(Intent(this@ProbeActivity, LlmSmokeActivity::class.java))
            }
        }
        val cameraButton = Button(this).apply {
            text = "Open camera probe"
            setOnClickListener {
                startActivity(Intent(this@ProbeActivity, CameraActivity::class.java))
            }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(sessionButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(pad, pad, pad, 0)
                })
                addView(llmButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(pad, pad, pad, 0)
                })
                addView(cameraButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(pad, pad, pad, 0)
                })
                addView(shareButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(pad, pad, pad, 0)
                })
                addView(probeText)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun shareJson() {
        val json = probe.toJson(probe.snapshot()).toString(2)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FocusForge probe JSON")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, "Share probe JSON"))
    }

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
