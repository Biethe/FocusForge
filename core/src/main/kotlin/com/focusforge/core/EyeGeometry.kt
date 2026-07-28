package com.focusforge.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Eye maths done straight on the landmark points, used both as the fallback when
 * blendshapes are missing and as the source of the horizontal gaze estimate.
 *
 * All landmark coordinates arrive normalized to 0..1 of the image. A 480x640 portrait
 * frame is not square, so a normalized vertical distance is not comparable to a
 * normalized horizontal one — every function here converts to pixels first.
 */
object EyeGeometry {

    /**
     * Eye aspect ratio: (two vertical lid distances averaged) / (eye width).
     * Open eye ~0.25-0.30, closed eye ~0.10-0.15. Returns null if any point is missing.
     *
     * Point order in [eye] is the standard one: outer corner, upper lid x2, inner corner,
     * lower lid x2 — so the vertical pairs are (1,5) and (2,4) and the width is (0,3).
     */
    fun eyeAspectRatio(landmarks: Map<Int, Point3>, eye: IntArray, w: Int, h: Int): Double? {
        val p = eye.map { landmarks[it] ?: return null }
        val v1 = pixelDistance(p[1], p[5], w, h)
        val v2 = pixelDistance(p[2], p[4], w, h)
        val width = pixelDistance(p[0], p[3], w, h)
        if (width <= 0.0) return null
        return (v1 + v2) / (2.0 * width)
    }

    /** Mean EAR of both eyes; null if neither eye has a complete set of points. */
    fun meanEyeAspectRatio(landmarks: Map<Int, Point3>, w: Int, h: Int): Double? {
        val left = eyeAspectRatio(landmarks, LandmarkIndices.LEFT_EYE_EAR, w, h)
        val right = eyeAspectRatio(landmarks, LandmarkIndices.RIGHT_EYE_EAR, w, h)
        return when {
            left != null && right != null -> (left + right) / 2.0
            else -> left ?: right
        }
    }

    /**
     * Horizontal iris offset, averaged over both eyes, as a fraction of half the eye
     * width: 0 = iris centred between the corners, +/-1 = iris at a corner.
     *
     * Measured along the image x axis (not along each eye's own axis) so that both eyes
     * move the same way when the user glances sideways — averaging along the nose-relative
     * axis would cancel the two eyes out. This makes the estimate mildly sensitive to head
     * roll, which is small for someone sitting at a desk; roll is reported separately.
     *
     * Vertical gaze is deliberately not estimated from the iris: the eye opening is only a
     * few pixels tall at 640x480 and desk distance, so the number would be noise. Up/down
     * looking is captured by head pitch instead. Returns null if the iris points are absent.
     *
     * Only the image width is needed — the measurement is a ratio along the x axis.
     */
    fun irisHorizontalRatio(landmarks: Map<Int, Point3>, w: Int): Double? {
        val left = eyeIrisRatio(
            landmarks[LandmarkIndices.LEFT_EYE_OUTER],
            landmarks[LandmarkIndices.LEFT_EYE_INNER],
            landmarks[LandmarkIndices.LEFT_IRIS_CENTER],
            w,
        )
        val right = eyeIrisRatio(
            landmarks[LandmarkIndices.RIGHT_EYE_OUTER],
            landmarks[LandmarkIndices.RIGHT_EYE_INNER],
            landmarks[LandmarkIndices.RIGHT_IRIS_CENTER],
            w,
        )
        return when {
            left != null && right != null -> (left + right) / 2.0
            else -> left ?: right
        }
    }

    private fun eyeIrisRatio(outer: Point3?, inner: Point3?, iris: Point3?, w: Int): Double? {
        if (outer == null || inner == null || iris == null) return null
        val centerX = (outer.x + inner.x) / 2.0 * w
        val halfWidth = abs(outer.x - inner.x) / 2.0 * w
        if (halfWidth <= 0.0) return null
        return ((iris.x * w) - centerX) / halfWidth
    }

    private fun pixelDistance(a: Point3, b: Point3, w: Int, h: Int): Double =
        hypot((a.x - b.x) * w, (a.y - b.y) * h)
}
