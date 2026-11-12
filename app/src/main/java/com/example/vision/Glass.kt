package com.example.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

internal val GlassNavy = Color(0xFF111B30)
internal val Ice = Color(0xFFBECFFF)

@Composable
internal fun VisionAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Ice,
                background = GlassNavy,
                surface = GlassNavy,
                onPrimary = Color(0xFF17254B),
                onSurface = Color(0xFFF4F6FF),
                onBackground = Color(0xFFF4F6FF),
                onSurfaceVariant = Color(0xFFD5DDF1),
            ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}

internal data class CameraBackdrop(val image: ImageBitmap, val bounds: Rect)

internal val LocalCameraBackdrop = staticCompositionLocalOf<CameraBackdrop?> { null }

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val source = LocalCameraBackdrop.current
    var panel by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier
            .clip(shape)
            .onGloballyPositioned { panel = it.boundsInWindow() }
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .32f),
                        Color(0xFF9DAEFF).copy(alpha = .12f),
                        Color.White.copy(alpha = .18f),
                    ),
                ),
                shape,
            ),
    ) {
        if (source != null && source.bounds.width > 0 && panel.width > 0)
            Canvas(Modifier.matchParentSize().blur(18.dp)) {
                val image = source.image
                val x =
                    ((panel.left - source.bounds.left) / source.bounds.width * image.width)
                        .roundToInt()
                        .coerceIn(0, image.width - 1)
                val y =
                    ((panel.top - source.bounds.top) / source.bounds.height * image.height)
                        .roundToInt()
                        .coerceIn(0, image.height - 1)
                val w =
                    (panel.width / source.bounds.width * image.width)
                        .roundToInt()
                        .coerceIn(1, image.width - x)
                val h =
                    (panel.height / source.bounds.height * image.height)
                        .roundToInt()
                        .coerceIn(1, image.height - y)
                drawImage(
                    image,
                    srcOffset = IntOffset(x, y),
                    srcSize = IntSize(w, h),
                    dstSize =
                        IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        ),
                    filterQuality = FilterQuality.Low,
                )
            }
        Box(
            Modifier.matchParentSize()
                .background(Brush.linearGradient(listOf(Color(0xD9263658), Color(0xED10192B)))),
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
internal fun rememberArBackdrop(view: SurfaceView?, bounds: Rect): CameraBackdrop? {
    val lifecycle = LocalLifecycleOwner.current
    var backdrop by remember { mutableStateOf<CameraBackdrop?>(null) }
    val currentBounds by rememberUpdatedState(bounds)
    LaunchedEffect(view, lifecycle) {
        if (view == null) return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (view.width > 0 && view.height > 0 && view.holder.surface.isValid) {
                    val bitmap =
                        Bitmap.createBitmap(
                            160,
                            (160f * view.height / view.width).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                    val ok =
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            runCatching {
                                PixelCopy.request(
                                    view,
                                    bitmap,
                                    { result ->
                                        if (continuation.isActive)
                                            continuation.resume(result == PixelCopy.SUCCESS)
                                    },
                                    Handler(Looper.getMainLooper()),
                                )
                            }
                                .onFailure { if (continuation.isActive) continuation.resume(false) }
                        }
                    if (ok) backdrop = CameraBackdrop(bitmap.asImageBitmap(), currentBounds)
                    else bitmap.recycle()
                }
                delay(500)
            }
        }
    }
    return backdrop
}
