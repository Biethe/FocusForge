package com.focusforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phase 2 screen: front camera at 640x480 -> MediaPipe FaceLandmarker (LIVE_STREAM,
 * blendshapes + facial transformation matrix on) -> landmark overlay + perf HUD.
 * Frames live only in memory (privacy rule); nothing is ever written or sent.
 */
class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var landmarkOverlay: LandmarkOverlayView
    private lateinit var hudText: TextView
    private lateinit var logText: TextView

    private val perf = PerfMonitor()
    private val hudHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var analysisExecutor: ExecutorService
    private var bitmapBuffer: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        landmarkOverlay = LandmarkOverlayView(this)
        hudText = makeMonoText(Gravity.TOP)
        logText = makeMonoText(Gravity.BOTTOM).apply { textSize = 10f }

        setContentView(FrameLayout(this).apply {
            addView(previewView)
            addView(landmarkOverlay)
            addView(hudText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(logText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setUpLandmarkerThenCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun makeMonoText(gravity: Int) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        this.gravity = gravity
    }

    @Deprecated("classic permission callback is fine at minSdk 28")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setUpLandmarkerThenCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for the probe", Toast.LENGTH_LONG).show()
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

    private fun onLandmarkerResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        perf.onInference(SystemClock.uptimeMillis() - result.timestampMs())
        runOnUiThread {
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) landmarkOverlay.clear()
            else landmarkOverlay.setResults(faces[0], input.width, input.height)
        }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            val line = "%s  fps=%4.1f  infer=%3.0f ms  rss=%.0f MB".format(
                Locale.US,
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                perf.fps(), perf.avgInferenceMs(), perf.rssMb())
            hudText.text = "camera %4.1f fps | landmarks %3.0f ms avg | RSS %.0f MB".format(
                Locale.US, perf.fps(), perf.avgInferenceMs(), perf.rssMb())
            Log.i(TAG, line)
            logLines.addLast(line)
            while (logLines.size > LOG_LINES) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")
            hudHandler.postDelayed(this, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        hudHandler.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        hudHandler.removeCallbacks(hudTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        faceLandmarker?.close()
        faceLandmarker = null
    }

    private companion object {
        const val TAG = "PerfHUD"
        const val CAMERA_REQUEST = 1
        const val LOG_LINES = 8
    }
}
