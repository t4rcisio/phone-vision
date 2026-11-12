package com.example.vision

import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** Validates SVG path input before the renderer treats it as a successful segmentation mask. */
internal object SegmentMask {
    fun parse(value: String): Path? = runCatching {
        val data =
            if (value.trimStart().startsWith("<")) {
                Regex("""<path\b[^>]*\bd\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(value)
                    .map { it.groupValues[1] }
                    .joinToString(" ")
            } else value.trim()
        require(data.startsWith("M", true))
        val path = requireNotNull(PathParser.createPathFromPathData(data))
        val bounds = RectF()
        path.computeBounds(bounds, true)
        require(!path.isEmpty && bounds.width() > 0 && bounds.height() > 0)
        path
    }
        .getOrNull()
}
