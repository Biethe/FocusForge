package com.focusforge

import com.focusforge.core.Blendshapes
import com.focusforge.core.FaceSample
import com.focusforge.core.LandmarkIndices
import com.focusforge.core.Point3
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * The one place MediaPipe's output is turned into the numbers the rest of the app sees.
 *
 * This is also where the privacy allow-lists are *enforced* rather than merely documented:
 * of the 478 landmarks we keep the 14 listed in [LandmarkIndices], and of the 52
 * blendshapes we keep the 13 eye/jaw values listed in [Blendshapes]. Everything else —
 * including every expression-related blendshape — is dropped here and never reaches
 * memory, the recorder, or a file. See CLAUDE.md §4.3.
 */
object SignalMapper {

    fun toSample(
        result: FaceLandmarkerResult,
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
    ): FaceSample {
        val face = result.faceLandmarks().firstOrNull()
        if (face.isNullOrEmpty()) {
            return FaceSample(
                timestampMs = timestampMs,
                faceVisible = false,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
        }

        val landmarks = HashMap<Int, Point3>(LandmarkIndices.ALLOWED.size)
        for (index in LandmarkIndices.ALLOWED) {
            val lm = face.getOrNull(index) ?: continue
            landmarks[index] = Point3(lm.x().toDouble(), lm.y().toDouble(), lm.z().toDouble())
        }

        val blendshapes = HashMap<String, Float>(Blendshapes.ALLOWED.size)
        result.faceBlendshapes().orElse(null)?.firstOrNull()?.forEach { category ->
            val name = category.categoryName()
            if (name in Blendshapes.ALLOWED) blendshapes[name] = category.score()
        }

        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()

        return FaceSample(
            timestampMs = timestampMs,
            faceVisible = true,
            blendshapes = blendshapes,
            matrix = matrix,
            landmarks = landmarks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }
}
