package com.focusforge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EyeGeometryTest {

    @Test
    fun `eye aspect ratio matches the geometry it was built from`() {
        for (target in listOf(0.10, 0.18, 0.28, 0.35)) {
            val ear = EyeGeometry.meanEyeAspectRatio(
                Synthetic.landmarks(ear = target), Synthetic.IMAGE_W, Synthetic.IMAGE_H,
            )!!
            assertEquals(target, ear, 1e-6)
        }
    }

    @Test
    fun `closed eyes measure a lower aspect ratio than open eyes`() {
        val open = EyeGeometry.meanEyeAspectRatio(
            Synthetic.landmarks(ear = 0.30), Synthetic.IMAGE_W, Synthetic.IMAGE_H,
        )!!
        val closed = EyeGeometry.meanEyeAspectRatio(
            Synthetic.landmarks(ear = 0.11), Synthetic.IMAGE_W, Synthetic.IMAGE_H,
        )!!
        assertTrue(closed < open, "closed=$closed should be below open=$open")
    }

    @Test
    fun `iris ratio is zero when centred and tracks the offset both ways`() {
        val centred = EyeGeometry.irisHorizontalRatio(Synthetic.landmarks(), Synthetic.IMAGE_W)!!
        assertEquals(0.0, centred, 1e-9)

        val right = EyeGeometry.irisHorizontalRatio(
            Synthetic.landmarks(irisRatio = 0.6), Synthetic.IMAGE_W,
        )!!
        assertEquals(0.6, right, 1e-6)

        val left = EyeGeometry.irisHorizontalRatio(
            Synthetic.landmarks(irisRatio = -0.6), Synthetic.IMAGE_W,
        )!!
        assertEquals(-0.6, left, 1e-6)
    }

    @Test
    fun `returns null rather than guessing when points are missing`() {
        assertNull(EyeGeometry.meanEyeAspectRatio(emptyMap(), Synthetic.IMAGE_W, Synthetic.IMAGE_H))
        assertNull(EyeGeometry.irisHorizontalRatio(emptyMap(), Synthetic.IMAGE_W))
    }

    @Test
    fun `one usable eye is enough`() {
        val onlyLeft = Synthetic.landmarks().filterKeys {
            it in LandmarkIndices.LEFT_EYE_EAR.toList() + LandmarkIndices.LEFT_IRIS_CENTER
        }
        assertTrue(
            EyeGeometry.meanEyeAspectRatio(onlyLeft, Synthetic.IMAGE_W, Synthetic.IMAGE_H) != null,
        )
        assertTrue(EyeGeometry.irisHorizontalRatio(onlyLeft, Synthetic.IMAGE_W) != null)
    }
}
