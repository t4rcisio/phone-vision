package com.example.vision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * State owner for the remote 2D/AR query experience.
 *
 * A generation counter associates each response with its immutable capture. Clearing or replacing
 * an image invalidates outstanding responses, so late network data cannot redraw an unrelated
 * scene. Credentials are delegated to [KeyVault]; captured images and answers belong to
 * [HistoryStore].
 */
class VisionModel(app: Application) : AndroidViewModel(app) {
    private val vault = KeyVault(app)
    private val prefs = app.getSharedPreferences("vision", 0)
    private val historyStore = HistoryStore(app)
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    private var imageGeneration = 0L
    var apiKey by mutableStateOf(vault.read())
        private set

    var model by mutableStateOf(prefs.getString("model", "moondream3.1-9B-A2B")!!)
        private set

    var skill by mutableStateOf(Skill.SEGMENT)
    var manualMarking by mutableStateOf(false)
    var prompt by mutableStateOf("")
    var manyObjects by mutableStateOf(prefs.getBoolean("manyObjects", true))
    var sensors by mutableStateOf(prefs.getBoolean("sensors", false))
        private set

    var saving by mutableStateOf(false)
        private set

    var settingsMessage by mutableStateOf<String?>(null)
        private set

    var image by mutableStateOf<Bitmap?>(null)
        private set

    var imagePose: List<Float>? = null
        private set

    var imageArSource: ArSource? = null
        private set

    var imageReference: ImageReference? = null
        private set

    private var preserveArManual = false
    var result by mutableStateOf(prefs.getString("result", "")!!)
        private set

    var marks by mutableStateOf<List<Mark>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var requests by mutableStateOf(0)
        private set

