package com.focusforge.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Head orientation in degrees. Yaw = turn left/right, pitch = nod up/down, roll = tilt. */
data class Orientation(val yawDeg: Double, val pitchDeg: Double, val rollDeg: Double)

/** How the 16 floats of a 4x4 matrix are laid out in memory. */
enum class MatrixLayout { ROW_MAJOR, COLUMN_MAJOR }

/**
 * Turns MediaPipe's 4x4 facial transformation matrix into yaw/pitch/roll.
 *
 * ## The layout question, settled at runtime
 *
 * MediaPipe hands back 16 floats and its Java API does not document whether they are
 * row-major or column-major. Reading a rotation the wrong way round is its transpose,
 * which for a combined yaw/pitch/roll is *not* simply the same angles negated — so we
 * cannot wave the question away.
 *
 * We do not have to guess. The matrix is a rigid transform: it carries a translation (the
 * head's position in front of the camera, which is never zero) in its fourth column if it
 * is row-major, and in its fourth row if it is column-major. Whichever of those two triples
 * is non-zero tells us the layout, per frame, with no assumption. [detectLayout] does that;
 * only a matrix with no translation at all is ambiguous, and then we default to row-major.
 */
object HeadPose {

    /** Rotation matrices are orthonormal; below this the extraction is degenerate. */
    private const val GIMBAL_EPSILON = 1e-6

    /** Below this a translation triple counts as "all zeros" for layout detection. */
    private const val TRANSLATION_EPSILON = 1e-4

    /**
     * Row-major puts translation at indices 3, 7, 11; column-major at 12, 13, 14.
     * The head is always some distance from the camera, so exactly one of them is non-zero.
     */
    fun detectLayout(m: FloatArray): MatrixLayout {
        val asRowMajor = abs(m[3]) + abs(m[7]) + abs(m[11])
        val asColumnMajor = abs(m[12]) + abs(m[13]) + abs(m[14])
        if (asRowMajor < TRANSLATION_EPSILON && asColumnMajor < TRANSLATION_EPSILON) {
            return MatrixLayout.ROW_MAJOR // no translation to go on; rotation-only test input
        }
        return if (asColumnMajor > asRowMajor) MatrixLayout.COLUMN_MAJOR else MatrixLayout.ROW_MAJOR
    }

    fun fromMatrix(m: FloatArray?): Orientation? {
        if (m == null || m.size < 16) return null
        val layout = detectLayout(m)
        // Upper-left 3x3, read according to the detected layout.
        fun r(row: Int, col: Int): Double = when (layout) {
            MatrixLayout.ROW_MAJOR -> m[row * 4 + col].toDouble()
            MatrixLayout.COLUMN_MAJOR -> m[col * 4 + row].toDouble()
        }
        val r00 = r(0, 0)
        val r10 = r(1, 0); val r11 = r(1, 1); val r12 = r(1, 2)
        val r20 = r(2, 0); val r21 = r(2, 1); val r22 = r(2, 2)

        // Reject anything that is not a rotation (an all-zero matrix from a dropped frame).
        val firstColumnNorm = sqrt(r00 * r00 + r10 * r10 + r20 * r20)
        if (!firstColumnNorm.isFinite() || firstColumnNorm < GIMBAL_EPSILON) return null

        // Standard X-Y-Z Euler extraction: pitch about X, yaw about Y, roll about Z.
        val sy = sqrt(r00 * r00 + r10 * r10)
        val pitch: Double
        val yaw: Double
        val roll: Double
        if (sy > GIMBAL_EPSILON) {
            pitch = atan2(r21, r22)
            yaw = atan2(-r20, sy)
            roll = atan2(r10, r00)
        } else {
            // Looking almost straight up or down: roll and yaw are not separable.
            pitch = atan2(-r12, r11)
            yaw = asin(-r20.coerceIn(-1.0, 1.0))
            roll = 0.0
        }
        if (!pitch.isFinite() || !yaw.isFinite() || !roll.isFinite()) return null
        return Orientation(
            yawDeg = Math.toDegrees(yaw),
            pitchDeg = Math.toDegrees(pitch),
            rollDeg = Math.toDegrees(roll),
        )
    }

    /**
     * Builds a row-major 4x4 with the given rotation and translation. Used by the tests.
     * The default translation stands in for a head roughly 30 cm from the camera, which is
     * what makes [detectLayout] able to tell the two layouts apart.
     */
    fun matrixOf(
        yawDeg: Double,
        pitchDeg: Double,
        rollDeg: Double,
        tx: Float = 1.5f,
        ty: Float = -2.0f,
        tz: Float = -30.0f,
    ): FloatArray {
        val x = Math.toRadians(pitchDeg)
        val y = Math.toRadians(yawDeg)
        val z = Math.toRadians(rollDeg)
        val cx = cos(x); val sx = sin(x)
        val cy = cos(y); val sy = sin(y)
        val cz = cos(z); val sz = sin(z)
        // R = Rz(roll) * Ry(yaw) * Rx(pitch) — the inverse of the extraction above.
        val r00 = cz * cy
        val r01 = cz * sy * sx - sz * cx
        val r02 = cz * sy * cx + sz * sx
        val r10 = sz * cy
        val r11 = sz * sy * sx + cz * cx
        val r12 = sz * sy * cx - cz * sx
        val r20 = -sy
        val r21 = cy * sx
        val r22 = cy * cx
        return floatArrayOf(
            r00.toFloat(), r01.toFloat(), r02.toFloat(), tx,
            r10.toFloat(), r11.toFloat(), r12.toFloat(), ty,
            r20.toFloat(), r21.toFloat(), r22.toFloat(), tz,
            0f, 0f, 0f, 1f,
        )
    }

    /** Transposes a 4x4 — i.e. re-expresses it in the other layout. Test helper. */
    fun transpose(m: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (row in 0..3) for (col in 0..3) out[row * 4 + col] = m[col * 4 + row]
        return out
    }
}
