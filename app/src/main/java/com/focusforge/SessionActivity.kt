package com.focusforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.focusforge.core.BlinkRateValidity
import com.focusforge.core.CoachLanguage
import com.focusforge.core.CoachMessage
import com.focusforge.core.FaceSample
import com.focusforge.core.FocusScorer
import com.focusforge.core.FocusState
import com.focusforge.core.SessionBuilder
import com.focusforge.core.SessionSummary
import com.focusforge.core.SignalEngine
import com.focusforge.core.SignalSnapshot
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Session screen: the app's actual product surface.
 *
 * A big current focus score, a timeline of the session so far, elapsed time, and the
 * summary numbers — plus an export of the whole thing as JSON through the share sheet.
 *
 * The fusion itself lives in :core ([FocusScorer]); this file only draws it. That split is
 * what lets the score be unit-tested on a plain JVM and replayed from a recording.
 */
class SessionActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var scoreText: TextView
    private lateinit var statusText: TextView
    private lateinit var fatigueBanner: TextView
    private lateinit var sparkline: SparklineView
    private lateinit var elapsedText: TextView
    private lateinit var summaryText: TextView
    private lateinit var exportButton: Button
    private lateinit var coachText: TextView
    private lateinit var languageButton: Button

    private val perf = PerfMonitor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var pipeline: FacePipeline? = null
    private lateinit var ioExecutor: ExecutorService
    private lateinit var sessionStore: SessionStore

    // --- session state ---------------------------------------------------------
    // Everything below is written on the detector thread and read by the 1 Hz UI tick.
    private val signalEngine = SignalEngine()
    private val focusScorer = FocusScorer()
    private val sessionLock = Any()
    private var sessionBuilder: SessionBuilder? = null   // guarded by sessionLock
    private val timelineScores = ArrayList<Int>()        // guarded by sessionLock
    private val timelineFatigue = ArrayList<Boolean>()   // guarded by sessionLock
    private var lastTimelineMs: Long? = null             // guarded by sessionLock

    @Volatile private var latestSnapshot: SignalSnapshot? = null
    @Volatile private var latestState: FocusState? = null
    // Summaries are computed on the detector thread and published here, rather than the UI
    // thread reaching into the engine and the scorer while they are being written. Neither
    // is thread-safe, and a display that is at most one second stale is free.
    @Volatile private var latestSummary: SessionSummary? = null
    private var lastSavedFile: File? = null

    // --- coach -----------------------------------------------------------------
    private var coach: CoachRunner? = null
    @Volatile private var coachStatus: String = "coach — starting…"
    @Volatile private var lastCoachMessage: CoachMessage? = null
    @Volatile private var lastCoachTiming: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ioExecutor = Executors.newSingleThreadExecutor()
        sessionStore = SessionStore(this)
        sessionBuilder = SessionBuilder(
            appVersion = BuildConfig.VERSION_NAME,
            startedAtEpochMs = System.currentTimeMillis(),
            device = runCatching { sessionStore.deviceInfo(SiliconProbe(applicationContext)) }
                .getOrElse { emptyMap() },
        )

        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        // A small preview, so the user can check they are actually in frame. The score is
        // the point of this screen, not the camera, so it gets a strip rather than the wall.
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        scoreText = TextView(this).apply {
            textSize = 84f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "--"
        }
        statusText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            text = "warming up…"
        }
        fatigueBanner = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setPadding(pad, pad, pad, pad)
            text = "Fatigue — time for a break"
            visibility = View.GONE
        }
        sparkline = SparklineView(this)
        elapsedText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            text = "00:00"
        }
        summaryText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad, pad, pad, 0)
        }
        exportButton = Button(this).apply {
            text = "Export session JSON"
            setOnClickListener { exportSession() }
        }
        coachText = TextView(this).apply {
            textSize = 15f
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#11000000"))
            text = "coach — starting…"
        }
        languageButton = Button(this).apply {
            text = "Coach: English"
            setOnClickListener {
                val next = if (coach?.language == CoachLanguage.FRENCH) CoachLanguage.ENGLISH
                           else CoachLanguage.FRENCH
                coach?.language = next
                text = if (next == CoachLanguage.FRENCH) "Coach : français" else "Coach: English"
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(context).apply { addView(previewView) },
                LinearLayout.LayoutParams(MATCH, (110 * density).toInt()))
            addView(fatigueBanner, row())
            addView(scoreText, row())
            addView(statusText, row())
            addView(sparkline, LinearLayout.LayoutParams(MATCH, (110 * density).toInt()).apply {
                setMargins(pad, pad / 2, pad, 0)
            })
            addView(elapsedText, row())
            addView(summaryText, row())
            addView(coachText, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(pad, pad, pad, 0)
            })
            addView(languageButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(pad, pad / 2, pad, 0)
            })
            addView(exportButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(pad, pad / 2, pad, pad)
            })
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun row() = LinearLayout.LayoutParams(MATCH, WRAP)

    @Deprecated("classic permission callback is fine at minSdk 28")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            toast("Camera permission is required for a session")
            finish()
        }
    }

    private fun startCoach() {
        coach = CoachRunner(
            modelFile = File(getExternalFilesDir(null) ?: filesDir, "models/model.gguf"),
            onMessage = { message, timing ->
                lastCoachMessage = message
                lastCoachTiming = timing
                // Coaching messages are rare, so they are stored whole rather than thinned.
                synchronized(sessionLock) { sessionBuilder?.addCoachMessage(message) }
            },
            onStatus = { status -> coachStatus = status },
        ).also { it.start() }
    }

    private fun startPipeline() {
        pipeline = FacePipeline(
            activity = this,
            perf = perf,
            onResult = { sample, _, _ -> onFaceSample(sample) },
            onError = { message -> statusText.text = message },
        ).also { it.start(previewView) }
        startCoach()
    }

    /** Called on the detector thread. */
    private fun onFaceSample(sample: FaceSample) {
        val snapshot = signalEngine.update(sample)
        val state = focusScorer.update(snapshot)
        latestSnapshot = snapshot
        latestState = state
        coach?.onState(state, snapshot)

        synchronized(sessionLock) {
            sessionBuilder?.add(snapshot, state)
            // One timeline point per second, matching the export's own thinning, so the
            // picture on screen and the file describe the same thing.
            val last = lastTimelineMs
            if (last == null || snapshot.timestampMs - last >= TIMELINE_INTERVAL_MS) {
                lastTimelineMs = snapshot.timestampMs
                timelineScores += state.score
                timelineFatigue += state.fatigue
                if (timelineScores.size > TIMELINE_MAX_POINTS) halveTimeline()
                latestSummary = focusScorer.summary(signalEngine.cumulative())
            }
        }
    }

    /**
     * Keeps the whole session visible instead of scrolling a window across it: when the
     * timeline fills up, drop every second point and halve the effective sample rate. A
     * four-hour session still fits, just at coarser resolution.
     */
    private fun halveTimeline() {
        val scores = ArrayList<Int>(timelineScores.size / 2 + 1)
        val flags = ArrayList<Boolean>(timelineFatigue.size / 2 + 1)
        for (i in timelineScores.indices step 2) {
            scores += timelineScores[i]
            flags += timelineFatigue[i]
        }
        timelineScores.clear(); timelineScores += scores
        timelineFatigue.clear(); timelineFatigue += flags
    }

    // ------------------------------------------------------------------ UI tick

    private val uiTick = object : Runnable {
        override fun run() {
            render()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private fun render() {
        val state = latestState
        val snapshot = latestSnapshot
        if (state == null || snapshot == null) {
            statusText.text = "waiting for the camera…"
            return
        }

        scoreText.text = if (state.ready) state.score.toString() else "--"
        scoreText.setTextColor(colorFor(state.score, state.ready))
        statusText.text = when {
            !state.ready -> "warming up — look at your work normally for a few seconds"
            !snapshot.faceVisible -> "no face in frame"
            else -> "%s   ·   attention %d%%  alertness %d%%  steadiness %d%%".format(
                Locale.US, verdict(state.score),
                (state.attention * 100).toInt(),
                (state.alertness * 100).toInt(),
                (state.steadiness * 100).toInt())
        }
        fatigueBanner.visibility = if (state.fatigue) View.VISIBLE else View.GONE

        elapsedText.text = "session %s   ·   %.1f fps   ·   %.0f MB".format(
            Locale.US, clock(state.elapsedMs), perf.fps(), perf.rssMb())

        val (scores, flags) = synchronized(sessionLock) {
            timelineScores.toIntArray() to timelineFatigue.toBooleanArray()
        }
        sparkline.setData(scores, flags)

        val message = lastCoachMessage
        coachText.text = if (message == null) {
            coachStatus
        } else {
            // The measured cost of the message is shown next to it, always. A coaching app
            // that hides its own latency cannot be judged on optimisation.
            "\"${message.text}\"\n\n${message.trigger.lowercase()} · $lastCoachTiming"
        }

        val summary = latestSummary ?: return
        summaryText.text = buildString {
            appendLine("mean %.0f   low %d   high %d   fatigue %.0f%% of session".format(
                Locale.US, summary.meanScore, summary.minScore, summary.maxScore,
                summary.fatigueFraction * 100))
            appendLine("on screen %.0f%%   face seen %.0f%%   PERCLOS %.3f".format(
                Locale.US, summary.signals.gazeOnScreenFraction * 100,
                summary.signals.faceVisibleFraction * 100, summary.signals.perclos))
            // The evidence rule, in the UI: an undersampled blink rate is a floor, not a
            // measurement, so it is not shown as a number (docs/DECISIONS.md 2026-08-02).
            val blinks = if (summary.signals.blinkRateValidity == BlinkRateValidity.FULL_RATE) {
                "blinks %d (%.1f/min)".format(
                    Locale.US, summary.signals.blinkCount, summary.signals.blinkRatePerMin)
            } else {
                "blinks %d+ (rate undersampled at %.1f fps)".format(
                    Locale.US, summary.signals.blinkCount, summary.signals.meanVisionFps)
            }
            append("%s   long closures %d   head %.1f deg".format(
                Locale.US, blinks,
                summary.signals.longClosureCount, summary.signals.meanHeadStabilityDeg))
        }
    }

    private fun verdict(score: Int): String = when {
        score >= 80 -> "focused"
        score >= 60 -> "drifting"
        score >= 40 -> "distracted"
        else -> "off task"
    }

    private fun colorFor(score: Int, ready: Boolean): Int = when {
        !ready -> Color.GRAY
        score >= 80 -> Color.parseColor("#2E7D32")
        score >= 60 -> Color.parseColor("#F9A825")
        else -> Color.parseColor("#C62828")
    }

    private fun clock(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
    }

    // ------------------------------------------------------------------ export

    private fun exportSession() {
        val builder = synchronized(sessionLock) { sessionBuilder }
        if (builder == null || builder.sampleCount == 0) {
            toast("Nothing recorded yet — give it a few seconds")
            return
        }
        val summary = latestSummary
        if (summary == null) {
            toast("Nothing recorded yet — give it a few seconds")
            return
        }
        val session = synchronized(sessionLock) { builder.build(summary) }
        ioExecutor.execute {
            val saved = runCatching { sessionStore.save(session) }
            runOnUiThread {
                saved.onSuccess { file ->
                    lastSavedFile = file
                    Log.i(TAG, "Saved session ${file.absolutePath} (${file.length()} bytes)")
                    startActivity(sessionStore.shareIntent(file))
                }.onFailure { e ->
                    Log.e(TAG, "Saving the session failed", e)
                    toast("Export failed: ${e.message}")
                }
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiTick)
        ioExecutor.shutdown()
        pipeline?.close()
        pipeline = null
        coach?.close()
        coach = null
    }

    private companion object {
        const val TAG = "FocusSession"
        const val CAMERA_REQUEST = 2
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** One timeline point per second, same thinning as the export. */
        const val TIMELINE_INTERVAL_MS = 1_000L

        /** Above this the timeline halves its resolution rather than dropping the past. */
        const val TIMELINE_MAX_POINTS = 900
    }
}
