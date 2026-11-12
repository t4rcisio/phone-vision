package com.example.vision

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Region

/** Converts a delayed response using only the frozen capture's geometry. No live hit tests. */
internal object CaptureGeometry {
    fun contours(mark: Mark): List<List<XY>> {
        val right = mark.right ?: return listOf(listOf(XY(mark.x, mark.y)))
        val bottom = mark.bottom ?: return emptyList()
        val path =
            mark.path?.let(SegmentMask::parse)
                ?: return listOf(
                    listOf(
                        XY(mark.x, mark.y),
                        XY(right, mark.y),
                        XY(right, bottom),
                        XY(mark.x, bottom),
                    ),
                )
        val measure = PathMeasure(path, false)
        val result = mutableListOf<List<XY>>()
        do {
            if (measure.length > 0) {
                val count = (measure.length * 160).toInt().coerceIn(24, 256)
                val point = FloatArray(2)
                result +=
                    (0..count).map { i ->
                        measure.getPosTan(measure.length * i / count, point, null)
                        XY(
                            mark.x + point[0] * (right - mark.x),
                            mark.y + point[1] * (bottom - mark.y),
                        )
                    }
            }
        } while (measure.nextContour() && result.size < 32)
        return result
    }

    fun reconstruct(mark: Mark, source: ArSource): List<List<FloatArray>>? {
        if (source.depth == null) return null
        val path = Path()
        if (mark.right != null && mark.bottom != null) {
            val mask = mark.path?.let(SegmentMask::parse)
            if (mask != null) {
                mask.transform(
                    Matrix().apply {
                        setValues(
                            floatArrayOf(
                                mark.right - mark.x,
                                0f,
                                mark.x,
                                0f,
                                mark.bottom - mark.y,
                                mark.y,
                                0f,
                                0f,
                                1f,
                            ),
                        )
                    },
                )
                path.addPath(mask)
            } else path.addRect(mark.x, mark.y, mark.right, mark.bottom, Path.Direction.CW)
        }
        val scale = 4096f
        path.transform(Matrix().apply { setScale(scale, scale) })
        val region = Region().apply { setPath(path, Region(0, 0, 4096, 4096)) }
        val inside: ((Float, Float) -> Boolean)? =
            if (mark.right == null) null
            else { x, y -> region.contains((x * scale).toInt(), (y * scale).toInt()) }
        val result = mutableListOf<List<FloatArray>>()
        for (contour in contours(mark)) {
            val points = contour.map { source.cameraPoint(it.x, it.y, inside) ?: return null }
            if (points.isNotEmpty()) result += points
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
