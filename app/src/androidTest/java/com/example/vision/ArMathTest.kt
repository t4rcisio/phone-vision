package com.example.vision

import android.opengl.Matrix
import org.junit.Assert.*
import org.junit.Test

class ArMathTest {
    private fun vp(x: Float = 0f, y: Float = 0f, z: Float = 0f): FloatArray {
        val projection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 60f, 1f, .01f, 100f)
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, x, y, z, x, y, z - 1f, 0f, 1f, 0f)
        return FloatArray(16).also { Matrix.multiplyMM(it, 0, projection, 0, view, 0) }
    }

    @Test
    fun forwardTranslationChangesApparentScaleOfFixedWorldPoint() {
        val point = floatArrayOf(.3f, 0f, -2f)
        val original = ArMath.project(point, vp())!!
        val closer = ArMath.project(point, vp(z = -1f))!!
        assertTrue(closer.x > original.x)
        assertEquals(2f, (closer.x - .5f) / (original.x - .5f), .001f)
    }

    @Test
    fun translationAlongXAndYChangesProjection() {
        val point = floatArrayOf(0f, 0f, -2f)
        assertTrue(ArMath.project(point, vp(x = .3f))!!.x < .5f)
        assertTrue(ArMath.project(point, vp(y = .3f))!!.y > .5f)
    }

    @Test
    fun capturedCameraRayIntersectsSurfaceAndReprojectsToSamePixel() {
        val matrix = vp(x = .2f, y = .1f, z = .3f)
        val inverse = FloatArray(16)
        Matrix.invertM(inverse, 0, matrix, 0)
        val source = ArSource("test", inverse)
        val point =
            ArMath.intersect(
                source,
                .3f,
                .7f,
                floatArrayOf(0f, 0f, -2f),
                floatArrayOf(0f, 0f, 1f),
            )!!
        val projected = ArMath.project(point, matrix)!!
        assertEquals(.3f, projected.x, .0001f)
        assertEquals(.7f, projected.y, .0001f)
    }

    @Test
    fun pointsBehindCameraAreNotProjected() {
        assertNull(ArMath.project(floatArrayOf(0f, 0f, 2f), vp()))
    }
}
