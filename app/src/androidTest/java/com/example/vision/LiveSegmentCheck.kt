package com.example.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration check; never runs during the regular regression suite. */
class LiveSegmentCheck {
    @Test
    fun segmentExplicitLocalFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRealApi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = KeyVault(context).read()
        assumeTrue("Configure a key in the app before this check", key.isNotBlank())
        val file = java.io.File(context.filesDir, "vision-segment-check.jpg")
        assumeTrue("Test image not found", file.exists())
        val original = BitmapFactory.decodeFile(file.absolutePath)
        val factor = minOf(1f, 1280f / maxOf(original.width, original.height))
        val bitmap =
            Bitmap.createScaledBitmap(
                original,
                (original.width * factor).toInt(),
                (original.height * factor).toInt(),
                true,
            )
        val bytes =
            ByteArrayOutputStream()
                .apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, this) }
                .toByteArray()
        val payload =
            JSONObject()
                .put("model", "moondream3.1-9B-A2B")
                .put("object", "laptop")
                .put("stream", false)
                .put(
                    "image_url",
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
        val connection =
            URL("https://api.moondream.ai/v1/segment").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Moondream-Auth", key)
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            assertTrue(
                "Segment HTTP ${connection.responseCode}",
                connection.responseCode in 200..299,
            )
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            println("SEGMENT_KEYS=" + json.keys().asSequence().toList().joinToString(","))
            println("SEGMENT_PATH_TYPE=" + (json.opt("path")?.javaClass?.simpleName ?: "absent"))
            println("SEGMENT_PATH_PREFIX=" + json.optString("path").take(180))
            val result = Moondream.parseResponse(Skill.SEGMENT, "laptop", json, false)
            assertTrue(
                "Must return a drawable object contour",
                result.marks.any { it.path != null },
            )
        } finally {
            connection.disconnect()
            file.delete()
        }
    }
}
