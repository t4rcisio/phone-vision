package com.example.vision

import android.opengl.Matrix
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CaptureGeometryTest {
    private fun projection(aspect: Float = 1f) =
        FloatArray(16).also { Matrix.perspectiveM(it, 0, 60f, aspect, .01f, 100f) }

    private fun source(depth: DepthSnapshot?): ArSource {
        val p = projection()
        val inverse = FloatArray(16)
        Matrix.invertM(inverse, 0, p, 0)
        return ArSource(
            "test",
            inverse,
            timestampNs = 123_000_000,
            imageWidth = 1000,
            imageHeight = 1000,
            inverseProjection = inverse,
            depth = depth,
        )
    }

    private fun depth(
        values: IntArray = IntArray(10000) { 2000 },
        uv: FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
    ) = DepthSnapshot(100, 100, values, 123_000_000, uv)

    private fun vp(camera: Pose, aspect: Float = 1f): FloatArray {
        val v = FloatArray(16)
        camera.inverse().toMatrix(v, 0)
        return FloatArray(16).also { Matrix.multiplyMM(it, 0, projection(aspect), 0, v, 0) }
    }

    @Test
    fun delayedReplyUsesCapturedDepthAfterTranslationAndRotation() {
        val objectPoint = floatArrayOf(.35f, .1f, -2f)
        val captured = ArMath.project(objectPoint, projection())!!
        val frame = source(depth())
        val restored =
            CaptureGeometry.reconstruct(Mark(captured.x, captured.y, "object"), frame)!![0][0]
        for (i in 0..2) assertEquals(objectPoint[i], restored[i], .0001f)
        val camera =
            Pose(
                floatArrayOf(.3f, -.15f, -.6f),
                floatArrayOf(0f, kotlin.math.sin(.1f), 0f, kotlin.math.cos(.1f)),
            )
        val expected = ArMath.project(objectPoint, vp(camera))!!
        val projected = ArMath.project(restored, vp(camera))!!
        assertEquals(expected.x, projected.x, .0001f)
        assertEquals(expected.y, projected.y, .0001f)
        assertTrue(kotlin.math.abs(projected.y - captured.y) > .01f)
    }

    @Test
    fun capturesAreImmutableWhileNewSceneDepthChanges() {
        val values = IntArray(10000) { 2000 }
        val frozen = source(depth(values))
        values.fill(8000)
        assertEquals(-2f, frozen.cameraPoint(.5f, .5f)!![2], .001f)
        assertNull(source(null).cameraPoint(.5f, .5f))
    }

    @Test
    fun nonPlanarContourKeepsPerVertexDepthAndOriginalPixelLocations() {
        val values = IntArray(10000) { 1500 + (it % 100) * 10 }
        val frame = source(depth(values))
        val mark = Mark(.2f, .2f, "object", right = .8f, bottom = .8f)
        val contour = CaptureGeometry.reconstruct(mark, frame)!![0]
        assertTrue(kotlin.math.abs(contour[0][2] - contour[1][2]) > .5f)
        val expected = listOf(XY(.2f, .2f), XY(.8f, .2f), XY(.8f, .8f), XY(.2f, .8f))
        contour.forEachIndexed { i, p ->
            val pixel = ArMath.project(p, projection())!!
            assertEquals(expected[i].x, pixel.x, .0001f)
            assertEquals(expected[i].y, pixel.y, .0001f)
        }
    }

    @Test
    fun worldMapRefinementAndViewportResizeDoNotShiftObject() {
        val captured = source(depth()).cameraPoint(.65f, .4f)!!
        val camera = Pose.makeTranslation(.3f, .1f, -.4f)
        val correction =
            Pose(
                floatArrayOf(.4f, -.2f, .1f),
                floatArrayOf(0f, 0f, kotlin.math.sin(.15f), kotlin.math.cos(.15f)),
            )
        val before = ArMath.project(captured, vp(camera, 1.5f))!!
        val after =
            ArMath.project(
                correction.transformPoint(captured),
                vp(correction.compose(camera), 1.5f),
            )!!
        assertEquals(before.x, after.x, .0001f)
        assertEquals(before.y, after.y, .0001f)
    }

    @Test
    fun depthCropAndRotationAreAppliedBeforeSampling() {
        val values = IntArray(10000) { if (it % 100 < 50) 1000 else 3000 }
        val normal = source(depth(values)).cameraPoint(.25f, .75f)!!
        val rotated =
            source(depth(values, floatArrayOf(0f, 1f, 0f, -1f, 1f, 0f))).cameraPoint(.25f, .75f)!!
        assertEquals(-1f, normal[2], .001f)
        assertEquals(-3f, rotated[2], .001f)
    }

    @Test
    fun staleDepthFramesAreRejected() {
        assertTrue(DepthSnapshot.synchronizedTimestamp(123_000_000, 123_000_000, 123_000_001))
        assertFalse(DepthSnapshot.synchronizedTimestamp(100_000_000, 123_000_000, 123_000_001))
    }

    @Test
    fun unsignedDepthRespectsRowAndPixelStrides() {
        val bytes = ByteBuffer.allocate(24 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0..3) for (x in 0..3) bytes.putShort(y * 24 + x * 4, 2500.toShort())
        val frozen =
            DepthSnapshot.copy(4, 4, bytes, 24, 4, 123, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        assertEquals(2.5f, frozen.sample(.5f, .5f)!!, .001f)
    }
}
