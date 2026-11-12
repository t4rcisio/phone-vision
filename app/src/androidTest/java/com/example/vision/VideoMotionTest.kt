package com.example.vision

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test

class VideoMotionTest {
    private val texture =
        Bitmap.createBitmap(100, 75, Bitmap.Config.ARGB_8888).also { image ->
            val canvas = Canvas(image)
            canvas.drawColor(Color.LTGRAY)
            val random = java.util.Random(987)
            val paint = Paint()
            repeat(220) {
                paint.color =
                    Color.rgb(random.nextInt(255), random.nextInt(255), random.nextInt(255))
                val x = random.nextInt(96).toFloat()
                val y = random.nextInt(71).toFloat()
                canvas.drawRect(x, y, x + 4, y + 4, paint)
            }
        }
    private val mark =
        Mark(
            80f / 480,
            90f / 320,
            "Car · 81%",
            right = 180f / 480,
            bottom = 165f / 320,
            path = "M0 0L1 0L1 1L0 1Z",
            id = "yolo-1",
        )

    private fun frame(step: Int, visible: Boolean = true) =
        Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).also { image ->
            Canvas(image).apply {
                drawColor(Color.DKGRAY)
                val paint = Paint().apply { color = Color.GRAY }
                for (x in 0 until 480 step 32) drawLine(x.toFloat(), 0f, x.toFloat(), 320f, paint)
                if (visible) drawBitmap(texture, 80f + step * 7f, 90f + step * 2f, null)
            }
        }

    private fun center(h: FloatArray) =
        floatArrayOf(130f / 480, 127.5f / 320).also {
            Matrix().apply { setValues(h) }.mapPoints(it)
        }

    @Test
    fun replayCompensatesMotionThatOccurredWhileInferenceWasPending() {
        VideoMotion().use { motion ->
            repeat(11) { i ->
                frame(i).let {
                    motion.add(i.toLong(), i * 33L, it)
                    it.recycle()
                }
            }
            assertEquals(1, motion.correct(0, listOf(mark)))
            var p = center(motion.visible().second[mark.id]!!)
            assertEquals(200f / 480, p[0], .012f)
            assertEquals(147.5f / 320, p[1], .012f)
            for (i in 11..17) {
                frame(i).let {
                    motion.add(i.toLong(), i * 33L, it)
                    it.recycle()
                }
                p = center(motion.visible().second[mark.id]!!)
                assertEquals((130f + i * 7) / 480, p[0], .012f)
                assertEquals((127.5f + i * 2) / 320, p[1], .012f)
            }
        }
    }

    @Test
    fun missingObjectAndExpiredReferenceCannotLeaveAStaleOutline() {
        VideoMotion().use { motion ->
            frame(0).let {
                motion.add(0, 0, it)
                it.recycle()
            }
            assertEquals(1, motion.correct(0, listOf(mark)))
            repeat(5) { i ->
                frame(i, visible = false).let {
                    motion.add(i + 1L, (i + 1) * 33L, it)
                    it.recycle()
                }
            }
            assertTrue(motion.visible().first.isEmpty())
            for (i in 6..40) frame(i, visible = false).let {
                motion.add(i.toLong(), i * 33L, it)
                it.recycle()
            }
            assertEquals(0, motion.correct(0, listOf(mark)))
            assertTrue(motion.visible().first.isEmpty())
        }
    }

    @Test
    fun gallerySnapshotBakesTheCurrentMotionIntoTheMask() {
        val result = warpedMark(mark, floatArrayOf(1f, 0f, .2f, 0f, 1f, .1f, 0f, 0f, 1f))
        assertEquals(mark.x + .2f, result.x, 1e-5f)
        assertEquals(mark.y + .1f, result.y, 1e-5f)
        assertEquals(mark.right!! + .2f, result.right!!, 1e-5f)
        assertNotNull(SegmentMask.parse(result.path!!))
    }
}