    var aspect = 3f / 4f
    var needsAlignment by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { history = historyStore.list() }
        runCatching {
            val array = JSONArray(prefs.getString("marks", "[]"))
            marks =
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Mark(
                        o.getDouble("x").toFloat(),
                        o.getDouble("y").toFloat(),
                        o.getString("label"),
                        right = if (o.has("right")) o.getDouble("right").toFloat() else null,
                        bottom = if (o.has("bottom")) o.getDouble("bottom").toFloat() else null,
                        path = if (o.has("path")) o.getString("path") else null,
                        manual =
                            o.optBoolean(
                                "manual",
                                !o.has("right") && !o.getString("label").matches(Regex(".* \\d+$")),
                            ),
                        live = false,
                    )
                }
            needsAlignment = marks.isNotEmpty()
        }
            .onFailure { error = "Could not restore the saved points." }
    }

    fun updateSensors(value: Boolean) {
        sensors = value
        prefs.edit().putBoolean("sensors", value).apply()
        if (value) needsAlignment = marks.any { it.reference == null }
    }

    fun refreshHistory() {
        viewModelScope.launch { history = historyStore.list() }
    }

    fun configure(key: String, selectedModel: String, onSuccess: () -> Unit) {
        if (saving) return
        saving = true
        settingsMessage = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    vault.save(key.trim())
                    check(prefs.edit().putString("model", selectedModel).commit())
                }
                apiKey = key.trim()
                model = selectedModel
                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                settingsMessage = "Could not confirm key storage. Try saving again."
            } finally {
                saving = false
            }
        }
    }

    fun selectImage(
        bitmap: Bitmap,
        pose: List<Float>?,
        arSource: ArSource? = null,
        preserveManual: Boolean = false,
        reference2d: ImageReference? = null,
    ) {
        imageGeneration++
        image = bitmap
        imagePose = pose
        imageArSource = arSource
        imageReference = reference2d
        preserveArManual = preserveManual || arSource != null
        result = ""
        marks =
            if (preserveArManual)
                marks.filter {
                    it.manual && (arSource == null || it.arSource?.sessionId == arSource.sessionId)
                }
            else emptyList()
        needsAlignment = false
        error = null
        persist()
    }

    fun importImage(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val bitmap =
                    withContext(Dispatchers.IO) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                getApplication<Application>().contentResolver,
                                uri,
                            ),
                        ) { decoder, info, _ ->
                            val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }
                selectImage(bitmap, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Could not open this image. Choose another."
            } finally {
                busy = false
            }
        }
    }

    fun canAsk(): Boolean {
        if (busy) return false
        if (apiKey.isBlank()) {
            error = "Configure your Moondream key before querying."
            return false
        }
        if (
            skill in listOf(Skill.QUERY, Skill.DETECT, Skill.POINT, Skill.SEGMENT) &&
                prompt.isBlank()
        ) {
            error = "Enter a question or the object to find."
            return false
        }
        return true
    }

    fun ask() {
        if (!canAsk()) return
        val snapshot =
            image
                ?: run {
                    error = "Capture or select an image first."
                    return
                }
        val selectedSkill = skill
        val selectedMany = manyObjects
        prefs.edit().putBoolean("manyObjects", selectedMany).apply()
        val text = prompt.trim()
        val pose = imagePose
        val arSource = imageArSource
        val ref2d = imageReference
        val keepManual = preserveArManual
        val generation = imageGeneration
        val snapshotAspect = snapshot.width.toFloat() / snapshot.height
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val response =
                    Moondream.request(apiKey, model, selectedSkill, text, snapshot, selectedMany) {
                        withContext(Dispatchers.Main) { requests++ }
                    }
                if (generation != imageGeneration) return@launch
                result = response.text
                marks =
                    (if (keepManual) marks.filter { it.manual } else emptyList()) +
                        response.marks.map { mark ->
                            mark.copy(
                                imageReference = ref2d,
                                arSource = arSource,
                                reference = pose,
                                world =
                                    pose?.let { Spatial.world(mark.x, mark.y, snapshotAspect, it) },
                            )
                        }
                needsAlignment = marks.isNotEmpty() && pose == null
                persist()
                runCatching {
                    historyStore.save(snapshot, marks, selectedSkill, text, response.text)
                }
                    .onSuccess { entry ->
                        history = listOf(entry) + history.filterNot { it.id == entry.id }
                        if (entry.galleryUri == null)
                            error = "History saved. Could not save the image to the gallery."
                    }
                    .onFailure { error = "Response received, but history could not be saved." }
                if (arSource != null && arSource.depth == null && response.marks.isNotEmpty())
                    error = "No depth in this capture. Open Results to view the image."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error =
                    if (e is java.io.IOException)
                        "Connection failed or timed out. Check your connection and try again."
                    else e.message ?: "Query failed."
            } finally {
                busy = false
            }
        }
    }

    fun pin(
        x: Float,
        y: Float,
        pose: List<Float>?,
        cameraAspect: Float,
        reference2d: ImageReference? = null,
    ) {
        if (busy || !manualMarking) return
        marks =
            marks +
                Mark(
                    x,
                    y,
                    "Manual point ${marks.count { it.manual } + 1}",
                    pose?.let { Spatial.world(x, y, cameraAspect, it) },
                    reference = pose,
                    manual = true,
                    imageReference = reference2d,
                )
        needsAlignment = marks.any { it.world == null }
        persist()
    }

    fun align(pose: List<Float>) {
        marks = marks.map {
            it.copy(reference = pose, world = Spatial.world(it.x, it.y, aspect, pose))
        }
        needsAlignment = false
    }

    fun addArPin(mark: Mark) {
        if (busy || !manualMarking) return
        marks = marks + mark
        persist()
    }

    fun clear() {
        imageGeneration++
        marks = emptyList()
        result = ""
        needsAlignment = false
        persist()
    }

    fun clearManualMarks() {
        marks = marks.filterNot { it.manual }
        persist()
    }

    internal fun removeMarks(ids: Set<String>) {
        marks = marks.filterNot { it.id in ids }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        marks.forEach { mark ->
            array.put(
                JSONObject().put("x", mark.x).put("y", mark.y).put("label", mark.label).apply {
                    put("manual", mark.manual)
                    mark.right?.let { put("right", it) }
                    mark.bottom?.let { put("bottom", it) }
                    mark.path?.let { put("path", it) }
                },
            )
        }
        prefs.edit().putString("result", result).putString("marks", array.toString()).apply()
    }
}
