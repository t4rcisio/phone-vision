package com.example.vision

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Skill(val title: String, val endpoint: String, val hint: String) {
    QUERY("Ask", "query", "What would you like to know about the image?"),
    CAPTION("Describe", "caption", "Automatic image description"),
    DETECT("Detect", "detect", "Object to locate, e.g. chair"),
    POINT("Point / count", "point", "Object to point at, e.g. person"),
    SEGMENT("Segment", "segment", "Object to segment, e.g. person"),
    OCR("Read text", "query", "Optional text-reading instructions"),
}

data class VisionResult(val text: String, val marks: List<Mark>)

/**
 * Explicit HTTPS requests to Moondream; no background camera uploads or automatic request loop.
 * Model results use normalized image coordinates. Segmentation paths are normalized inside their
 * bounding box, so consumers must apply the box transform before projecting onto the preview.
 */
object Moondream {
    suspend fun request(
        key: String,
        model: String,
        skill: Skill,
        prompt: String,
        bitmap: Bitmap,
        manyObjects: Boolean = true,
        onRequest: suspend () -> Unit = {},
    ): VisionResult =
        withContext(Dispatchers.IO) {
            val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
            val scaled =
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            val bytes =
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            if (scaled !== bitmap) scaled.recycle()
            val body =
                JSONObject()
                    .put("model", model)
                    .put(
                        "image_url",
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                    )
                    .put("stream", false)
            when (skill) {
                Skill.QUERY -> body.put("question", "$prompt\nRespond in English.")
                Skill.OCR ->
                    body.put(
                        "question",
                        "Transcribe the visible text, preserving line breaks. $prompt",
                    )
                Skill.CAPTION -> body.put("length", "normal")
                else -> body.put("object", prompt)
            }
            suspend fun post(endpoint: String, payload: JSONObject): JSONObject {
                val connection =
                    URL("https://api.moondream.ai/v1/$endpoint").openConnection()
                        as HttpsURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 90_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("X-Moondream-Auth", key)
                    onRequest()
                    connection.outputStream.use {
                        it.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }
                    val status = connection.responseCode
                    if (status !in 200..299)
                        error(
                            when (status) {
                                401,
                                403 -> "Key rejected. Check your key and model access."
                                429 -> "Rate limit reached. Wait before trying again."
                                else -> "Moondream returned HTTP $status. Retry manually."
                            },
                        )
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }
            if (skill == Skill.SEGMENT && manyObjects) {
                val detections = post("detect", JSONObject(body.toString()))
                val boxes = detections.getJSONArray("objects")
                if (boxes.length() == 0)
                    return@withContext VisionResult("No objects found: $prompt", emptyList())
                body.put(
                    "spatial_refs",
                    JSONArray().apply {
                        for (i in 0 until boxes.length()) {
                            val box = boxes.getJSONObject(i)
                            put(
                                JSONArray(
                                    listOf(
                                        box.getDouble("x_min"),
                                        box.getDouble("y_min"),
                                        box.getDouble("x_max"),
                                        box.getDouble("y_max"),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
            parseResponse(skill, prompt, post(skill.endpoint, body), manyObjects)
        }

    internal fun parseResponse(
        skill: Skill,
        prompt: String,
        json: JSONObject,
        manyObjects: Boolean,
    ): VisionResult {
        return when (skill) {
            Skill.DETECT -> {
                val items = json.getJSONArray("objects")
                val marks =
                    (0 until items.length()).map { i ->
                        val item = items.getJSONObject(i)
                        Mark(
                            item.getDouble("x_min").toFloat().coerceIn(0f, 1f),
                            item.getDouble("y_min").toFloat().coerceIn(0f, 1f),
                            "$prompt ${i + 1}",
                            right = item.getDouble("x_max").toFloat().coerceIn(0f, 1f),
                            bottom = item.getDouble("y_max").toFloat().coerceIn(0f, 1f),
                        )
                    }
                val selected = if (manyObjects) marks else marks.take(1)
                VisionResult("${selected.size} objects shown: $prompt", selected)
            }
            Skill.POINT -> {
                val items = json.getJSONArray("points")
                val marks =
                    (0 until items.length()).map { i ->
                        val item = items.getJSONObject(i)
                        Mark(
                            item.getDouble("x").toFloat().coerceIn(0f, 1f),
                            item.getDouble("y").toFloat().coerceIn(0f, 1f),
                            "$prompt ${i + 1}",
                        )
                    }
                val selected = if (manyObjects) marks else marks.take(1)
                VisionResult("${selected.size} points shown: $prompt", selected)
            }
            Skill.SEGMENT -> {
                val segments = json.optJSONArray("segments")
                val entries =
                    if (segments != null)
                        (0 until segments.length()).map { segments.getJSONObject(it) }
                    else listOf(json)
                val masks = entries.mapNotNull { entry ->
                    val path = entry.optString("path").takeUnless { it.isBlank() || it == "null" }
                    if (path == null) {
                        check(!entry.has("bbox") || entry.isNull("bbox")) {
                            "The service returned a box without a mask. Segmentation is incomplete; try again."
                        }
                        null
                    } else {
                        checkNotNull(SegmentMask.parse(path)) {
                            "The returned mask could not be drawn. Try segmenting again."
                        }
                        val box = entry.getJSONObject("bbox")
                        Mark(
                            box.getDouble("x_min").toFloat(),
                            box.getDouble("y_min").toFloat(),
                            prompt,
                            right = box.getDouble("x_max").toFloat(),
                            bottom = box.getDouble("y_max").toFloat(),
                            path = path,
                        )
                    }
                }
                val selected = if (manyObjects) masks else masks.take(1)
                VisionResult(
                    if (selected.isEmpty()) "No masks found: $prompt"
                    else "${selected.size} segmentation masks: $prompt",
                    selected,
                )
            }
            else ->
                VisionResult(
                    json.getString(if (skill == Skill.CAPTION) "caption" else "answer"),
                    emptyList(),
                )
        }
    }
}
