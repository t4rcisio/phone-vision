package com.example.vision

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * UI state for the independent, offline tracking screen. Owns the most recently delivered frame
 * reference and releases it on replacement or teardown. Saving freezes pixels and transformed
 * contours together; it never uses a later preview image.
 */
class ObjectTrackingModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("yolo", 0)
    var categories by
        mutableStateOf(
            prefs
                .getStringSet("categories", null)
                ?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id in 0..79 } }
                ?.toSet()
                ?: prefs.getInt("category", -1).takeIf { it in 0..79 }?.let { setOf(it) }
                ?: emptySet(),
        )
        private set

    var automaticOrientation by mutableStateOf(prefs.getBoolean("autoOrientation", true))
        private set

    var manualRotation by mutableIntStateOf(prefs.getInt("manualRotation", 0))
        private set

    var orientationHint by mutableIntStateOf(0)
        private set

    var rotationUsed by mutableIntStateOf(0)
        internal set

    val categoryLabel
        get() =
            when (categories.size) {
                0 -> "All"
                1 -> YoloLabels[categories.single()]
                2 -> categories.sorted().joinToString(" + ") { YoloLabels[it] }
                else -> "${categories.size} categories"
            }

    var threshold by mutableStateOf(prefs.getFloat("threshold", .4f).coerceIn(.2f, .8f))
        private set

    var paused by mutableStateOf(false)
        private set

    var marks by mutableStateOf<List<Mark>>(emptyList())
        private set

    var saving by mutableStateOf(false)
        private set

    var status by mutableStateOf("Loading…")
        internal set

    var latency by mutableStateOf(0L)
        internal set

    var frames by mutableLongStateOf(0L)
        private set

    var inferences by mutableLongStateOf(0L)
        private set

    var motionMs by mutableLongStateOf(0L)
        private set

    var trackingFps by mutableFloatStateOf(0f)
        private set

    var transforms by mutableStateOf<Map<String, FloatArray>>(emptyMap())
        private set

    var error by mutableStateOf<String?>(null)
    var generation by mutableStateOf(0L)
        private set

    private var image: VideoFrame? = null

    fun filter(values: Set<Int>) {
        categories = values.filter { it in 0..79 }.toSet()
        generation++
        clearFrame()
        prefs.edit().putStringSet("categories", categories.map { it.toString() }.toSet()).apply()
    }

    fun autoOrientation(value: Boolean) {
        automaticOrientation = value
        generation++
        clearFrame()
        prefs.edit().putBoolean("autoOrientation", value).apply()
    }

    fun rotate() {
        manualRotation = (manualRotation + 90) % 360
        generation++
        clearFrame()
        prefs.edit().putInt("manualRotation", manualRotation).apply()
    }

    fun hint(degrees: Int) {
        val value = YoloOrientation.snapped(degrees, orientationHint)
        if (value != orientationHint) {
            orientationHint = value
            if (automaticOrientation) {
                generation++
                clearFrame()
            }
        }
    }

    fun confidence(value: Float) {
        threshold = value.coerceIn(.2f, .8f)
        generation++
        clearFrame()
        prefs.edit().putFloat("threshold", threshold).apply()
    }

    fun pause() {
        paused = !paused
        generation++
        clearFrame()
    }

    fun clearFrame() {
        marks = emptyList()
        transforms = emptyMap()
        image?.release()
        image = null
    }

    internal fun publish(output: VideoOutput) {
        if (output.generation != generation || paused) {
            output.frame.release()
            return
        }
        image?.release()
        image = output.frame
        marks = output.marks
        transforms = output.transforms
        frames++
        inferences = output.inferences
        latency = output.inferenceMs
        motionMs = output.motionMs
        trackingFps = output.fps
        rotationUsed = output.rotation
        status = output.accelerator?.let { "YOLO · $it" } ?: "Loading…"
    }

    internal fun snapshotForSave(): Pair<Bitmap, List<Mark>>? {
        val bitmap = image?.bitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        return bitmap to marks.mapNotNull { m -> transforms[m.id]?.let { warpedMark(m, it) } }
    }

    fun save() {
        if (saving || marks.isEmpty()) return
        val (bitmap, snapshotMarks) = snapshotForSave() ?: return
        saving = true
        viewModelScope.launch {
            try {
                val entry =
                    HistoryStore(getApplication())
                        .save(
                            bitmap,
                            snapshotMarks,
                            Skill.SEGMENT,
                            "YOLO · " + categoryLabel,
                            "Local YOLO · ${snapshotMarks.size} objects",
                        )
                error =
                    if (entry.galleryUri != null) "Saved to the gallery and history."
                    else "Saved to history. Could not export to the gallery."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Could not save."
            } finally {
                bitmap.recycle()
                saving = false
            }
        }
    }

    override fun onCleared() {
        clearFrame()
        super.onCleared()
    }
}
