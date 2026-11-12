package com.example.vision

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class ArActivity : ComponentActivity() {
    private var session: Session? = null
    private var surface: GLSurfaceView? = null
    private var engine by mutableStateOf<ArEngine?>(null)
    private var status by mutableStateOf("Starting ARCore…")
    private var shapes by mutableStateOf<List<ArDrawing>>(emptyList())
    private var capturing by mutableStateOf(false)
    private var resultSheetOpen by mutableStateOf(false)
    private var installRequested = false
    private var permissionRequested = false
    private lateinit var vm: VisionModel
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openSession() else status = "Allow camera access to use 3D anchors."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm = ViewModelProvider(this)[VisionModel::class.java]
        // Restored XY annotations do not belong to this newly created AR coordinate system.
        setContent {
            VisionAppTheme {
                ArScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        openSession()
    }

    private fun openSession() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            if (!permissionRequested) {
                permissionRequested = true
                permission.launch(Manifest.permission.CAMERA)
            }
            return
        }
        try {
            if (session == null) {
                val availability = ArCoreApk.getInstance().checkAvailability(this)
                if (availability.isUnsupported) {
                    status = "This device does not support ARCore. Use the 2D camera."
                    return
                }
                if (
                    ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    status =
                        "Finish installing Google Play Services for AR, then return to the app."
                    return
                }
                val created = Session(this)
                created.configure(
                    Config(created).apply {
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                            depthMode = Config.DepthMode.AUTOMATIC
                    },
                )
                session = created
                val renderer = ArEngine(created, { status = it }, { shapes = it }, vm::addArPin)
                surface =
                    GLSurfaceView(this).apply {
                        preserveEGLContextOnPause = true
                        setEGLContextClientVersion(2)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                engine = renderer
            }
            if (engine?.active != true) {
                session?.resume()
                engine?.displayRotation = windowManager.defaultDisplay.rotation
                engine?.active = true
                surface?.onResume()
            }
        } catch (e: Exception) {
            status =
                "Could not start ARCore (${e.javaClass.simpleName}). Update Google Play Services for AR and try again."
        }
    }

    override fun onPause() {
        engine?.cancelCapture()
        engine?.active = false
        surface?.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun restartTracking() {
        engine?.active = false
        engine?.cancelCapture()
        surface?.onPause()
        session?.pause()
        engine?.resetCameraBinding()
        openSession()
    }

    internal fun queryCamera() {
        if (!vm.canAsk() || capturing) return
        val renderer =
            engine
                ?: run {
                    vm.error = "The camera is not ready."
                    return
                }
        capturing = true
        vm.error = null
        renderer.capture(
            { bitmap, source ->
                capturing = false
                vm.selectImage(bitmap, null, source, preserveManual = true)
                vm.ask()
            },
            { message ->
                capturing = false
                vm.error = message
            },
        )
    }

    @Composable
    private fun ArScreen() {
        val renderer = engine
        var cameraBounds by remember { mutableStateOf(Rect.Zero) }
        val backdrop = rememberArBackdrop(surface, cameraBounds)
        var historyOpen by remember { mutableStateOf(false) }
        var menuApi by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<String?>(null) }
        var details by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        SideEffect { renderer?.marks = vm.marks }
        CameraError(vm, snackbar)
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
                    surface?.let { view ->
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    }
                    ArOverlay(shapes, selected, !vm.busy && !capturing) { point, id ->
                        if (id != null) selected = id
                        else {
                            selected = null
                            if (vm.manualMarking) renderer?.pin(point.x, point.y)
                        }
                    }
                }
                CameraHeader(
                    true,
                    status.startsWith("3D active"),
                    on2d = { finish() },
                    on3d = {},
                    menu = { close ->
                        GlassMenuItem(
                            "Track objects",
                            R.drawable.ic_tracking,
                            onClick = {
                                close()
                                startActivity(
                                    android.content.Intent(
                                        this@ArActivity,
                                        ObjectTrackingActivity::class.java,
                                    ),
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
                                menuApi = true
                            },
                        )
                        GlassMenuItem(
                            "Undo point",
                            R.drawable.ic_undo,
                            onClick = {
                                if (shapes.any { !it.anchored }) renderer?.cancelPendingPin()
                                else
                                    vm.marks
                                        .lastOrNull { it.manual }
                                        ?.let { vm.removeMarks(setOf(it.id)) }
                                selected = null
                                close()
                            },
                        )
                        GlassMenuItem(
                            "Clear all",
                            R.drawable.ic_delete,
                            onClick = {
                                vm.clear()
                                shapes = emptyList()
                                selected = null
                                surface?.queueEvent { renderer?.detachAll() }
                                close()
                            },
                        )
                        GlassMenuItem(
                            "Restart",
                            R.drawable.ic_refresh,
                            onClick = {
                                close()
                                restartTracking()
                            },
                        )
                        GlassMenuItem(
                            "Tracking",
                            R.drawable.ic_tracking,
                            onClick = {
                                close()
                                details = true
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CameraAction(
                            "Anchor",
                            {
                                vm.manualMarking = true
                                renderer?.pin(.5f, .5f)
                            },
                            enabled = renderer?.active == true && !vm.busy && !capturing,
                        )
                        CameraAction(
                            "Tap",
                            { vm.manualMarking = !vm.manualMarking },
                            selected = vm.manualMarking,
                            enabled = !vm.busy && !capturing,
                        )
                        Spacer(Modifier.weight(1f))
                        if (vm.image != null) CameraAction("Results", { resultSheetOpen = true })
                    }
                    if (selected != null)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CameraAction(
                                "Delete point",
                                {
                                    if (selected == "pending") renderer?.cancelPendingPin()
                                    else selected?.let { vm.removeMarks(setOf(it)) }
                                    selected = null
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            CameraAction("Cancel", { selected = null })
                        }
                    CameraComposer(
                        vm,
                        capturing,
                        renderer?.active == true,
                        ::queryCamera,
                        { resultSheetOpen = true },
                    )
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
        if (menuApi) ApiSettings(vm) { menuApi = false }
        if (resultSheetOpen) CameraResultSheet(vm) { resultSheetOpen = false }
        if (details)
            GlassSheet("Tracking", { details = false }) {
                Text(status, Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp))
            }
    }
}
