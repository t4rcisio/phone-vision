package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionRegressionTest {
    @Test
    fun encryptedKeySurvivesNewVaultInstance() {
        // Isolated test namespace: never overwrite or print the user's actual credential.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = KeyVault(context, "vision-regression-test", "regression-credentials")
        try {
            vault.save("local-test-value-no-api-call")
            val reopened = KeyVault(context, "vision-regression-test", "regression-credentials")
            assertTrue(
                "Credential must survive reopening storage",
                reopened.read() == "local-test-value-no-api-call",
            )
            reopened.save("")
            assertTrue(
                KeyVault(context, "vision-regression-test", "regression-credentials")
                    .read()
                    .isEmpty(),
            )
        } finally {
            vault.save("")
        }
    }

    @Test
    fun manyObjectsSelectsAllPointsOrOnePoint() {
        val json = JSONObject("""{"points":[{"x":0.2,"y":0.3},{"x":0.7,"y":0.8}]}""")
        assertEquals(2, Moondream.parseResponse(Skill.POINT, "person", json, true).marks.size)
        assertEquals(1, Moondream.parseResponse(Skill.POINT, "person", json, false).marks.size)
    }

    @Test
    fun segmentMaskIsPlottedInsideReturnedBoundingBox() {
        val json =
            JSONObject(
                """{"path":"M 0 0 L 1 0 L 1 1 L 0 1 Z","bbox":{"x_min":0.2,"y_min":0.3,"x_max":0.6,"y_max":0.8}}""",
            )
        val response = Moondream.parseResponse(Skill.SEGMENT, "object", json, true)
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(1000f, 1000f),
        ) {
            renderMarks(response.marks)
        }
        assertTrue("Mask must fill bbox interior", Color.alpha(bitmap.getPixel(400, 550)) > 0)
        assertEquals("Mask must not fill whole image", 0, Color.alpha(bitmap.getPixel(100, 100)))
        assertEquals("Mask must stop at bbox edge", 0, Color.alpha(bitmap.getPixel(700, 600)))
    }

    @Test
    fun tripodMatrixIgnoresSensorReadings() {
        val mark = Mark(.4f, .6f, "point")
        val p = floatArrayOf(mark.x, mark.y)
        overlayMatrix(mark, 1000f, 800f, null, false)!!.mapPoints(p)
        assertEquals(400f, p[0], .001f)
        assertEquals(480f, p[1], .001f)
    }

    @Test
    fun irregularMaskDoesNotDrawItsBoundingRectangle() {
        val json =
            JSONObject(
                """{"path":"M 0.5 0 L 1 1 L 0 1 Z","bbox":{"x_min":0.2,"y_min":0.3,"x_max":0.6,"y_max":0.8}}""",
            )
        val marks = Moondream.parseResponse(Skill.SEGMENT, "object", json, false).marks
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(1000f, 1000f),
        ) {
            renderMarks(marks)
        }
        assertTrue(Color.alpha(bitmap.getPixel(400, 650)) > 0)
        assertEquals(
            "No rectangle edge outside the actual object",
            0,
            Color.alpha(bitmap.getPixel(200, 600)),
        )
        assertEquals("No mask outside the actual object", 0, Color.alpha(bitmap.getPixel(220, 500)))
    }

    @Test
    fun malformedMaskDoesNotBecomeAnApparentlySuccessfulBox() {
        val json =
            JSONObject(
                """{"path":"not svg","bbox":{"x_min":0.2,"y_min":0.3,"x_max":0.6,"y_max":0.8}}""",
            )
        assertThrows(IllegalStateException::class.java) {
            Moondream.parseResponse(Skill.SEGMENT, "object", json, false)
        }
    }

    @Test
    fun svgWrapperIsAccepted() {
        assertNotNull(SegmentMask.parse("""<svg><path d="M 0 0 L 1 0 L 0 1 Z"/></svg>"""))
    }

    @Test
    fun arPointsAreClippedToCameraViewport() {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(100f, 100f),
        ) {
            translate(70f, 70f) {
                drawArShapes(
                    listOf(ArDrawing("outside", listOf(listOf(XY(.5f, -.15f))), false, true)),
                )
            }
        }
        assertEquals(
            "No anchor in the toolbar above the viewport",
            0,
            Color.alpha(bitmap.getPixel(120, 55)),
        )
        assertEquals("No anchor outside the camera", 0, Color.alpha(bitmap.getPixel(120, 66)))
    }

    @Test
    fun deletingOnePointPreservesOtherPoints() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as android.app.Application
            val vm = VisionModel(app)
            val original = vm.marks.map { it.id }.toSet()
            val a = Mark(.25f, .4f, "test-a", manual = true)
            val b = Mark(.7f, .4f, "test-b", manual = true)
            try {
                vm.manualMarking = true
                vm.addArPin(a)
                vm.addArPin(b)
                vm.removeMarks(setOf(a.id))
                assertFalse(vm.marks.any { it.id == a.id })
                assertTrue(vm.marks.any { it.id == b.id })
                assertTrue(vm.marks.map { it.id }.containsAll(original))
            } finally {
                vm.removeMarks(setOf(a.id, b.id))
            }
        }
    }

    @Test
    fun confidenceAndUserPercentLabelsRenderWithoutFormatterCrash() {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val labels = listOf("Car #1 · 80%", "Motorcycle #2 · 99%", "%s %n %.2f %%")
        val marks = labels.mapIndexed { i, label ->
            Mark(
                .1f,
                .2f + i * .2f,
                label,
                right = .8f,
                bottom = .35f + i * .2f,
                path = "M0 0L1 0L1 1L0 1Z",
                id = if (i < 2) "yolo-$i" else "user-$i",
            )
        }
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(600f, 800f),
        ) {
            renderMarks(marks)
        }
        assertTrue(Color.alpha(bitmap.getPixel(200, 200)) > 0)
        bitmap.recycle()
    }
}
