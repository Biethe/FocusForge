package com.focusforge.core

/**
 * One frame of *derived numbers* about a face — never an image.
 *
 * PRIVACY RULE (CLAUDE.md §4.3): this is the widest data structure that ever leaves the
 * camera pipeline. It carries a handful of named eye/jaw values, a head-orientation matrix
 * and a short list of eye-contour points. No pixels, no full face mesh, no identity.
 */
data class FaceSample(
    /** Milliseconds on a monotonic clock. Only differences between samples are used. */
    val timestampMs: Long,
    /** False when the detector found no face in this frame (user away, turned around, dark). */
    val faceVisible: Boolean,
    /** Allow-listed blendshape scores in 0..1, keyed by [Blendshapes] names. Empty if no face. */
    val blendshapes: Map<String, Float> = emptyMap(),
    /**
     * MediaPipe's 4x4 facial transformation matrix, 16 floats, or null if unavailable.
     * Layout (row- vs column-major) is an assumption — see [HeadPose] for why it cannot
     * change any of our signals.
     */
    val matrix: FloatArray? = null,
    /** Allow-listed landmarks keyed by their MediaPipe index (see [LandmarkIndices]). */
    val landmarks: Map<Int, Point3> = emptyMap(),
    /** Image size the normalized landmark coordinates refer to; used to undo aspect distortion. */
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
) {
    // data class + FloatArray needs hand-written equals/hashCode to behave sanely.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceSample) return false
        return timestampMs == other.timestampMs &&
            faceVisible == other.faceVisible &&
            blendshapes == other.blendshapes &&
            (matrix?.toList() ?: emptyList<Float>()) == (other.matrix?.toList() ?: emptyList<Float>()) &&
            landmarks == other.landmarks &&
            imageWidth == other.imageWidth &&
            imageHeight == other.imageHeight
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + faceVisible.hashCode()
        result = 31 * result + blendshapes.hashCode()
        result = 31 * result + (matrix?.toList()?.hashCode() ?: 0)
        result = 31 * result + landmarks.hashCode()
        result = 31 * result + imageWidth
        result = 31 * result + imageHeight
        return result
    }
}

/** A landmark in MediaPipe's normalized image space: x and y in 0..1, z relative depth. */
data class Point3(val x: Double, val y: Double, val z: Double = 0.0)

/**
 * The only blendshapes we ever read or store.
 *
 * MediaPipe emits 52 ARKit-style blendshapes, many of which describe facial expression
 * (brows, mouth corners, cheeks). We deliberately allow-list eye and jaw values only:
 * they are the behavioural signals a fatigue coach needs, and keeping the rest out of
 * memory and out of recordings is what makes "we do not do emotion inference" a fact
 * about the code rather than a promise (CLAUDE.md §4.3).
 *
 * "Left"/"Right" are the detector's labels in *image* space. Our maths averages the two
 * eyes, so it does not matter whether the preview is mirrored.
 */
object Blendshapes {
    const val EYE_BLINK_LEFT = "eyeBlinkLeft"
    const val EYE_BLINK_RIGHT = "eyeBlinkRight"
    const val EYE_SQUINT_LEFT = "eyeSquintLeft"
    const val EYE_SQUINT_RIGHT = "eyeSquintRight"
    const val EYE_LOOK_IN_LEFT = "eyeLookInLeft"
    const val EYE_LOOK_OUT_LEFT = "eyeLookOutLeft"
    const val EYE_LOOK_UP_LEFT = "eyeLookUpLeft"
    const val EYE_LOOK_DOWN_LEFT = "eyeLookDownLeft"
    const val EYE_LOOK_IN_RIGHT = "eyeLookInRight"
    const val EYE_LOOK_OUT_RIGHT = "eyeLookOutRight"
    const val EYE_LOOK_UP_RIGHT = "eyeLookUpRight"
    const val EYE_LOOK_DOWN_RIGHT = "eyeLookDownRight"
    const val JAW_OPEN = "jawOpen"

    /** Recording order. Anything not in this list is dropped before it reaches [FaceSample]. */
    val ALLOWED: List<String> = listOf(
        EYE_BLINK_LEFT, EYE_BLINK_RIGHT,
        EYE_SQUINT_LEFT, EYE_SQUINT_RIGHT,
        EYE_LOOK_IN_LEFT, EYE_LOOK_OUT_LEFT, EYE_LOOK_UP_LEFT, EYE_LOOK_DOWN_LEFT,
        EYE_LOOK_IN_RIGHT, EYE_LOOK_OUT_RIGHT, EYE_LOOK_UP_RIGHT, EYE_LOOK_DOWN_RIGHT,
        JAW_OPEN,
    )
}

/**
 * The only face-mesh points we ever read or store: 12 eye-contour points (for the eye
 * aspect ratio) plus the 2 iris centres (for horizontal gaze). 14 of MediaPipe's 478.
 *
 * Indices are from the FaceLandmarker 478-point mesh (the 468-point face mesh plus 10
 * iris points, 468-472 left iris and 473-477 right iris).
 */
object LandmarkIndices {
    /** Left eye, in eye-aspect-ratio order: outer corner, 2 upper lid, inner corner, 2 lower lid. */
    val LEFT_EYE_EAR = intArrayOf(33, 160, 158, 133, 153, 144)

    /** Right eye, same order: inner corner, 2 upper lid, outer corner, 2 lower lid. */
    val RIGHT_EYE_EAR = intArrayOf(362, 385, 387, 263, 373, 380)

    const val LEFT_EYE_OUTER = 33
    const val LEFT_EYE_INNER = 133
    const val RIGHT_EYE_INNER = 362
    const val RIGHT_EYE_OUTER = 263

    const val LEFT_IRIS_CENTER = 468
    const val RIGHT_IRIS_CENTER = 473

    /** Recording order. Anything not in this list is dropped before it reaches [FaceSample]. */
    val ALLOWED: List<Int> =
        (LEFT_EYE_EAR.toList() + RIGHT_EYE_EAR.toList() +
            listOf(LEFT_IRIS_CENTER, RIGHT_IRIS_CENTER)).distinct()
}
