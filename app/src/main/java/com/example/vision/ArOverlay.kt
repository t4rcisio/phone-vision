package com.example.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun ArOverlay(
    shapes: List<ArDrawing>,
    selected: String?,
    enabled: Boolean,
    onTap: (XY, String?) -> Unit,
) {
    val currentShapes by rememberUpdatedState(shapes)
    val currentTap by rememberUpdatedState(onTap)
    Canvas(
        Modifier.fillMaxSize().clipToBounds().pointerInput(enabled) {
            if (enabled)
                detectTapGestures { pixel ->
                    val limit = 30.dp.toPx()
                    val hit =
                        currentShapes
                            .filter { shape ->
                                shape.contours.size == 1 && shape.contours[0].size == 1
                            }
                            .map { shape ->
                                val point = shape.contours[0][0]
                                shape.id to
                                    (Offset(point.x * size.width, point.y * size.height) - pixel)
                                        .getDistance()
                            }
                            .filter { it.second <= limit }
                            .minByOrNull { it.second }
                    currentTap(XY(pixel.x / size.width, pixel.y / size.height), hit?.first)
                }
        },
    ) {
        drawArShapes(shapes, selected)
    }
}

internal fun DrawScope.drawArShapes(shapes: List<ArDrawing>, selected: String? = null) {
    clipRect {
        shapes.forEachIndexed { index, shape ->
            val color =
                if (!shape.anchored) Color(0xFFFFD271)
                else if (shape.manual) Mint
                else listOf(Mint, Color.Cyan, Color(0xFFF4A2D5))[index % 3]
            shape.contours.forEach { contour ->
                if (contour.size == 1) {
                    val p = Offset(contour[0].x * size.width, contour[0].y * size.height)
                    drawCircle(Color.Black.copy(alpha = .5f), 13.dp.toPx(), p)
                    drawCircle(color, 8.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    if (shape.id == selected)
                        drawCircle(Color.White, 16.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                } else {
                    val path =
                        Path().apply {
                            contour.forEachIndexed { i, p ->
                                if (i == 0) moveTo(p.x * size.width, p.y * size.height)
                                else lineTo(p.x * size.width, p.y * size.height)
                            }
                            close()
                        }
                    if (shape.mask) drawPath(path, color.copy(alpha = .2f))
                    drawPath(path, color, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}
