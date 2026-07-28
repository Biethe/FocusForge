package com.focusforge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max

/**
 * Draws the face landmarks on top of the camera preview.
 * The preview uses FILL_CENTER (center-crop), so we scale by max(view/image)
 * and center the overflow — same math as the official MediaPipe sample overlay.
 */
class LandmarkOverlayView(context: Context) : View(context) {

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 6f
    }

    fun setResults(landmarks: List<NormalizedLandmark>, imageWidth: Int, imageHeight: Int) {
        this.landmarks = landmarks
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun clear() {
        landmarks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f
        for (lm in landmarks) {
            canvas.drawCircle(
                lm.x() * imageWidth * scale + offsetX,
                lm.y() * imageHeight * scale + offsetY,
                3f,
                pointPaint,
            )
        }
    }
}
