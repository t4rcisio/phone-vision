package com.example.vision

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

/** Maps the entire shape, not just its origin, from normalized image space into the preview. */
internal fun overlayMatrix(
    mark: Mark,
    width: Float,
    height: Float,
    rotation: List<Float>?,
    sensors: Boolean,
    transform: FloatArray? = null,
): Matrix? {
    if (transform != null)
        return Matrix().apply {
            setValues(transform)
            postScale(width, height)
        }
    if (!sensors) return Matrix().apply { setScale(width, height) }
    val reference = mark.reference ?: return null
    val current = rotation ?: return null
    val source = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    val target = FloatArray(8)
    for (i in 0..3) {
        val world = Spatial.world(source[2 * i], source[2 * i + 1], width / height, reference)
        val point = Spatial.project(world, width / height, current, clip = false) ?: return null
        target[2 * i] = point.x * width
        target[2 * i + 1] = point.y * height
    }
    return Matrix().takeIf { it.setPolyToPoly(source, 0, target, 0, 4) }
}

internal fun DrawScope.renderMarks(
    marks: List<Mark>,
    rotation: List<Float>? = null,
    sensors: Boolean = false,
    transforms: Map<String, FloatArray>? = null,
    liveMode: Boolean = false,
) {
    val colors =
        intArrayOf(0xFF8BE5C3.toInt(), 0xFF70C9FF.toInt(), 0xFFFFD271.toInt(), 0xFFF4A2D5.toInt())
    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        canvas.save()
        canvas.clipRect(0f, 0f, size.width, size.height)
        marks.forEachIndexed { index, mark ->
            if (
                liveMode &&
                    (!mark.live ||
                        (mark.imageReference != null && transforms?.get(mark.id) == null))
            )
                return@forEachIndexed
            val matrix =
                overlayMatrix(
                    mark,
                    size.width,
                    size.height,
                    rotation,
                    sensors,
                    transforms?.get(mark.id),
                ) ?: return@forEachIndexed
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors[index % colors.size]
                    strokeWidth = 2.dp.toPx()
                    style = Paint.Style.STROKE
                }
            val path = Path()
            if (mark.path != null && mark.right != null && mark.bottom != null) {
                val mask = SegmentMask.parse(mark.path)
                if (mask != null) {
                    // Moondream's SVG coordinates are relative to bbox, NOT to the full image.
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
                    mask.transform(matrix)
                    paint.style = Paint.Style.FILL
                    paint.alpha = 75
                    canvas.drawPath(mask, paint)
                    paint.style = Paint.Style.STROKE
                    paint.alpha = 255
                    canvas.drawPath(mask, paint)
                }
            }
            if (mark.path == null && mark.right != null && mark.bottom != null) {
                path.addRect(mark.x, mark.y, mark.right, mark.bottom, Path.Direction.CW)
                path.transform(matrix)
                canvas.drawPath(path, paint)
            }
            val location = floatArrayOf(mark.x, mark.y)
            matrix.mapPoints(location)
            val x = location[0]
            val y = location[1]
            if (!x.isFinite() || !y.isFinite()) return@forEachIndexed
            if (mark.right == null) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 5.dp.toPx(), paint)
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x, y, 10.dp.toPx(), paint)
            }
            val kind =
                if (mark.path != null) "Mask"
                else if (mark.right != null) "Box" else if (mark.manual) "Manual" else "Point"
            // Labels may contain '%' (YOLO confidence or user text); only format constant strings.
            val coordinates =
                String.format(java.util.Locale.getDefault(), "(%.2f, %.2f)", mark.x, mark.y)
            val label =
                if (mark.id.startsWith("yolo-")) mark.label.take(48)
                else "${index + 1} · $kind · ${mark.label.take(24)} $coordinates"
            paint.style = Paint.Style.FILL
            paint.textSize = 11.dp.toPx()
            val labelWidth = paint.measureText(label) + 10.dp.toPx()
            val lx = x.coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))
            val ly = (y - 7.dp.toPx()).coerceIn(22.dp.toPx(), size.height)
            val color = paint.color
            paint.color = 0xDD09131B.toInt()
            canvas.drawRoundRect(
                lx,
                ly - 17.dp.toPx(),
                lx + labelWidth,
                ly + 4.dp.toPx(),
                4.dp.toPx(),
                4.dp.toPx(),
                paint,
            )
            paint.color = color
            canvas.drawText(label, lx + 5.dp.toPx(), ly, paint)
        }
        canvas.restore()
    }
}
