package com.example.vision

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraFeed(onReady: (PreviewView) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val latestError by rememberUpdatedState(onError)
    DisposableEffect(owner, view) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (!disposed)
                    runCatching {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        val useCase =
                            Preview.Builder().build().also {
                                it.surfaceProvider = view.surfaceProvider
                            }
                        boundPreview = useCase
                        cameraProvider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            useCase,
                        )
                    }
                        .onFailure {
                            latestError("Could not start the rear camera. Use the gallery.")
                        }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            boundPreview?.let { provider?.unbind(it) }
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize(), update = { onReady(it) })
}

@Composable
fun LiveMarks(
    vm: VisionModel,
    rotation: List<Float>?,
    sensors: Boolean,
    selectedId: String? = null,
    transforms: Map<String, FloatArray> = emptyMap(),
    onPin: ((Float, Float) -> Unit)? = null,
    onSelect: (Mark?) -> Unit = {},
) {
    val latestRotation by rememberUpdatedState(rotation)
    val latestSelect by rememberUpdatedState(onSelect)
    val currentTransforms by rememberUpdatedState(transforms)
    val latestPin by rememberUpdatedState(onPin)
    val input =
        if (!vm.busy)
            Modifier.pointerInput(vm.busy, vm.manualMarking, sensors) {
                detectTapGestures { point ->
                    val hit =
                        vm.marks
                            .filter {
                                it.live &&
                                    it.right == null &&
                                    (it.imageReference == null || currentTransforms[it.id] != null)
                            }
                            .mapNotNull { mark ->
                                val matrix =
                                    overlayMatrix(
                                        mark,
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        latestRotation,
                                        sensors,
                                        currentTransforms[mark.id],
                                    ) ?: return@mapNotNull null
                                val p = floatArrayOf(mark.x, mark.y)
                                matrix.mapPoints(p)
                                mark to (Offset(p[0], p[1]) - point).getDistance()
                            }
                            .filter { it.second <= 30.dp.toPx() }
                            .minByOrNull { it.second }
                            ?.first
                    latestSelect(hit)
                    if (hit == null && vm.manualMarking) {
                        if (latestPin != null)
                            latestPin!!.invoke(point.x / size.width, point.y / size.height)
                        else
                            vm.pin(
                                point.x / size.width,
                                point.y / size.height,
                                latestRotation,
                                size.width.toFloat() / size.height,
                            )
                    }
                }
            }
        else Modifier
    Canvas(Modifier.fillMaxSize().clipToBounds().then(input)) {
        vm.aspect = size.width / size.height
        renderMarks(vm.marks, if (sensors) rotation else null, sensors, transforms, liveMode = true)
        vm.marks
            .firstOrNull { it.id == selectedId }
            ?.let { mark ->
                overlayMatrix(mark, size.width, size.height, rotation, sensors, transforms[mark.id])
                    ?.let { matrix ->
                        val p = floatArrayOf(mark.x, mark.y)
                        matrix.mapPoints(p)
                        drawCircle(
                            Color.White,
                            16.dp.toPx(),
                            Offset(p[0], p[1]),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
            }
    }
}

@Composable
fun Snapshot(
    bitmap: Bitmap,
    marks: List<Mark>,
    manualMarking: Boolean = false,
    onSelect: ((Mark?) -> Unit)? = null,
    selectedId: String? = null,
    onPin: (Float, Float) -> Unit = { _, _ -> },
) {
    val latestPin by rememberUpdatedState(onPin)
    val latestSelect by rememberUpdatedState(onSelect)
    val currentMarks by rememberUpdatedState(marks)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val aspect = bitmap.width.toFloat() / bitmap.height
        val fitWidth = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
        val fitHeight = fitWidth / aspect
        Box(Modifier.size(fitWidth, fitHeight).align(Alignment.Center)) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Image selected for analysis",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            val input =
                if (manualMarking || onSelect != null)
                    Modifier.pointerInput(manualMarking, onSelect != null) {
                        detectTapGestures { point ->
                            val hit =
                                currentMarks
                                    .filter { it.right == null }
                                    .map {
                                        it to
                                            (Offset(it.x * size.width, it.y * size.height) - point)
                                                .getDistance()
                                    }
                                    .filter { it.second <= 30.dp.toPx() }
                                    .minByOrNull { it.second }
                                    ?.first
                            latestSelect?.invoke(hit)
                            if (hit == null && manualMarking)
                                latestPin(point.x / size.width, point.y / size.height)
                        }
                    }
                else Modifier
            Canvas(Modifier.fillMaxSize().clipToBounds().then(input)) {
                renderMarks(marks)
                marks
                    .firstOrNull { it.id == selectedId }
                    ?.let {
                        drawCircle(
                            Color.White,
                            16.dp.toPx(),
                            Offset(it.x * size.width, it.y * size.height),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
            }
        }
    }
}
