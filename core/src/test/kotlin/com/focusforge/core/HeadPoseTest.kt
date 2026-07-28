package com.focusforge.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadPoseTest {

    @Test
    fun `recovers the angles it was built from`() {
        for (yaw in listOf(-30.0, -10.0, 0.0, 15.0, 40.0)) {
            for (pitch in listOf(-20.0, 0.0, 25.0)) {
                for (roll in listOf(-15.0, 0.0, 10.0)) {
                    val o = HeadPose.fromMatrix(HeadPose.matrixOf(yaw, pitch, roll))!!
                    assertEquals(yaw, o.yawDeg, 1e-3, "yaw")
                    assertEquals(pitch, o.pitchDeg, 1e-3, "pitch")
                    assertEquals(roll, o.rollDeg, 1e-3, "roll")
                }
            }
        }
    }

    /**
     * The layout question, settled: the same pose written column-major must read back as
     * the same angles, not as the transpose's (which are genuinely different for a
     * combined rotation — that is why we detect rather than assume).
     */
    @Test
    fun `the same pose reads identically in either matrix layout`() {
        for ((yaw, pitch, roll) in listOf(
            Triple(20.0, -12.0, 7.0),
            Triple(-35.0, 18.0, -9.0),
            Triple(0.0, 0.0, 0.0),
        )) {
            val rowMajor = HeadPose.matrixOf(yaw, pitch, roll)
            val columnMajor = HeadPose.transpose(rowMajor)

            assertEquals(MatrixLayout.ROW_MAJOR, HeadPose.detectLayout(rowMajor))
            assertEquals(MatrixLayout.COLUMN_MAJOR, HeadPose.detectLayout(columnMajor))

            val a = HeadPose.fromMatrix(rowMajor)!!
            val b = HeadPose.fromMatrix(columnMajor)!!
            assertEquals(a.yawDeg, b.yawDeg, 1e-3, "yaw for $yaw/$pitch/$roll")
            assertEquals(a.pitchDeg, b.pitchDeg, 1e-3, "pitch for $yaw/$pitch/$roll")
            assertEquals(a.rollDeg, b.rollDeg, 1e-3, "roll for $yaw/$pitch/$roll")
        }
    }

    /**
     * A rotation-only matrix carries no translation, so the layout cannot be detected.
     * We default to row-major; this test pins how far off the other reading could be, so
     * the fallback is a measured risk rather than an unknown one.
     */
    @Test
    fun `without a translation the fallback reading stays in the same ballpark`() {
        val rotationOnly = HeadPose.matrixOf(20.0, -12.0, 7.0, tx = 0f, ty = 0f, tz = 0f)
        val direct = HeadPose.fromMatrix(rotationOnly)!!
        val transposed = HeadPose.fromMatrix(HeadPose.transpose(rotationOnly))!!
        assertEquals(20.0, direct.yawDeg, 1e-3, "the default reading is still correct")
        assertTrue(
            abs(abs(transposed.yawDeg) - 20.0) < 4.0,
            "the wrong reading would be ${transposed.yawDeg}, not wildly off",
        )
    }

    @Test
    fun `rejects unusable matrices`() {
        assertNull(HeadPose.fromMatrix(null))
        assertNull(HeadPose.fromMatrix(FloatArray(9)))
        assertNull(HeadPose.fromMatrix(FloatArray(16)), "all-zero matrix is not a rotation")
    }

    @Test
    fun `handles the straight-down gimbal case without producing nonsense`() {
        val o = HeadPose.fromMatrix(HeadPose.matrixOf(yawDeg = 90.0, pitchDeg = 0.0, rollDeg = 0.0))!!
        assertTrue(o.yawDeg.isFinite() && o.pitchDeg.isFinite() && o.rollDeg.isFinite())
        assertEquals(90.0, abs(o.yawDeg), 1e-3)
    }
}
