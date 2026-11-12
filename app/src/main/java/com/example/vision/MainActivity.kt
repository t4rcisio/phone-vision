package com.example.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Mint = Color(0xFF8BE5C3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val vm = ViewModelProvider(this)[VisionModel::class.java]
        setContent {
            VisionAppTheme { VisionApp(vm) }
        }
    }
}

@Composable
private fun VisionApp(vm: VisionModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var camera by rememberSaveable { mutableStateOf(true) }
    var photo by rememberSaveable { mutableStateOf(false) }
    var api by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var cameraBounds by remember { mutableStateOf(Rect.Zero) }
    var backdrop by remember { mutableStateOf<CameraBackdrop?>(null) }
    var transforms by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    val tracker = remember { ImageTracker() }
    val currentMarks by rememberUpdatedState(vm.marks)
    var selected by remember { mutableStateOf<String?>(null) }
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val rotation = rememberRotation(camera && permitted && vm.sensors)
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted ->
            permitted =
                granted[Manifest.permission.CAMERA]
                    ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED)
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                camera = false
                photo = true
                selected = null
                vm.importImage(uri)
            }
        }
    LaunchedEffect(Unit) {
        val required = mutableListOf<String>()
        if (!permitted) required += Manifest.permission.CAMERA
        if (
            android.os.Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        )
            required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (required.isNotEmpty()) permission.launch(required.toTypedArray())
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !photo) {
                transforms = emptyMap()
                camera = true
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(tracker) { onDispose { tracker.close() } }
    LaunchedEffect(preview, camera, lifecycle) {
        val view = preview
        if (!camera || view == null) {
            transforms = emptyMap()
            return@LaunchedEffect
        }
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (view.previewStreamState.value == PreviewView.StreamState.STREAMING) {
                    val frame = view.bitmap
                    if (frame != null) {
                        try {
                            val thumb =
                                android.graphics.Bitmap.createScaledBitmap(
                                    frame,
                                    160,
                                    (160f * frame.height / frame.width).toInt().coerceAtLeast(1),
                                    true,
                                )
                            backdrop = CameraBackdrop(thumb.asImageBitmap(), cameraBounds)
                            transforms =
                                withContext(Dispatchers.Default) {
                                    runCatching { tracker.update(frame, currentMarks) }
                                        .getOrDefault(emptyMap())
                                }
                        } finally {
                            frame.recycle()
                        }
                    }
                }
                delay(240)
            }
        }
    }
    CameraError(vm, snackbar)
    fun query() {
        if (!vm.canAsk()) return
        if (camera) {
            val frame =
                preview
                    ?.takeIf { it.previewStreamState.value == PreviewView.StreamState.STREAMING }
                    ?.bitmap
            if (frame == null) {
                vm.error = "The camera is not ready."
                return
            }
            vm.selectImage(frame, rotation, reference2d = ImageReference(frame))
        }
        selected = null
        vm.ask()
    }
    CompositionLocalProvider(LocalCameraBackdrop provides backdrop) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Box(
                Modifier.fillMaxSize().clipToBounds().onGloballyPositioned {
                    cameraBounds = it.boundsInWindow()
                },
            ) {
                if (camera && permitted) {
                    CameraFeed({ preview = it }, { vm.error = it })
                    LiveMarks(
                        vm,
                        rotation,
                        false,
                        selected,
                        transforms,
                        onPin = { x, y ->
                            preview?.bitmap?.let { frame ->
                                val reference = ImageReference(frame)
                                vm.pin(x, y, null, vm.aspect, reference)
                                if (reference.image !== frame) frame.recycle()
                            }
                        },
                    ) {
                        selected = it?.id
                    }
                } else if (!camera && vm.image != null) {
                    Snapshot(
                        vm.image!!,
                        vm.marks,
                        vm.manualMarking && !vm.busy,
                        onSelect = { selected = it?.id },
                        selectedId = selected,
                    ) { x, y ->
                        vm.pin(x, y, vm.imagePose, vm.image!!.width.toFloat() / vm.image!!.height)
                    }
                }
            }
            CameraHeader(
                false,
                permitted,
                on2d = {
                    photo = false
                    camera = true
                    if (!permitted) permission.launch(arrayOf(Manifest.permission.CAMERA))
                },
                on3d = {
                    camera = false
                    photo = false
                    context.startActivity(Intent(context, ArActivity::class.java))
                },
                menu = { close ->
                    GlassMenuItem(
                        "Track objects",
                        R.drawable.ic_tracking,
                        onClick = {
                            close()
                            camera = false
                            photo = false
                            context.startActivity(
                                Intent(context, ObjectTrackingActivity::class.java),
                            )
                        },
                    )
                    GlassMenuItem(
                        "History",
                        R.drawable.ic_history,
                        onClick = {
                            close()
                            vm.refreshHistory()
                            historyOpen = true
                        },
                    )
                    GlassMenuItem(
                        "API",
                        R.drawable.ic_key,
                        onClick = {
                            close()
                            api = true
                        },
                    )
                    GlassMenuItem(
                        "Gallery",
                        R.drawable.ic_gallery,
                        onClick = {
                            close()
                            gallery.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                    if (!camera)
                        GlassMenuItem(
                            "Camera",
                            R.drawable.ic_gallery,
                            onClick = {
                                photo = false
                                camera = true
                                close()
                            },
                        )
                    GlassMenuItem(
                        "Undo point",
                        R.drawable.ic_undo,
                        onClick = {
                            vm.marks.lastOrNull { it.manual }?.let { vm.removeMarks(setOf(it.id)) }
                            selected = null
                            close()
                        },
                    )
                    GlassMenuItem(
                        "Clear all",
                        R.drawable.ic_delete,
                        onClick = {
                            vm.clear()
                            selected = null
                            close()
                        },
                    )
                },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraAction(
                        "Point",
                        {
                            vm.manualMarking = true
                            val aspect =
                                if (camera) vm.aspect
                                else vm.image?.let { it.width.toFloat() / it.height } ?: .75f
                            val reference =
                                if (camera)
                                    preview?.bitmap?.let { frame ->
                                        ImageReference(frame).also {
                                            if (it.image !== frame) frame.recycle()
                                        }
                                    }
                                else null
                            vm.pin(.5f, .5f, null, aspect, reference)
                        },
                        enabled = !vm.busy,
                    )
                    CameraAction(
                        "Tap",
                        { vm.manualMarking = !vm.manualMarking },
                        selected = vm.manualMarking,
                        enabled = !vm.busy,
                    )
                    Spacer(Modifier.weight(1f))
                    if (vm.image != null) CameraAction("Results", { results = true })
                }
                if (selected != null)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CameraAction(
                            "Delete point",
                            {
                                selected?.let { vm.removeMarks(setOf(it)) }
                                selected = null
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        CameraAction("Cancel", { selected = null })
                    }
                CameraComposer(vm, false, !camera || permitted, ::query, { results = true })
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp)
                    .padding(top = 70.dp),
            )
        }
    }
    if (historyOpen) HistorySheet(vm) { historyOpen = false }
    if (api) ApiSettings(vm) { api = false }
    if (results) CameraResultSheet(vm) { results = false }
}
