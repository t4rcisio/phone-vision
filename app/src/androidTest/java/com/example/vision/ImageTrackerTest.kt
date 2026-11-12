package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test

class ImageTrackerTest {
    private fun fixture(): Bitmap {
        val image = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val random = java.util.Random(872)
        val paint = Paint()
        repeat(850) {
            paint.color = Color.rgb(random.nextInt(255), random.nextInt(255), random.nextInt(255))
            val x = random.nextInt(690).toFloat()
            val y = random.nextInt(450).toFloat()
            canvas.drawRect(x, y, x + random.nextInt(25) + 3, y + random.nextInt(25) + 3, paint)
        }
        return image
    }

    @Test
    fun referenceCoordinatesFollowCameraMotionDuringResponseLatency() {
        val original = fixture()
        val current = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val warp =
            Matrix().apply {
                setValues(
                    floatArrayOf(1.02f, .025f, 18f, -.015f, 1.015f, 14f, .00002f, -.00001f, 1f),
                )
            }
        Canvas(current).apply {
            drawColor(Color.WHITE)
            drawBitmap(original, warp, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val mark =
            Mark(
                .15f,
                .15f,
                "object",
                right = .8f,
                bottom = .8f,
                imageReference = ImageReference(original),
            )
        ImageTracker().use { tracker ->
            val transform = tracker.update(current, listOf(mark))[mark.id]
            assertNotNull("A delayed response must be matched to the current frame", transform)
            val actual = floatArrayOf(.5f, .5f)
            Matrix().apply { setValues(transform!!) }.mapPoints(actual)
            val expected = floatArrayOf(360f, 240f)
            warp.mapPoints(expected)
            assertEquals(expected[0] / 720, actual[0], .012f)
            assertEquals(expected[1] / 480, actual[1], .012f)
        }
    }

    @Test
    fun featurelessLiveImageDoesNotReuseStaleCoordinates() {
        val original = fixture()
        val blank =
            Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val mark =
            Mark(
                .1f,
                .1f,
                "object",
                right = .9f,
                bottom = .9f,
                imageReference = ImageReference(original),
            )
        ImageTracker().use { assertTrue(it.update(blank, listOf(mark)).isEmpty()) }
    }
}
