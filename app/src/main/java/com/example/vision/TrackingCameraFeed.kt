package com.example.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/** CPU camera frames in the same crop as PreviewView; no full-screen GPU bitmap readback. */
@Composable
internal fun TrackingCameraFeed(
    pipeline: LiveYoloPipeline,
    onReady: (PreviewView) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val error by rememberUpdatedState(onError)
    DisposableEffect(owner, view, pipeline) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var disposed = false
        fun bind() {
            if (
                disposed ||
                    provider == null ||
                    preview != null ||
                    view.width == 0 ||
                    view.height == 0
            )
                return
            val viewport = view.viewPort ?: return
            try {
                val cameraPreview =
                    Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analyzer =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                analyzer.setAnalyzer(executor) { image ->
                    var frame: Bitmap? = null
                    try {
                        if (pipeline.wantsFrames) {
                            val bitmap = image.toBitmap()
                            val crop = image.cropRect
                            val cropped =
                                Bitmap.createBitmap(
                                    bitmap,
                                    crop.left,
                                    crop.top,
                                    crop.width(),
                                    crop.height(),
                                )
                            val upright =
                                if (image.imageInfo.rotationDegrees == 0) cropped
                                else
                                    Bitmap.createBitmap(
                                        cropped,
                                        0,
                                        0,
                                        cropped.width,
                                        cropped.height,
                                        Matrix().apply {
                                            postRotate(image.imageInfo.rotationDegrees.toFloat())
                                        },
                                        true,
                                    )
                            if (bitmap !== upright && bitmap !== cropped) bitmap.recycle()
                            if (cropped !== upright) cropped.recycle()
                            frame = upright
                        }
                    } catch (e: Exception) {
                        logFailure("Camera frame conversion failed", e)
                    } finally {
                        image.close()
                    }
                    frame?.let(pipeline::frame)
                }
                provider!!.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder()
                        .setViewPort(viewport)
                        .addUseCase(cameraPreview)
                        .addUseCase(analyzer)
                        .build(),
                )
                preview = cameraPreview
                analysis = analyzer
            } catch (e: Exception) {
                logFailure("Camera binding failed", e)
                error("Could not start the tracking camera.")
            }
        }
        val layout =
            android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bind() }
        view.addOnLayoutChangeListener(layout)
        future.addListener(
            {
                if (!disposed) {
                    provider = future.get()
                    bind()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            view.removeOnLayoutChangeListener(layout)
            analysis?.clearAnalyzer()
            val cases = listOfNotNull(preview, analysis)
            if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize(), update = { onReady(it) })
}
