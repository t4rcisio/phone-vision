package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class IconRenderTest {
    @Test
    fun adaptiveIconRendersWithoutOpeningAnActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(256, 256)) > 0)
        assertNotEquals(bitmap.getPixel(256, 256), bitmap.getPixel(160, 256))
        File(context.filesDir, "vision-icon-preview.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
