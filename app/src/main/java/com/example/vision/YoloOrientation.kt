package com.example.vision

/** Preview stays in portrait; inference is upright and all vertices return to preview space. */
internal object YoloOrientation {
    fun snapped(degrees: Int, previous: Int): Int {
        if (degrees < 0) return previous
        val nearest = ((degrees + 45) / 90 * 90) % 360
        val distance = kotlin.math.abs((degrees - nearest + 540) % 360 - 180)
        return if (distance <= 30) nearest else previous
    }

    private fun inverse(x: Float, y: Float, rotation: Int): XY =
        when (rotation) {
            90 -> XY(y, 1 - x)
            180 -> XY(1 - x, 1 - y)
            270 -> XY(1 - y, x)
            else -> XY(x, y)
        }

    fun preview(detection: YoloDetection, clockwise: Int): YoloDetection {
        if (clockwise == 0) return detection
        val b = detection.box
        val corners =
            listOf(
                inverse(b.left, b.top, clockwise),
                inverse(b.right, b.top, clockwise),
                inverse(b.right, b.bottom, clockwise),
                inverse(b.left, b.bottom, clockwise),
            )
        val box =
            YoloBox(
                corners.minOf { it.x },
                corners.minOf { it.y },
                corners.maxOf { it.x },
                corners.maxOf { it.y },
            )
        val tokens = Regex("([ML])([-+0-9.eE]+)\\s+([-+0-9.eE]+)|Z").findAll(detection.path)
        val path = buildString {
            for (token in tokens) {
                if (token.value == "Z") append("Z")
                else {
                    val p =
                        inverse(
                            b.left + token.groupValues[2].toFloat() * b.width,
                            b.top + token.groupValues[3].toFloat() * b.height,
                            clockwise,
                        )
                    append(token.groupValues[1])
                    append(
                        String.format(
                            java.util.Locale.US,
                            "%.4f %.4f",
                            ((p.x - box.left) / box.width).coerceIn(0f, 1f),
                            ((p.y - box.top) / box.height).coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        }
        return detection.copy(box = box, path = path)
    }
}

/** Also recovers sideways video/content when gravity alone does not explain image orientation. */
internal class YoloAutoOrientation {
    private var hint = -1
    private var index = 0
    private var misses = 0

    fun next(deviceHint: Int): Int {
        if (deviceHint != hint) {
            hint = deviceHint
            index = 0
            misses = 0
        }
        return (hint + intArrayOf(0, 90, 270, 180)[index]) % 360
    }

    fun observe(found: Boolean) {
        if (found) misses = 0
        else {
            misses++
            if (misses >= 2) {
                index = (index + 1) % 4
                misses = 0
            }
        }
    }

    fun reset() {
        hint = -1
        index = 0
        misses = 0
    }
}
