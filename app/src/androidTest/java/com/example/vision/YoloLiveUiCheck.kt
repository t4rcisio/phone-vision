package com.example.vision

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit camera check on an unlocked phone; no server requests or API keys. */
class YoloLiveUiCheck {
    @get:Rule val compose = createAndroidComposeRule<ObjectTrackingActivity>()

    @Test
    fun liveCameraKeepsUpdatingAndMultipleCategorySelectionPersists() {
        val vm = ViewModelProvider(compose.activity)[ObjectTrackingModel::class.java]
        compose.waitUntil(60_000) { vm.frames >= 3 && vm.inferences > 0 }
        val start = vm.frames
        compose.onNodeWithContentDescription("YOLO categories").performClick()
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Car", useUnmergedTree = true).performScrollTo().performClick()
        compose
            .onNodeWithText("Motorcycle", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Apply · 2 selected").performClick()
        compose.runOnIdle { assertEquals(setOf(2, 3), vm.categories) }
        compose.waitUntil(30_000) { vm.frames >= start + 30 && vm.inferences >= 3 }
        lateinit var analysis: android.graphics.Bitmap
        lateinit var preview: android.graphics.Bitmap
        compose.runOnIdle {
            analysis = vm.snapshotForSave()!!.first
            preview = compose.activity.cameraPreview!!.bitmap!!
        }
        try {
            val reference = ImageReference(analysis)
            val mark =
                Mark(
                    .08f,
                    .08f,
                    "alignment",
                    right = .92f,
                    bottom = .92f,
                    imageReference = reference,
                )
            ImageTracker().use { tracker ->
                val matrix = tracker.update(preview, listOf(mark))[mark.id]
                assertNotNull("Camera analysis crop must match the displayed preview", matrix)
                val points = floatArrayOf(.2f, .2f, .5f, .5f, .8f, .8f)
                android.graphics.Matrix().apply { setValues(matrix!!) }.mapPoints(points)
                assertEquals(.5f, points[2], .04f)
                assertEquals(.5f, points[3], .04f)
                assertEquals(.2f, points[0], .06f)
                assertEquals(.8f, points[5], .06f)
            }
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString(
                            "stream",
                            "ANALYSIS=${analysis.width}x${analysis.height}; PREVIEW=${preview.width}x${preview.height}; CROP_ALIGNMENT=OK\n",
                        )
                    },
                )
        } finally {
            analysis.recycle()
            preview.recycle()
        }
        compose.onNodeWithText("⋯").performClick()
        compose.onNodeWithText("Local YOLO").assertIsDisplayed()
        compose.onNodeWithText("Automatic orientation").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                0,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "LIVE_FRAMES=${vm.frames}; INFERENCES=${vm.inferences}; CATEGORIES=${vm.categories}; YOLO_MS=${vm.latency}; FLOW_MS=${vm.motionMs}; FLOW_FPS=${vm.trackingFps}; ROTATION=${vm.rotationUsed}; STATUS=${vm.status}\n",
                    )
                },
            )
    }
}
