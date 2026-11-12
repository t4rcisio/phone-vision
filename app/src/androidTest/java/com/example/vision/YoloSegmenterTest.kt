package com.example.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class YoloSegmenterTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun fixture() =
        instrumentation.context.assets.open("yolo-bus.jpg").use { BitmapFactory.decodeStream(it) }

    @Test
    fun bundledModelSegmentsRealObjectsOfflineOnCpu() {
        val bitmap = fixture()
        YoloSegmenter(instrumentation.targetContext, preferGpu = false).use { model ->
            val detections = model.detect(bitmap)
            assertEquals("CPU", model.accelerator)
            val bus = detections.firstOrNull { it.category == 5 }
            assertNotNull("Bundled weights must detect the bus", bus)
            assertTrue(detections.any { it.category == 0 })
            assertTrue(bus!!.path.count { it == 'L' } > 8)
            assertNotNull(SegmentMask.parse(bus.path))
            assertTrue(
                bus.box.left in 0f..1f && bus.box.right in 0f..1f && bus.box.bottom in 0f..1f,
            )
            val marked = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val marks = detections.map { d ->
                Mark(
                    d.box.left,
                    d.box.top,
                    YoloLabels[d.category],
                    right = d.box.right,
                    bottom = d.box.bottom,
                    path = d.path,
                )
            }
            CanvasDrawScope().draw(
                Density(2f),
                LayoutDirection.Ltr,
                Canvas(marked.asImageBitmap()),
                Size(marked.width.toFloat(), marked.height.toFloat()),
            ) {
                renderMarks(marks)
            }
            File(instrumentation.targetContext.filesDir, "yolo-preview.png").outputStream().use {
                marked.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            marked.recycle()
        }
        bitmap.recycle()
    }

    @Test
    fun gpuOrCpuFallbackRunsRepeatedFramesAndCategoryFilter() {
        val bitmap = fixture()
        val durations = mutableListOf<Long>()
        YoloSegmenter(instrumentation.targetContext).use { model ->
            repeat(4) {
                val start = SystemClock.elapsedRealtime()
                val found = model.detect(bitmap, categories = setOf(5))
                durations += SystemClock.elapsedRealtime() - start
                assertTrue(found.isNotEmpty())
                assertTrue(found.all { it.category == 5 })
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "YOLO ${model.accelerator}: ${durations.joinToString()} ms (load + 3 warm frames)\n",
                    )
                },
            )
        }
        bitmap.recycle()
    }

    @Test
    fun noStaleContoursWhenNextImageIsBlank() {
        val blank =
            Bitmap.createBitmap(480, 960, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(114, 114, 114))
            }
        YoloSegmenter(instrumentation.targetContext, preferGpu = false).use { model ->
            assertTrue(model.detect(blank, .5f).isEmpty())
        }
        blank.recycle()
    }

    @Test
    fun movingBusKeepsItsIdOverStationaryBackground() {
        val original = fixture()
        YoloSegmenter(instrumentation.targetContext).use { model ->
            val bus = model.detect(original, categories = setOf(5)).first()
            val shape = SegmentMask.parse(bus.path)!!
            val b = bus.box
            shape.transform(
                android.graphics.Matrix().apply {
                    setValues(
                        floatArrayOf(
                            b.width * original.width,
                            0f,
                            b.left * original.width,
                            0f,
                            b.height * original.height,
                            b.top * original.height,
                            0f,
                            0f,
                            1f,
                        ),
                    )
                },
            )
            val cutout =
                Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(cutout).apply {
                clipPath(shape)
                drawBitmap(original, 0f, 0f, null)
            }
            val tracker = YoloTrackIds()
            var id: Int? = null
            var firstCenter = 0f
            repeat(4) { i ->
                val frame =
                    Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(frame).apply {
                    drawColor(Color.rgb(70, 75, 80))
                    val paint =
                        android.graphics.Paint().apply {
                            color = Color.GRAY
                            strokeWidth = 2f
                        }
                    for (x in 0 until original.width step 80) drawLine(
                        x.toFloat(),
                        0f,
                        x.toFloat(),
                        original.height.toFloat(),
                        paint,
                    )
                    drawBitmap(
                        cutout,
                        android.graphics.Matrix().apply {
                            postScale(.65f, .65f)
                            postTranslate(40f + i * 24f, 40f)
                        },
                        null,
                    )
                }
                val found = model.detect(frame, categories = setOf(5))
                assertTrue("Moved foreground must remain a bus at frame $i", found.isNotEmpty())
                val tracked = tracker.update(found, i * 150L).maxBy { it.second.score }
                if (i == 0) {
                    id = tracked.first
                    firstCenter = tracked.second.box.cx
                } else {
                    assertEquals("Keep the bus identity while its pixels move", id, tracked.first)
                    assertEquals(
                        firstCenter + i * 24f / original.width,
                        tracked.second.box.cx,
                        .025f,
                    )
                }
                frame.recycle()
            }
            cutout.recycle()
        }
        original.recycle()
    }

    @Test
    fun rotatedPublicFixtureReturnsMasksToPreviewCoordinates() {
        val original = fixture()
        val bitmap =
            Bitmap.createBitmap(
                original,
                0,
                0,
                original.width,
                original.height,
                android.graphics.Matrix().apply { postRotate(90f) },
                true,
            )
        YoloSegmenter(instrumentation.targetContext).use { model ->
            val found = model.detect(bitmap, .4f, categories = setOf(5), rotation = 270)
            assertTrue(
                "A rotated public fixture must still detect the bus",
                found.any { it.category == 5 && it.score > .6f },
            )
            assertTrue(found.all { SegmentMask.parse(it.path) != null })
            assertTrue(
                found.all {
                    it.box.left >= 0f &&
                        it.box.top >= 0f &&
                        it.box.right <= 1f &&
                        it.box.bottom <= 1f
                },
            )
        }
        bitmap.recycle()
        original.recycle()
    }
}
