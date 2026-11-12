package com.example.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

class ObjectTrackingActivity : ComponentActivity() {
    internal var cameraPreview: PreviewView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val vm = ViewModelProvider(this)[ObjectTrackingModel::class.java]
        setContent { VisionAppTheme { TrackingScreen(vm) } }
    }

    @Composable
    private fun TrackingScreen(vm: ObjectTrackingModel) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val listener =
                object :
                    OrientationEventListener(
                        this@ObjectTrackingActivity,
                        android.hardware.SensorManager.SENSOR_DELAY_UI,
                    ) {
                    override fun onOrientationChanged(orientation: Int) {
                        vm.hint(orientation)
                    }
                }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) listener.enable()
                else if (event == Lifecycle.Event.ON_PAUSE) listener.disable()
            }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) listener.enable()
            onDispose {
                listener.disable()
                owner.lifecycle.removeObserver(observer)
            }
        }
        var permitted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        val permission =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                permitted =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
            }
        fun requestPermission() {
            permission.launch(
                if (android.os.Build.VERSION.SDK_INT <= 28)
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else arrayOf(Manifest.permission.CAMERA),
            )
        }
        LaunchedEffect(Unit) { if (!permitted) requestPermission() }
        var preview by remember { mutableStateOf<PreviewView?>(null) }
        var cameraBounds by remember { mutableStateOf(Rect.Zero) }
        var backdrop by remember { mutableStateOf<CameraBackdrop?>(null) }
        var menu by remember { mutableStateOf(false) }
        var categories by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.error) {
            vm.error?.let {
                snack.showSnackbar(it)
                if (vm.error == it) vm.error = null
            }
        }
        val pipeline = remember {
            LiveYoloPipeline(
                this@ObjectTrackingActivity,
                onOutput = { output ->
                    vm.publish(output)
                    output.thumbnail?.let {
                        backdrop = CameraBackdrop(it.asImageBitmap(), cameraBounds)
                    }
                },
                onError = { vm.error = it },
            )
        }
        val options =
            YoloOptions(
                vm.generation,
                vm.categories,
                vm.threshold,
                vm.automaticOrientation,
                vm.orientationHint,
                vm.manualRotation,
                vm.paused,
            )
        SideEffect { pipeline.options = options }
        DisposableEffect(owner, pipeline) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) pipeline.setActive(true)
                else if (event == Lifecycle.Event.ON_PAUSE) {
                    pipeline.setActive(false)
                    vm.clearFrame()
                }
            }
            owner.lifecycle.addObserver(observer)
            pipeline.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            onDispose {
                owner.lifecycle.removeObserver(observer)
                pipeline.close()
                vm.clearFrame()
            }
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
                    if (permitted)
                        TrackingCameraFeed(
                            pipeline,
                            {
                                preview = it
                                cameraPreview = it
                            },
                            { vm.error = it },
                        )
                    Canvas(Modifier.fillMaxSize().clipToBounds()) {
                        if (!vm.paused) renderMarks(vm.marks, transforms = vm.transforms)
                    }
                    if (!permitted)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CameraAction("Allow camera", ::requestPermission)
                        }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraAction("‹ Back", { finish() })
                    GlassSurface(shape = CircleShape) {
                        Text(
                            if (vm.paused) "Paused" else vm.status,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    CameraAction("⋯", { menu = true })
                }
                GlassSurface(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                    RoundedCornerShape(28.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { categories = true },
                            modifier =
                                Modifier.weight(1f).semantics {
                                    contentDescription = "YOLO categories"
                                },
                        ) {
                            Text(
                                vm.categoryLabel + " ⌄",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { vm.pause() }) {
                            Text(if (vm.paused) "Resume" else "Pause")
                        }
                        Button(
                            onClick = { vm.save() },
                            enabled = vm.marks.isNotEmpty() && !vm.saving,
                            shape = CircleShape,
                        ) {
                            Text(if (vm.saving) "Saving…" else "Save")
                        }
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = 70.dp))
            }
            if (menu)
                GlassSheet("Local YOLO", { menu = false }) {
                    Column(
                        Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Confidence · ${(vm.threshold*100).toInt()}%")
                        var confidence by remember { mutableFloatStateOf(vm.threshold) }
                        Slider(
                            confidence,
                            { confidence = it },
                            onValueChangeFinished = { vm.confidence(confidence) },
                            valueRange = 0.2f..0.8f,
                        )
                        Text(
                            "${vm.marks.size} objects · ${vm.latency} ms",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "YOLO26 nano · 80 categories · Offline",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Automatic orientation")
                            Switch(vm.automaticOrientation, { vm.autoOrientation(it) })
                        }
                        if (!vm.automaticOrientation)
                            CameraAction("Rotate 90° · ${vm.manualRotation}°", { vm.rotate() })
                    }
                }
            if (categories)
                GlassSheet("Objects", { categories = false }) {
                    var draft by remember { mutableStateOf(vm.categories) }
                    OutlinedTextField(
                        search,
                        { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        singleLine = true,
                        placeholder = { Text("Search categories") },
                        shape = RoundedCornerShape(18.dp),
                    )
                    Column(
                        Modifier.fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                    ) {
                        GlassMenuItem(
                            "All",
                            R.drawable.ic_tracking,
                            { draft = emptySet() },
                            selected = draft.isEmpty(),
                        )
                        YoloLabels.withIndex()
                            .filter { it.value.contains(search, true) }
                            .sortedBy { it.value }
                            .forEach { (index, label) ->
                                fun toggle() {
                                    draft = if (index in draft) draft - index else draft + index
                                }
                                GlassMenuItem(
                                    label,
                                    R.drawable.ic_segment,
                                    ::toggle,
                                    selected = index in draft,
                                    trailingIcon = { Checkbox(index in draft, { toggle() }) },
                                )
                            }
                    }
                    Button(
                        onClick = {
                            vm.filter(draft)
                            categories = false
                        },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = CircleShape,
                    ) {
                        Text(
                            if (draft.isEmpty()) "Apply · All"
                            else "Apply · ${draft.size} selected",
                        )
                    }
                }
        }
    }
}
