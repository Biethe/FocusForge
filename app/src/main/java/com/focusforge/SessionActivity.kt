package com.focusforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import dev.aarchmage.DeviceProfile
import dev.aarchmage.Governor
import dev.aarchmage.GovernorDecision
import dev.aarchmage.toJson
import dev.aarchmage.WindowMeasurement
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

    /**
     * The file this session is being written to as it runs.
     *
     * A session used to reach storage only when someone pressed Export, which meant a run
     * nobody thought to export simply did not exist afterwards — and the operator had to
     * remember to press a button at the end of every session for the evidence to survive.
     * It is now written continuously to one file, so pulling the device always yields what
     * actually happened.
     */
    private var autosaveFile: File? = null
    private var lastAutosaveMs = 0L

    // --- governor ---------------------------------------------------------------
    // Only present once the device has been profiled. Without a profile there is nothing to
    // govern against, and inventing a configuration would defeat the point of measuring one.
    private var governor: Governor? = null
    private var deviceProfile: DeviceProfile? = null
    @Volatile private var governorLine: String = ""
    private val governorLog = mutableListOf<GovernorDecision>()   // guarded by sessionLock
    private var windowStartedMs = 0L
    private var windowFrames = 0

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
        // Scrollable, because the coach's message is variable-height and pushed the export
        // button off the bottom of the screen on the operator's device (2026-08-02). A
        // control that exists but cannot be reached is a control that does not exist.
        setContentView(ScrollView(this).apply { addView(root) })

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

    /**
     * Puts the device profile in charge, if one has been measured.
     *
     * The profile decides how many threads the coach gets and what frame budget the vision
     * loop starts with; the governor then holds both to the contract while the session runs.
     */
    private fun startGovernor() {
        val profile = ProfileStore(this).load()
        deviceProfile = profile
        if (profile == null) {
            governorLine = "no device profile — run the self-benchmark on the LLM screen"
            return
        }
        governor = Governor(profile)
        synchronized(sessionLock) { sessionBuilder?.setDeviceProfile(profile.toJson()) }
        pipeline?.targetFps = profile.chosen.visionFpsBudget
        governorLine = "profile: ${profile.chosen.threads} threads, " +
            "%.1f fps budget".format(Locale.US, profile.chosen.visionFpsBudget)
    }

    /**
     * One governor window: hand it what was measured and act on what it decides.
     *
     * Called on the UI tick rather than per frame — the governor reasons about windows, and
     * feeding it every frame would only give it more chances to be indecisive.
     */
    private fun updateGovernor(state: FocusState, summary: SessionSummary) {
        val g = governor ?: return
        if (windowStartedMs == 0L) windowStartedMs = SystemClock.uptimeMillis()
        val windowMs = SystemClock.uptimeMillis() - windowStartedMs
        if (windowMs < GOVERNOR_WINDOW_MS) return
        windowStartedMs = SystemClock.uptimeMillis()

        val lastMessage = lastCoachMessage
        val measurement = WindowMeasurement(
            elapsedMs = state.elapsedMs,
            // Only report a latency if one was actually produced in this session; a stale or
            // absent number is not evidence and the governor treats null as "did not look".
            ttftMs = lastMessage?.ttftMs,
            decodeTokPerSec = lastMessage?.tokensPerSecond,
            visionFps = perf.fps(),
            rssBytes = (perf.rssMb() * 1024 * 1024).toLong(),
        )
        val decision = g.observe(measurement)
        if (decision != null) {
            synchronized(sessionLock) {
                governorLog += decision
                // Straight into the export: a decision nobody can audit afterwards is the
                // same defect as a benchmark number with no evidence behind it.
                sessionBuilder?.addGovernorDecision(decision.toJson())
            }
            if (decision.applied && decision.knob == "visionFpsBudget") {
                pipeline?.targetFps = g.current.visionFpsBudget
            }
            Log.i(TAG, "governor: ${decision.knob} ${decision.from} -> ${decision.to} " +
                "(applied=${decision.applied}) because ${decision.trigger?.summary ?: "recovery"}")
        }
        governorLine = buildString {
            append("governor %.1f fps".format(Locale.US, g.current.visionFpsBudget))
            append(" · ${g.decisions.size} decision${if (g.decisions.size == 1) "" else "s"}")
            g.decisions.lastOrNull()?.let {
                append(" · last: ${it.knob} ${it.from}→${it.to}")
                if (!it.applied) append(" (logged)")
            }
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
            // The vision loop and the LLM share 2 A73s and 6 A53s. Running MediaPipe every
            // frame while the model processes a prompt cost measurable latency on the phone
            // (see docs/DECISIONS.md 2026-08-02). Standing the detector down for the few
            // seconds a message takes is the honest fix, and it is a preview of the Phase 6
            // governor's fps-budget knob — here as a fixed rule, there as a measured decision.
            setVisionPaused = { paused -> pipeline?.paused = paused },
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
        startGovernor()
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
            autosave()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Writes the session as it goes, on the IO thread.
     *
     * Every [AUTOSAVE_INTERVAL_MS], not every tick: a session of a few hundred rows is small,
     * but writing it once a second for an hour would be an hour of pointless flash traffic on
     * a phone whose battery is one of the things being measured.
     */
    private fun autosave(force: Boolean = false) {
        val builder = synchronized(sessionLock) { sessionBuilder } ?: return
        if (builder.sampleCount == 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAutosaveMs < AUTOSAVE_INTERVAL_MS) return
        lastAutosaveMs = now

        val summary = latestSummary ?: return
        val session = synchronized(sessionLock) { builder.build(summary) }
        val target = autosaveFile ?: sessionStore.newSessionFile().also { autosaveFile = it }
        ioExecutor.execute {
            runCatching { sessionStore.save(session, target) }
                .onSuccess { lastSavedFile = it }
                .onFailure { Log.e(TAG, "autosave failed", it) }
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
        updateGovernor(state, summary)
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
            appendLine("%s   long closures %d   head %.1f deg".format(
                Locale.US, blinks,
                summary.signals.longClosureCount, summary.signals.meanHeadStabilityDeg))
            append(governorLine)
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
        val target = autosaveFile ?: sessionStore.newSessionFile().also { autosaveFile = it }
        ioExecutor.execute {
            // The same file the autosave has been writing — exporting shares it rather than
            // producing a second, near-identical copy beside it.
            val saved = runCatching { sessionStore.save(session, target) }
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
        // Leaving the screen is exactly when a session would otherwise be lost.
        autosave(force = true)
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

        /** How often the session is written to storage while it runs. */
        const val AUTOSAVE_INTERVAL_MS = 20_000L

        /**
         * How long the governor looks at before deciding anything.
         *
         * Thirty seconds, and the governor additionally requires two consecutive violating
         * windows before it acts — so nothing changes on less than a minute of evidence.
         */
        const val GOVERNOR_WINDOW_MS = 30_000L
    }
}
