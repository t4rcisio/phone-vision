package com.example.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val skill: String,
    val prompt: String,
    val answer: String,
    val original: String,
    val annotated: String,
    val marksJson: String,
    val galleryUri: String?,
)

/**
 * Stores source images, annotated images and response metadata in private application storage. A
 * shared mutex serializes writers; AtomicFile makes the index replacement crash-safe. Gallery
 * export is a separate MediaStore transaction and may fail without losing private history.
 */
class HistoryStore(
    private val context: Context,
    directoryName: String = "history",
    private val exportToGallery: Boolean = true,
) {
    private val folder = File(context.filesDir, directoryName).apply { mkdirs() }
    private val index = AtomicFile(File(folder, "index.json"))

    suspend fun list(): List<HistoryEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read() } }

    private fun read(): List<HistoryEntry> = runCatching {
        val json = JSONArray(index.openRead().bufferedReader().use { it.readText() })
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            HistoryEntry(
                o.getString("id"),
                o.getLong("timestamp"),
                o.getString("skill"),
                o.getString("prompt"),
                o.getString("answer"),
                o.getString("original"),
                o.getString("annotated"),
                o.getString("marks"),
                o.optString("gallery").takeIf { it.isNotBlank() },
            )
        }
    }
        .getOrDefault(emptyList())

    suspend fun save(
        bitmap: Bitmap,
        marks: List<Mark>,
        skill: Skill,
        prompt: String,
        answer: String,
    ): HistoryEntry =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = java.util.UUID.randomUUID().toString()
                val original = File(folder, "$id-source.jpg")
                val annotated = File(folder, "$id-result.jpg")
                original.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
                }
                val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                CanvasDrawScope().draw(
                    Density((bitmap.width / 420f).coerceIn(1f, 3f)),
                    LayoutDirection.Ltr,
                    Canvas(result.asImageBitmap()),
                    Size(result.width.toFloat(), result.height.toFloat()),
                ) {
                    renderMarks(marks.filterNot { it.manual })
                }
                annotated.outputStream().use {
                    check(result.compress(Bitmap.CompressFormat.JPEG, 92, it))
                }
                result.recycle()
                val uri =
                    if (exportToGallery) runCatching { export(annotated, id) }.getOrNull() else null
                val entry =
                    HistoryEntry(
                        id,
                        System.currentTimeMillis(),
                        skill.title,
                        prompt,
                        answer,
                        original.absolutePath,
                        annotated.absolutePath,
                        serialize(marks.filterNot { it.manual }),
                        uri,
                    )
                val all = listOf(entry) + read()
                val json =
                    JSONArray().apply {
                        all.forEach { e ->
                            put(
                                JSONObject()
                                    .put("id", e.id)
                                    .put("timestamp", e.timestamp)
                                    .put("skill", e.skill)
                                    .put("prompt", e.prompt)
                                    .put("answer", e.answer)
                                    .put("original", e.original)
                                    .put("annotated", e.annotated)
                                    .put("marks", e.marksJson)
                                    .put("gallery", e.galleryUri ?: ""),
                            )
                        }
                    }
                val stream = index.startWrite()
                try {
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    index.finishWrite(stream)
                } catch (e: Exception) {
                    index.failWrite(stream)
                    throw e
                }
                entry
            }
        }

    private fun export(file: File, id: String): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val uri =
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            "Vision_${System.currentTimeMillis()}_${id.take(6)}.jpg",
                        )
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Vision",
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    },
                ) ?: return null
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                check(
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1,
                )
                return uri.toString()
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val directory =
            File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Vision",
                )
                .apply { mkdirs() }
        val target = File(directory, "Vision_$id.jpg")
        file.copyTo(target)
        return context.contentResolver
            .insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, target.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATA, target.absolutePath)
                },
            )
            ?.toString()
    }

    companion object {
        private val mutex = Mutex()

        fun serialize(marks: List<Mark>): String =
            JSONArray()
                .apply {
                    marks.forEach { m ->
                        put(
                            JSONObject().put("x", m.x).put("y", m.y).put("label", m.label).apply {
                                m.right?.let { put("right", it) }
                                m.bottom?.let { put("bottom", it) }
                                m.path?.let { put("path", it) }
                            },
                        )
                    }
                }
                .toString()

        fun marks(json: String): List<Mark> {
            val all = JSONArray(json)
            return (0 until all.length()).map { i ->
                val m = all.getJSONObject(i)
                Mark(
                    m.getDouble("x").toFloat(),
                    m.getDouble("y").toFloat(),
                    m.getString("label"),
                    right = if (m.has("right")) m.getDouble("right").toFloat() else null,
                    bottom = if (m.has("bottom")) m.getDouble("bottom").toFloat() else null,
                    path = if (m.has("path")) m.getString("path") else null,
                    live = false,
                )
            }
        }
    }
}
