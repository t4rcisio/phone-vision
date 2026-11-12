package com.example.vision

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarkInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun normalTouchAfterDetectionDoesNotCreateManualLabel() {
        var pins = 0
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 400.dp)) {
                    Snapshot(bitmap, listOf(Mark(.2f, .3f, "laptop", right = .7f, bottom = .8f))) {
                        _,
                        _ ->
                        pins++
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Image selected for analysis").performTouchInput {
            click(center)
        }
        compose.runOnIdle { assertEquals(0, pins) }
    }

    @Test
    fun explicitManualModeAllowsPinning() {
        var pins = 0
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 400.dp)) {
                    Snapshot(bitmap, emptyList(), manualMarking = true) { _, _ -> pins++ }
                }
            }
        }
        compose.onNodeWithContentDescription("Image selected for analysis").performTouchInput {
            click(center)
        }
        compose.runOnIdle { assertEquals(1, pins) }
    }
}
