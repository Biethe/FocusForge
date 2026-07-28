package com.focusforge.core

/**
 * Builds [FaceSample] streams by hand so that every signal can be tested against inputs
 * whose answer we know exactly, with no camera and no recording involved.
 *
 * Geometry mirrors a 480x640 portrait analysis frame with a face roughly centred: each eye
 * is 10% of the frame wide, the two eyes sit at y = 0.45.
 */
object Synthetic {

    const val IMAGE_W = 480
    const val IMAGE_H = 640

    private const val EYE_Y = 0.45
    private const val LEFT_OUTER_X = 0.35
    private const val LEFT_INNER_X = 0.45
    private const val RIGHT_INNER_X = 0.55
    private const val RIGHT_OUTER_X = 0.65

    /**
     * 14 landmarks for a face whose eyes have the given aspect ratio and whose irises sit
     * at [irisRatio] of a half-eye-width from centre (+1 = at the inner-x edge).
     */
    fun landmarks(ear: Double = 0.28, irisRatio: Double = 0.0): Map<Int, Point3> {
        val eyeWidthPx = (LEFT_INNER_X - LEFT_OUTER_X) * IMAGE_W
        // Half the lid separation, converted back to normalized y.
        val halfLidNorm = ear * eyeWidthPx / IMAGE_H / 2.0
        val halfWidthNorm = (LEFT_INNER_X - LEFT_OUTER_X) / 2.0

        val leftCenterX = (LEFT_OUTER_X + LEFT_INNER_X) / 2.0
        val rightCenterX = (RIGHT_INNER_X + RIGHT_OUTER_X) / 2.0

        return buildMap {
            // Left eye: 33 outer, 160/158 upper lid, 133 inner, 153/144 lower lid.
            put(33, Point3(LEFT_OUTER_X, EYE_Y))
            put(160, Point3(0.375, EYE_Y - halfLidNorm))
            put(158, Point3(0.425, EYE_Y - halfLidNorm))
            put(133, Point3(LEFT_INNER_X, EYE_Y))
            put(153, Point3(0.425, EYE_Y + halfLidNorm))
            put(144, Point3(0.375, EYE_Y + halfLidNorm))
            // Right eye: 362 inner, 385/387 upper lid, 263 outer, 373/380 lower lid.
            put(362, Point3(RIGHT_INNER_X, EYE_Y))
            put(385, Point3(0.575, EYE_Y - halfLidNorm))
            put(387, Point3(0.625, EYE_Y - halfLidNorm))
            put(263, Point3(RIGHT_OUTER_X, EYE_Y))
            put(373, Point3(0.625, EYE_Y + halfLidNorm))
            put(380, Point3(0.575, EYE_Y + halfLidNorm))
            // Iris centres.
            put(468, Point3(leftCenterX + irisRatio * halfWidthNorm, EYE_Y))
            put(473, Point3(rightCenterX + irisRatio * halfWidthNorm, EYE_Y))
        }
    }

    fun sample(
        timestampMs: Long,
        closure: Double = 0.0,
        yawDeg: Double = 0.0,
        pitchDeg: Double = 0.0,
        rollDeg: Double = 0.0,
        irisRatio: Double = 0.0,
        jawOpen: Double = 0.0,
        faceVisible: Boolean = true,
        /** Set false to test the eye-aspect-ratio fallback path. */
        withBlendshapes: Boolean = true,
        ear: Double = 0.28,
    ): FaceSample {
        if (!faceVisible) return FaceSample(
            timestampMs = timestampMs,
            faceVisible = false,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )
        val blendshapes = if (withBlendshapes) {
            mapOf(
                Blendshapes.EYE_BLINK_LEFT to closure.toFloat(),
                Blendshapes.EYE_BLINK_RIGHT to closure.toFloat(),
                Blendshapes.JAW_OPEN to jawOpen.toFloat(),
            )
        } else emptyMap()
        return FaceSample(
            timestampMs = timestampMs,
            faceVisible = true,
            blendshapes = blendshapes,
            matrix = HeadPose.matrixOf(yawDeg, pitchDeg, rollDeg),
            landmarks = landmarks(ear = ear, irisRatio = irisRatio),
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )
    }

    /** A stream of samples at a fixed rate; [at] describes the face at each timestamp. */
    fun stream(
        durationMs: Long,
        periodMs: Long = 100L,
        at: (Long) -> FaceSample,
    ): List<FaceSample> = (0..durationMs step periodMs).map { at(it) }

    /** Wraps a stream into a recording, exactly as the app's recorder would. */
    fun recording(label: String, samples: List<FaceSample>): LandmarkRecording {
        val builder = RecordingBuilder(
            label = label,
            device = "synthetic",
            androidVersion = "n/a",
            appVersion = "test",
            startedAtEpochMs = 0L,
            imageWidth = IMAGE_W,
            imageHeight = IMAGE_H,
        )
        samples.forEach { builder.add(it) }
        return builder.build()
    }
}
