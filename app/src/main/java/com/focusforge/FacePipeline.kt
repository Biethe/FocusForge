package com.focusforge

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front camera → MediaPipe FaceLandmarker → [FaceSample], in one place.
 *
 * Both screens that need a face need exactly this, and an earlier version of the app had it
 * written out twice — which is how the two copies quietly drift apart. The camera screen
 * additionally wants the raw landmarks to draw the mesh, so [onResult] hands those over
 * too; the session screen ignores them.
 *
 * PRIVACY RULE (CLAUDE.md §4.3): frames live in memory for the duration of one detection
 * and are then dropped. Nothing here writes an image anywhere.
 */
class FacePipeline(
    private val activity: ComponentActivity,
    private val perf: PerfMonitor,
    /** Called on the detector thread, not the main thread. */
    private val onResult: (FaceSample, FaceLandmarkerResult, MPImage) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var faceLandmarker: FaceLandmarker? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var bitmapBuffer: Bitmap? = null
    private var previewView: PreviewView? = null

    /**
     * When true, frames are drained but not sent to the detector.
     *
     * The camera keeps running — stopping and restarting it would take longer than the pause
     * — but MediaPipe does not, which hands its share of the CPU to whatever needs it more.
     * Phase 6's governor will own this; for now the coach raises it while generating.
     */
    @Volatile var paused = false

    /** Size of the upright frame actually handed to the detector. */
    @Volatile var analysisWidth = 0
        private set
    @Volatile var analysisHeight = 0
        private set

    /**
     * Loads the model off the main thread, then binds the camera. [previewView] may be null
     * for a screen that shows no preview at all.
     */
    fun start(previewView: PreviewView?) {
        this.previewView = previewView
        analysisExecutor.execute {
            try {
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET)
                            .build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setResultListener(this::onLandmarkerResult)
                    .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(activity, options)
                activity.runOnUiThread { bindCamera() }
            } catch (e: Exception) {
                Log.e(TAG, "FaceLandmarker init failed", e)
                activity.runOnUiThread { onError("FaceLandmarker init FAILED: ${e.message}") }
            }
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = previewView?.let { view ->
                Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
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
            val useCases = listOfNotNull(preview, analysis).toTypedArray()
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (paused) {
            // Close the proxy without doing any work: holding it would stall the camera.
            image.close()
            return
        }
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

    private fun onLandmarkerResult(result: FaceLandmarkerResult, input: MPImage) {
        perf.onInference(SystemClock.uptimeMillis() - result.timestampMs())
        analysisWidth = input.width
        analysisHeight = input.height
        // The timestamp we handed detectAsync — the moment the frame was captured, not the
        // moment inference finished. Replaying a recording must reproduce this timeline.
        val sample = SignalMapper.toSample(
            result, result.timestampMs(), input.width, input.height)
        onResult(sample, result, input)
    }

    fun close() {
        analysisExecutor.shutdown()
        faceLandmarker?.close()
        faceLandmarker = null
    }

    private companion object {
        const val TAG = "FacePipeline"
        const val MODEL_ASSET = "models/face_landmarker.task"
    }
}
