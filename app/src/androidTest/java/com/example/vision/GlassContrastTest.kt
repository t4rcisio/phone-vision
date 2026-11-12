package com.example.vision

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun glassTitlesAndBodyHaveReadableLightForeground() {
        var color = Color.Black
        compose.setContent {
            VisionAppTheme {
                GlassSheet("Local YOLO", {}) {
                    color = LocalContentColor.current
                    Text("Confidence · 31%")
                }
            }
        }
        compose.onNodeWithText("Local YOLO").assertIsDisplayed()
        compose.onNodeWithText("Confidence · 31%").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(ColorUtils.calculateContrast(color.toArgb(), GlassNavy.toArgb()) > 7)
        }
        val pixels = compose.onNodeWithText("Local YOLO").captureToImage().toPixelMap()
        var light = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red > .8 && c.green > .8 && c.blue > .8) light++
        }
        assertTrue("Title must render light glyphs, not inherited black", light > 20)
    }
}
