package com.focusforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.focusforge.core.FaceSample
import com.focusforge.core.RecordingBuilder
import com.focusforge.core.SignalEngine
import com.focusforge.core.SignalSnapshot
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
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

    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService
    private var bitmapBuffer: Bitmap? = null

    // --- signals ---------------------------------------------------------------
    // The engine is only ever touched from the FaceLandmarker result thread; the snapshot
    // it produces is read by the HUD tick on the main thread, hence @Volatile.
    private val signalEngine = SignalEngine()
    @Volatile private var latestSnapshot: SignalSnapshot? = null
    /** Size of the upright frame actually handed to the detector; recorded as-is. */
    @Volatile private var analysisWidth = 0
    @Volatile private var analysisHeight = 0

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
        analysisExecutor = Executors.newSingleThreadExecutor()
        ioExecutor = Executors.newSingleThreadExecutor()
        recordingStore = RecordingStore(this)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        landmarkOverlay = LandmarkOverlayView(this)
        hudText = makeMonoText()
        signalsText = makeMonoText().apply { textSize = 11f }
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
            setUpLandmarkerThenCamera()
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
            setUpLandmarkerThenCamera()
        } else {
            toast("Camera permission is required for the probe")
            finish()
        }
    }

    private fun setUpLandmarkerThenCamera() {
        // Landmarker creation loads the model file; do it off the main thread.
        analysisExecutor.execute {
            try {
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath("models/face_landmarker.task")
                            .build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setResultListener(this::onLandmarkerResult)
                    .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(this, options)
                runOnUiThread { startCamera() }
            } catch (e: Exception) {
                Log.e(TAG, "FaceLandmarker init failed", e)
                runOnUiThread {
                    hudText.text = "FaceLandmarker init FAILED: ${e.message}"
                }
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                // 640x480 per the phase spec — CLAUDE.md forbids silently raising it.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, this::analyzeFrame) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        perf.onFrame()
        val buffer = bitmapBuffer ?: Bitmap.createBitmap(
            image.width, image.height, Bitmap.Config.ARGB_8888).also { bitmapBuffer = it }
        image.use { buffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        // Rotate to upright and mirror horizontally so landmark space matches the
        // (mirrored) front-camera preview the user sees.
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f, buffer.width / 2f, buffer.height / 2f)
        }
        val rotated = Bitmap.createBitmap(buffer, 0, 0, buffer.width, buffer.height, matrix, true)

        faceLandmarker?.detectAsync(
            BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
    }

    private fun onLandmarkerResult(
        result: FaceLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage,
    ) {
        perf.onInference(SystemClock.uptimeMillis() - result.timestampMs())

        // The timestamp we handed detectAsync — the moment the frame was captured, not the
        // moment inference finished. Replaying the recording must reproduce this timeline.
        analysisWidth = input.width
        analysisHeight = input.height
        val sample = SignalMapper.toSample(
            result, result.timestampMs(), input.width, input.height)
        latestSnapshot = signalEngine.update(sample)
        synchronized(recordLock) { recorder?.add(sample) }

        runOnUiThread {
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) landmarkOverlay.clear()
            else landmarkOverlay.setResults(faces[0], input.width, input.height)
        }
    }

    // ------------------------------------------------------------------ recording

    private fun isRecording(): Boolean = synchronized(recordLock) { recorder != null }

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
                imageWidth = analysisWidth,
                imageHeight = analysisHeight,
                mirrored = true,
            )
        }
        recordingStartedUptimeMs = SystemClock.uptimeMillis()
        updateButtons()
        toast("Recording \"${LABELS[labelIndex]}\" — stops automatically after 2 minutes")
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
            logLines.addLast(line)
            while (logLines.size > LOG_LINES) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")

            if (isRecording() &&
                SystemClock.uptimeMillis() - recordingStartedUptimeMs >= RECORD_MAX_MS) {
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
                RECORD_MAX_MS / 1000)
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
                s.blinkRatePerMin?.let { "%.1f/min".format(Locale.US, it) } ?: "not enough data",
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
                append(recordingLine)
        }
    }

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
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        faceLandmarker?.close()
        faceLandmarker = null
    }

    private companion object {
        const val TAG = "PerfHUD"
        const val CAMERA_REQUEST = 1
        const val LOG_LINES = 4

        /** The three labelled sessions the replay tests expect. */
        val LABELS = listOf("focused", "distracted", "drowsy")

        /** Recordings stop themselves so the operator's protocol is impossible to get wrong. */
        const val RECORD_MAX_MS = 120_000L
    }
}
