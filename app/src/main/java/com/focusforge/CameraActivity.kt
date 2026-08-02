package com.focusforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.focusforge.core.Blendshapes
import com.focusforge.core.EyeGeometry
import com.focusforge.core.FaceSample
import com.focusforge.core.RecordingBuilder
import com.focusforge.core.SignalEngine
import com.focusforge.core.SignalSnapshot
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera screen: front camera at 640x480 -> MediaPipe FaceLandmarker (LIVE_STREAM,
 * blendshapes + facial transformation matrix on) -> landmark overlay, perf HUD, and, as of
 * Phase 3, the live signal values from :core plus a landmark-stream recorder.
 *
 * Frames live only in memory (privacy rule). What can be written to storage is a recording
 * of *derived numbers* — see [RecordingStore] and com.focusforge.core.LandmarkRecording.
 */
class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var landmarkOverlay: LandmarkOverlayView
    private lateinit var hudText: TextView
    private lateinit var signalsText: TextView
    private lateinit var logText: TextView
    private lateinit var labelButton: Button
    private lateinit var recordButton: Button
    private lateinit var shareButton: Button

    private val perf = PerfMonitor()
    private val hudHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    private var pipeline: FacePipeline? = null
    private lateinit var ioExecutor: ExecutorService

    // --- signals ---------------------------------------------------------------
    // The engine is only ever touched from the FaceLandmarker result thread; the snapshot
    // it produces is read by the HUD tick on the main thread, hence @Volatile.
    private val signalEngine = SignalEngine()
    @Volatile private var latestSnapshot: SignalSnapshot? = null
    // --- eye diagnostic (temporary — see docs/SIGNALS.md §5.1) ------------------
    // PERCLOS sat at exactly 0.000 on the A20e. To tell "the eyes are never scored"
    // from "the averaged score never reaches P80", the panel shows the two raw
    // eyeBlink values, their average, the landmark-derived EAR, and the extremes
    // seen so far. Written on the detector thread, read by the HUD tick.
    private class EyeDebug(
        val left: Double?,
        val right: Double?,
        val avg: Double?,
        val ear: Double?,
        val blendshapeCount: Int,
        val peakLeft: Double,
        val peakRight: Double,
        val peakAvg: Double,
        val earOpen: Double,
        val earClosed: Double,
    )

    @Volatile private var eyeDebug: EyeDebug? = null
    @Volatile private var eyePeakResetRequested = false
    private var peakLeft = 0.0
    private var peakRight = 0.0
    private var peakAvg = 0.0
    private var earOpen = 0.0
    private var earClosed = Double.MAX_VALUE

    // --- recording -------------------------------------------------------------
    private lateinit var recordingStore: RecordingStore
    private val recordLock = Any()
    private var recorder: RecordingBuilder? = null // guarded by recordLock
    private var recordingStartedUptimeMs = 0L
    private var labelIndex = 0
    private var lastSavedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ioExecutor = Executors.newSingleThreadExecutor()
        recordingStore = RecordingStore(this)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        landmarkOverlay = LandmarkOverlayView(this)
        hudText = makeMonoText()
        signalsText = makeMonoText().apply {
            textSize = 11f
            setOnClickListener { resetEyePeaks() }
        }
        logText = makeMonoText().apply { textSize = 10f }

        labelButton = Button(this).apply {
            setOnClickListener {
                if (isRecording()) {
                    toast("Stop the recording before changing the label")
                } else {
                    labelIndex = (labelIndex + 1) % LABELS.size
                    updateButtons()
                }
            }
        }
        recordButton = Button(this).apply {
            setOnClickListener { if (isRecording()) stopRecording(auto = false) else startRecording() }
        }
        shareButton = Button(this).apply {
            text = "Share"
            isEnabled = false
            setOnClickListener {
                lastSavedFile?.let { startActivity(recordingStore.shareIntent(it)) }
            }
        }

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hudText, matchWidth())
            addView(signalsText, matchWidth())
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labelButton, weighted())
            addView(recordButton, weighted())
            addView(shareButton, weighted())
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(logText, matchWidth())
            addView(buttonRow, matchWidth())
        }

        setContentView(FrameLayout(this).apply {
            addView(previewView)
            addView(landmarkOverlay)
            addView(topPanel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottomPanel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        updateButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun weighted() = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun makeMonoText() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    @Deprecated("classic permission callback is fine at minSdk 28")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            toast("Camera permission is required for the probe")
            finish()
        }
    }

    private fun startPipeline() {
        pipeline = FacePipeline(
            activity = this,
            perf = perf,
            onResult = { sample, result, input -> onFaceSample(sample, result, input) },
            onError = { message -> hudText.text = message },
        ).also { it.start(previewView) }
    }

    /** Called on the detector thread. */
    private fun onFaceSample(
        sample: com.focusforge.core.FaceSample,
        result: FaceLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage,
    ) {
        latestSnapshot = signalEngine.update(sample)
        updateEyeDebug(sample)
        synchronized(recordLock) { recorder?.add(sample) }

        runOnUiThread {
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) landmarkOverlay.clear()
            else landmarkOverlay.setResults(faces[0], input.width, input.height)
        }
    }

    // ------------------------------------------------------------------ eye diagnostic

    /**
     * Records what the eye signals actually look like on this device: the two raw
     * eyeBlink blendshapes exactly as MediaPipe reports them, their average (which is
     * what PERCLOS thresholds at 0.80), and the eye aspect ratio computed from the lid
     * landmarks. Extremes are kept because a closure lasts a second and the panel only
     * refreshes once a second — the peak is what tells us how close we get to P80.
     *
     * Diagnostic only: it reads the same allow-listed sample everything else reads and
     * stores nothing to disk.
     */
    private fun updateEyeDebug(sample: FaceSample) {
        if (eyePeakResetRequested) {
            eyePeakResetRequested = false
            peakLeft = 0.0
            peakRight = 0.0
            peakAvg = 0.0
            earOpen = 0.0
            earClosed = Double.MAX_VALUE
        }
        if (!sample.faceVisible) return
        val left = sample.blendshapes[Blendshapes.EYE_BLINK_LEFT]?.toDouble()
        val right = sample.blendshapes[Blendshapes.EYE_BLINK_RIGHT]?.toDouble()
        val avg = when {
            left != null && right != null -> (left + right) / 2.0
            else -> left ?: right
        }
        val ear = EyeGeometry.meanEyeAspectRatio(
            sample.landmarks, sample.imageWidth, sample.imageHeight)

        left?.let { peakLeft = maxOf(peakLeft, it) }
        right?.let { peakRight = maxOf(peakRight, it) }
        avg?.let { peakAvg = maxOf(peakAvg, it) }
        // EAR runs the other way: it *falls* as the lid comes down, so the closed
        // extreme is the minimum and the open extreme is the maximum.
        ear?.let {
            earOpen = maxOf(earOpen, it)
            earClosed = minOf(earClosed, it)
        }

        eyeDebug = EyeDebug(
            left = left, right = right, avg = avg, ear = ear,
            blendshapeCount = sample.blendshapes.size,
            peakLeft = peakLeft, peakRight = peakRight, peakAvg = peakAvg,
            earOpen = earOpen, earClosed = earClosed,
        )
    }

    /**
     * Tapping the panel clears the extremes so the operator can measure one thing at a
     * time (eyes open, then eyes shut). The peaks belong to the detector thread, so the
     * tap only raises a flag and that thread does the clearing.
     */
    private fun resetEyePeaks() {
        eyePeakResetRequested = true
        toast("Eye peaks reset")
    }

    // ------------------------------------------------------------------ recording

    private fun isRecording(): Boolean = synchronized(recordLock) { recorder != null }

    /** The blink probe stops at 30 s; the labelled sessions run the full two minutes. */
    private fun recordingLimitMs(): Long =
        if (LABELS[labelIndex] == BLINK_PROBE_LABEL) BLINK_PROBE_MAX_MS else RECORD_MAX_MS

    private fun startRecording() {
        val snapshot = latestSnapshot
        if (snapshot == null) {
            toast("Wait for the face mesh to appear before recording")
            return
        }
        synchronized(recordLock) {
            recorder = RecordingBuilder(
                label = LABELS[labelIndex],
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                appVersion = BuildConfig.VERSION_NAME,
                startedAtEpochMs = System.currentTimeMillis(),
                imageWidth = pipeline?.analysisWidth ?: 0,
                imageHeight = pipeline?.analysisHeight ?: 0,
                mirrored = true,
            )
        }
        recordingStartedUptimeMs = SystemClock.uptimeMillis()
        updateButtons()
        toast("Recording \"${LABELS[labelIndex]}\" — stops automatically after " +
            "${recordingLimitMs() / 1000} seconds")
    }

    private fun stopRecording(auto: Boolean) {
        val finished = synchronized(recordLock) {
            val current = recorder ?: return
            recorder = null
            current.build()
        }
        updateButtons()
        if (finished.frames.isEmpty()) {
            toast("Nothing recorded")
            return
        }
        ioExecutor.execute {
            val saved = runCatching { recordingStore.save(finished) }
            runOnUiThread {
                saved.onSuccess { file ->
                    lastSavedFile = file
                    shareButton.isEnabled = true
                    val why = if (auto) "2 minutes reached" else "stopped"
                    toast("$why — saved ${finished.frames.size} frames to ${file.name}")
                    Log.i(TAG, "Saved recording ${file.absolutePath} (${file.length()} bytes)")
                }.onFailure { e ->
                    Log.e(TAG, "Saving the recording failed", e)
                    toast("Saving failed: ${e.message}")
                }
            }
        }
    }

    private fun updateButtons() {
        val recording = isRecording()
        labelButton.text = "Label:\n${LABELS[labelIndex]}"
        labelButton.isEnabled = !recording
        recordButton.text = if (recording) "STOP" else "REC"
    }

    // ------------------------------------------------------------------ HUD

    private val hudTick = object : Runnable {
        override fun run() {
            val line = "%s  fps=%4.1f  infer=%3.0f ms  rss=%.0f MB".format(
                Locale.US,
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                perf.fps(), perf.avgInferenceMs(), perf.rssMb())
            hudText.text = "camera %4.1f fps | landmarks %3.0f ms avg | RSS %.0f MB".format(
                Locale.US, perf.fps(), perf.avgInferenceMs(), perf.rssMb())
            signalsText.text = describeSignals()
            Log.i(TAG, line)
            // Same numbers in logcat, so `adb logcat -s PerfHUD` is a second way to read
            // the diagnostic if the panel is hard to photograph.
            eyeDebug?.let {
                Log.i(TAG, "eyes L=%s R=%s avg=%s ear=%s bs=%d peakAvg=%.2f".format(
                    Locale.US, f2(it.left), f2(it.right), f2(it.avg), f2(it.ear),
                    it.blendshapeCount, it.peakAvg))
            }
            logLines.addLast(line)
            while (logLines.size > LOG_LINES) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")

            if (isRecording() &&
                SystemClock.uptimeMillis() - recordingStartedUptimeMs >= recordingLimitMs()) {
                stopRecording(auto = true)
            }
            hudHandler.postDelayed(this, 1000L)
        }
    }

    /** The live debug view the phase asks for: every signal, in plain words. */
    private fun describeSignals(): String {
        val s = latestSnapshot ?: return "signals   waiting for the first face…"
        val recordingLine = synchronized(recordLock) {
            val r = recorder
            if (r == null) "record    idle — label \"${LABELS[labelIndex]}\""
            else "record    ● %s  %d frames  %.0f s / %d s".format(
                Locale.US, LABELS[labelIndex], r.frameCount,
                (SystemClock.uptimeMillis() - recordingStartedUptimeMs) / 1000.0,
                recordingLimitMs() / 1000)
        }
        return buildString {
            appendLine(if (s.calibrated) "baseline  calibrated" else "baseline  calibrating…")
            appendLine("face      %-4s  eyes %s".format(
                Locale.US,
                if (s.faceVisible) "YES" else "NO",
                s.eyeClosure?.let { "%.2f %s".format(Locale.US, it, if (s.eyesClosedNow) "(closed)" else "(open)") }
                    ?: "n/a"))
            appendLine("blinks    %-4d  rate %s  long closures %d".format(
                Locale.US, s.blinkCount,
                when {
                    s.blinkRateValidity != com.focusforge.core.BlinkRateValidity.FULL_RATE ->
                        "undersampled (%.1f fps)".format(Locale.US, s.visionFps)
                    else -> s.blinkRatePerMin?.let { "%.1f/min".format(Locale.US, it) }
                        ?: "not enough data"
                },
                s.longClosureCount))
            appendLine("PERCLOS   %.3f  over %.0f s of measurable time".format(
                Locale.US, s.perclos, s.perclosCoverageMs / 1000.0))
            appendLine("gaze      %-4s  on screen %.3f of the last minute".format(
                Locale.US, if (s.gazeOnScreen) "ON" else "OFF", s.gazeOnScreenFraction))
            appendLine("head      yaw %s  pitch %s  iris %s".format(
                Locale.US, deg(s.headYawDevDeg), deg(s.headPitchDevDeg),
                s.irisHorizontalDev?.let { "%+.2f".format(Locale.US, it) } ?: "n/a"))
            appendLine("steadines %.1f deg spread  %s".format(
                Locale.US, s.headStabilityDeg, if (s.headStable) "STABLE" else "MOVING"))
            appendLine("yawns     %d".format(Locale.US, s.yawnCount))
            append(describeEyeDebug())
            appendLine(recordingLine)
        }
    }

    /**
     * The temporary eye diagnostic, two lines. Reading them:
     *
     *  - `bs 0` means MediaPipe returned no blendshapes at all on this device and the
     *    whole eye path is running on the EAR fallback (or on nothing).
     *  - `avg` is the number PERCLOS compares against 0.80. If `peak avg` stays below
     *    0.80 while the eyes are genuinely shut, the threshold is wrong for this device,
     *    not the pipeline.
     *  - `EAR` open/closed shows whether the landmark path separates the two states,
     *    i.e. whether it is a usable second opinion.
     */
    private fun describeEyeDebug(): String {
        val d = eyeDebug ?: return "eyes raw  no face seen yet\n"
        return buildString {
            appendLine("eyes raw  L %s  R %s  avg %s  EAR %s  bs %d".format(
                Locale.US, f2(d.left), f2(d.right), f2(d.avg), f2(d.ear), d.blendshapeCount))
            appendLine("eyes ref  open EAR %.3f (calibrated)".format(
                Locale.US, latestSnapshot?.earOpen ?: 0.0))
            appendLine("eyes peak L %.2f  R %.2f  avg %.2f (P80=0.80)  EAR %s..%s  [tap=reset]".format(
                Locale.US, d.peakLeft, d.peakRight, d.peakAvg,
                f2(if (d.earClosed == Double.MAX_VALUE) null else d.earClosed), f2(d.earOpen)))
        }
    }

    private fun f2(value: Double?): String =
        value?.let { "%.2f".format(Locale.US, it) } ?: " n/a"

    private fun deg(value: Double?): String =
        value?.let { "%+.0f".format(Locale.US, it) } ?: "n/a"

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        hudHandler.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        hudHandler.removeCallbacks(hudTick)
        // Leaving the screen ends a recording rather than silently capturing a gap.
        if (isRecording()) stopRecording(auto = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        pipeline?.close()
        pipeline = null
    }

    private companion object {
        const val TAG = "PerfHUD"
        const val CAMERA_REQUEST = 1
        const val LOG_LINES = 4

        /**
         * The three labelled sessions the replay tests expect, plus the blink probe — a
         * short capture used to measure closure amplitude directly (docs/SIGNALS.md §16.2).
         * A recording already stores raw blendshapes *and* lid landmarks at full frame
         * rate, so it is the full-rate debug capture; no separate logging path is needed.
         */
        val LABELS = listOf("focused", "distracted", "drowsy", "blinkprobe")

        /** Recordings stop themselves so the operator's protocol is impossible to get wrong. */
        const val RECORD_MAX_MS = 120_000L

        /** The blink probe is a 30 s exercise, not a 2-minute session. */
        const val BLINK_PROBE_MAX_MS = 30_000L
        const val BLINK_PROBE_LABEL = "blinkprobe"
    }
}
