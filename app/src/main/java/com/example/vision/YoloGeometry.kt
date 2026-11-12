package com.example.vision

import kotlin.math.*

internal data class YoloBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    val cx
        get() = (left + right) / 2

    val cy
        get() = (top + bottom) / 2

    fun iou(other: YoloBox): Float {
        val intersection =
            max(0f, min(right, other.right) - max(left, other.left)) *
                max(0f, min(bottom, other.bottom) - max(top, other.top))
        return intersection /
            (width * height + other.width * other.height - intersection).coerceAtLeast(1e-8f)
    }

    fun translated(x: Float, y: Float) =
        copy(left = left + x, right = right + x, top = top + y, bottom = bottom + y)
}

internal data class Letterbox(val width: Int, val height: Int, val edge: Int = 640) {
    val scale = min(edge.toFloat() / width, edge.toFloat() / height)
    val scaledWidth = (width * scale).roundToInt()
    val scaledHeight = (height * scale).roundToInt()
    val padX = (edge - scaledWidth) / 2
    val padY = (edge - scaledHeight) / 2

    fun x(model: Float) = ((model * edge - padX) / scaledWidth).coerceIn(0f, 1f)

    fun y(model: Float) = ((model * edge - padY) / scaledHeight).coerceIn(0f, 1f)

    fun box(cx: Float, cy: Float, w: Float, h: Float) =
        YoloBox(x(cx - w / 2), y(cy - h / 2), x(cx + w / 2), y(cy + h / 2))
}

internal data class YoloCandidate(
    val index: Int,
    val category: Int,
    val score: Float,
    val box: YoloBox,
)

internal data class YoloDetection(
    val category: Int,
    val score: Float,
    val box: YoloBox,
    val path: String,
)

internal object YoloDecode {
    const val ANCHORS = 8400
    const val CLASSES = 80

    fun candidates(
        raw: FloatArray,
        letterbox: Letterbox,
        threshold: Float,
        categories: Set<Int>,
    ): List<YoloCandidate> {
        require(raw.size == 116 * ANCHORS)
        val found = ArrayList<YoloCandidate>()
        for (i in 0 until ANCHORS) {
            var best = -1
            var score = threshold
            for (c in 0 until CLASSES) {
                val value = raw[(4 + c) * ANCHORS + i]
                if (
                    (categories.isEmpty() || c in categories) && value.isFinite() && value > score
                ) {
                    best = c
                    score = value
                }
            }
            if (best < 0) continue
            val values =
                floatArrayOf(raw[i], raw[ANCHORS + i], raw[2 * ANCHORS + i], raw[3 * ANCHORS + i])
            if (values.any { !it.isFinite() } || values[2] <= 0 || values[3] <= 0) continue
            val box = letterbox.box(values[0], values[1], values[2], values[3])
            if (box.width > .005 && box.height > .005) found += YoloCandidate(i, best, score, box)
        }
        val selected = mutableListOf<YoloCandidate>()
        for (item in found.sortedByDescending { it.score }.take(300)) {
            if (selected.none { it.category == item.category && it.box.iou(item.box) > .5f })
                selected += item
            if (selected.size == 12) break
        }
        return selected
    }
}

/** Motion prediction + class-gated global greedy association; hidden immediately on a miss. */
internal class YoloTrackIds {
    private data class Track(
        val id: Int,
        var box: YoloBox,
        var time: Long,
        var vx: Float = 0f,
        var vy: Float = 0f,
    )

    private val tracks = mutableMapOf<Int, Pair<Int, Track>>()
    private var nextId = 1

    fun update(detections: List<YoloDetection>, time: Long): List<Pair<Int, YoloDetection>> {
        tracks.entries.removeAll { time - it.value.second.time > 900 }
        data class Pairing(val id: Int, val index: Int, val cost: Float)
        val pairs = mutableListOf<Pairing>()
        for ((id, pair) in tracks) for ((index, detection) in detections.withIndex()) {
            if (pair.first != detection.category) continue
            val t = pair.second
            val dt = (time - t.time).coerceIn(0, 500) / 1000f
            val predicted = t.box.translated(t.vx * dt, t.vy * dt)
            val distance = hypot(predicted.cx - detection.box.cx, predicted.cy - detection.box.cy)
            val iou = predicted.iou(detection.box)
            val areaRatio =
                detection.box.width * detection.box.height /
                    (t.box.width * t.box.height).coerceAtLeast(1e-6f)
            if (
                areaRatio in .25f..4f &&
                    (iou > .08 || distance < max(.045f, hypot(t.box.width, t.box.height) * .45f))
            )
                pairs += Pairing(id, index, 1 - iou + distance)
        }
        val matches = mutableMapOf<Int, Int>()
        val used = mutableSetOf<Int>()
        for (p in pairs.sortedBy { it.cost }) if (p.id !in used && p.index !in matches) {
            used += p.id
            matches[p.index] = p.id
        }
        return detections.mapIndexed { index, detection ->
            val id = matches[index] ?: nextId++
            val t = tracks[id]?.second
            if (t != null) {
                val dt = ((time - t.time) / 1000f).coerceAtLeast(.015f)
                t.vx = .4f * t.vx + .6f * (detection.box.cx - t.box.cx) / dt
                t.vy = .4f * t.vy + .6f * (detection.box.cy - t.box.cy) / dt
                t.box = detection.box
                t.time = time
            } else tracks[id] = detection.category to Track(id, detection.box, time)
            id to detection
        }
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }
}
