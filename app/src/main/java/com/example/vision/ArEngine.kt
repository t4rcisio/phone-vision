package com.example.vision

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ArDrawing(
    val label: String,
    val contours: List<List<XY>>,
    val mask: Boolean,
    val manual: Boolean,
    val anchored: Boolean = true,
    val id: String? = null,
)

/** Owns the AR camera. Never binds CameraX while this session is active. */
class ArEngine(
    val session: Session,
    val status: (String) -> Unit,
    val drawing: (List<ArDrawing>) -> Unit,
    val onPin: (Mark) -> Unit,
) : GLSurfaceView.Renderer {
    val sessionId = java.util.UUID.randomUUID().toString()
    @Volatile var marks: List<Mark> = emptyList()
    @Volatile var displayRotation = 0
    @Volatile var active = false
    private val main = Handler(Looper.getMainLooper())
    private val tap = AtomicReference<XY?>(null)

    private data class Capture(
        val success: (Bitmap, ArSource?) -> Unit,
        val error: (String) -> Unit,
    )

    private val capture = AtomicReference<Capture?>(null)
    @Volatile private var textureBound = false
    @Volatile private var geometryDirty = true
    private var appliedRotation = -1
    @Volatile
    var tracking = false
        private set

    @Volatile
    var frameCount = 0
        private set

    @Volatile private var lastDrawings: List<ArDrawing> = emptyList()
    private val bindings = mutableMapOf<String, Bound>()

    private data class CaptureSpace(val anchor: Anchor, val source: ArSource)

    private val captures = mutableMapOf<String, CaptureSpace>()
    private var latestCaptureId: String? = null
    private var width = 1
    private var height = 1
    private var texture = 0
    private var program = 0
    private var lastPublish = 0L
    private var lastStatus = ""
    private val vertices = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = buffer(FloatArray(8))

    private data class Bound(
        val anchor: Anchor,
        val local: List<List<FloatArray>>,
        val mark: Mark,
        val ownsAnchor: Boolean = true,
    )

    fun pin(x: Float, y: Float) {
        tap.set(XY(x, y))
        publish(lastDrawings.filter { it.anchored } + pending())
    }

    fun capture(callback: (Bitmap, ArSource?) -> Unit, onError: (String) -> Unit = { report(it) }) {
        if (!active) {
            onError("The camera is not ready. Please try again.")
            return
        }
        val request = Capture(callback, onError)
        if (!capture.compareAndSet(null, request)) {
            onError("A capture is already in progress.")
            return
        }
        main.postDelayed(
            {
                if (capture.compareAndSet(request, null))
                    request.error("No camera frame arrived. Restart tracking and try again.")
            },
            3500,
        )
    }

    fun cancelCapture() {
        capture.getAndSet(null)?.let {
            main.post { it.error("Capture interrupted. Try again after returning to the camera.") }
        }
    }

    fun cancelPendingPin() {
        tap.set(null)
        publish(lastDrawings.filter { it.anchored })
    }

    fun resetCameraBinding() {
        textureBound = false
        geometryDirty = true
    }

    private fun pending(): List<ArDrawing> =
        tap.get()?.let {
            listOf(
                ArDrawing(
                    "Waiting for a 3D surface",
                    listOf(listOf(it)),
                    false,
                    true,
                    false,
                    "pending",
                ),
            )
        } ?: emptyList()

    private fun publish(items: List<ArDrawing>) {
        lastDrawings = items
        main.post { drawing(items) }
    }

    private fun report(text: String) {
        if (text != lastStatus) {
            lastStatus = text
            main.post { status(text) }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GL.glGenTextures(1, textures, 0)
        texture = textures[0]
        textureBound = false
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GL.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL.GL_TEXTURE_MIN_FILTER,
            GL.GL_LINEAR,
        )
        GL.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL.GL_TEXTURE_MAG_FILTER,
            GL.GL_LINEAR,
        )
        GL.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL.GL_TEXTURE_WRAP_S,
            GL.GL_CLAMP_TO_EDGE,
        )
        GL.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL.GL_TEXTURE_WRAP_T,
            GL.GL_CLAMP_TO_EDGE,
        )
        fun shader(type: Int, code: String): Int =
            GL.glCreateShader(type).also {
                GL.glShaderSource(it, code)
                GL.glCompileShader(it)
                val result = IntArray(1)
                GL.glGetShaderiv(it, GL.GL_COMPILE_STATUS, result, 0)
                check(result[0] != 0) { GL.glGetShaderInfoLog(it) }
            }
        val vertex =
            shader(
                GL.GL_VERTEX_SHADER,
                "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv;}",
            )
        val fragment =
            shader(
                GL.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uCamera; varying vec2 vUv; void main(){gl_FragColor=texture2D(uCamera,vUv);}",
            )
        program = GL.glCreateProgram()
        GL.glAttachShader(program, vertex)
        GL.glAttachShader(program, fragment)
        GL.glLinkProgram(program)
        GL.glDeleteShader(vertex)
        GL.glDeleteShader(fragment)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        geometryDirty = true
        GL.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GL.glClearColor(0f, 0f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        if (!active) return
        var inFlight: Capture? = null
        try {
            if (!textureBound) {
                session.setCameraTextureName(texture)
                textureBound = true
            }
            if (geometryDirty || appliedRotation != displayRotation) {
                session.setDisplayGeometry(displayRotation, width, height)
                appliedRotation = displayRotation
                geometryDirty = false
            }
            val frame = session.update()
            if (frame.timestamp == 0L) return
            frameCount++
            vertices.position(0)
            uv.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                uv,
            )
            GL.glDisable(GL.GL_DEPTH_TEST)
            GL.glUseProgram(program)
            GL.glActiveTexture(GL.GL_TEXTURE0)
            GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GL.glUniform1i(GL.glGetUniformLocation(program, "uCamera"), 0)
            val position = GL.glGetAttribLocation(program, "aPosition")
            val tex = GL.glGetAttribLocation(program, "aUv")
            vertices.position(0)
            uv.position(0)
            GL.glVertexAttribPointer(position, 2, GL.GL_FLOAT, false, 0, vertices)
            GL.glVertexAttribPointer(tex, 2, GL.GL_FLOAT, false, 0, uv)
            GL.glEnableVertexAttribArray(position)
            GL.glEnableVertexAttribArray(tex)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
            GL.glDisableVertexAttribArray(position)
            GL.glDisableVertexAttribArray(tex)

            val camera = frame.camera
            tracking = camera.trackingState == TrackingState.TRACKING
            val projection = FloatArray(16)
            val view = FloatArray(16)
            val vp = FloatArray(16)
            camera.getProjectionMatrix(projection, 0, .01f, 100f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            val inverse = FloatArray(16)
            val source =
                if (tracking && Matrix.invertM(inverse, 0, vp, 0)) ArSource(sessionId, inverse)
                else null

            // Capturing the camera image does not require a tracked world pose.
            capture.getAndSet(null)?.let { request ->
                inFlight = request
                val capturedSource =
                    if (tracking) captureSpace(frame, projection, inverse) else null
                val bytes =
                    ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                GL.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
                val pixels = IntArray(width * height)
                for (y in 0 until height) for (x in 0 until width) {
                    val i = ((height - 1 - y) * width + x) * 4
                    pixels[y * width + x] =
                        (255 shl 24) or
                            ((bytes.get(i).toInt() and 255) shl 16) or
                            ((bytes.get(i + 1).toInt() and 255) shl 8) or
                            (bytes.get(i + 2).toInt() and 255)
                }
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                main.post { request.success(bitmap, capturedSource) }
                inFlight = null
            }
            if (!tracking) {
                val help =
                    when (camera.trackingFailureReason) {
                        TrackingFailureReason.INSUFFICIENT_LIGHT ->
                            "Low light: improve the scene lighting."
                        TrackingFailureReason.INSUFFICIENT_FEATURES ->
                            "Point at a textured surface."
                        TrackingFailureReason.EXCESSIVE_MOTION -> "Move the device more slowly."
                        TrackingFailureReason.CAMERA_UNAVAILABLE ->
                            "Another app is using the camera."
                        TrackingFailureReason.BAD_STATE -> "Tap Restart tracking."
                        else -> "Move the device slowly from side to side to map the scene."
                    }
                report(
                    (if (tap.get() != null) "Point selected; waiting for a 3D position. "
                    else "Initializing 3D position. ") + help + " Queries available.",
                )
                publish(pending())
                return
            }

            tap.get()?.let { point ->
                val hit = valid(frame.hitTest(point.x * width, point.y * height))
                if (hit != null && tap.compareAndSet(point, null)) {
                    val mark = Mark(point.x, point.y, "3D point", manual = true, arSource = source)
                    bindings[mark.id] =
                        Bound(hit.createAnchor(), listOf(listOf(floatArrayOf(0f, 0f, 0f))), mark)
                    marks = marks + mark
                    main.post { onPin(mark) }
                }
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPublish < 50) return
            lastPublish = now
            val currentMarks = marks
            val ids = currentMarks.map { it.id }.toSet()
            bindings.keys
                .filter { it !in ids }
                .toList()
                .forEach { id ->
                    bindings.remove(id)?.let { if (it.ownsAnchor) it.anchor.detach() }
                }
            currentMarks
                .filter { it.id !in bindings && it.arSource?.sessionId == sessionId }
                .forEach { mark ->
                    bind(mark)?.let { bindings[mark.id] = it }
                }
            val usedCaptures = currentMarks.mapNotNull { it.arSource?.captureId }.toSet()
            captures.keys
                .filter { it != latestCaptureId && it !in usedCaptures }
                .toList()
                .forEach { captures.remove(it)?.anchor?.detach() }
            val output =
                bindings.values
                    .filter { it.anchor.trackingState == TrackingState.TRACKING }
                    .mapNotNull { bound ->
                        val contours =
                            bound.local.mapNotNull { points ->
                                val projected = points.map {
                                    ArMath.project(bound.anchor.pose.transformPoint(it), vp)
                                }
                                if (projected.any { it == null }) null
                                else projected.filterNotNull()
                            }
                        if (contours.isEmpty()) null
                        else
                            ArDrawing(
                                bound.mark.label,
                                contours,
                                bound.mark.path != null,
                                bound.mark.manual,
                                id = bound.mark.id,
                            )
                    }
            val missing = currentMarks.size - output.size
            report(
                if (tap.get() != null)
                    "Point selected; searching for a surface. Move slowly and keep the target on the yellow marker."
                else
                    "3D active · XYZ + rotation · ${output.size} anchors" +
                        if (missing > 0) " · $missing without a surface in this session" else "",
            )
            publish(output + pending())
        } catch (e: Exception) {
            val failed = inFlight ?: capture.getAndSet(null)
            failed?.let { request ->
                main.post {
                    request.error(
                        "Could not capture the image (${e.javaClass.simpleName}). Try again.",
                    )
                }
            }
            report("AR unavailable: ${e.javaClass.simpleName}. Go back and reopen the 3D camera.")
        }
    }

    private fun valid(hits: List<HitResult>) = hits.firstOrNull { hit ->
        when (val trackable = hit.trackable) {
            is Plane -> trackable.isPoseInPolygon(hit.hitPose)
            is DepthPoint -> true
            is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            else -> false
        }
    }

    private fun captureSpace(
        frame: Frame,
        projection: FloatArray,
        inverseVp: FloatArray,
    ): ArSource? {
        val inverseProjection = FloatArray(16)
        if (!Matrix.invertM(inverseProjection, 0, projection, 0)) return null
        val anchor =
            runCatching { session.createAnchor(frame.camera.displayOrientedPose) }.getOrNull()
                ?: return null
        val depth = runCatching {
            frame.acquireDepthImage16Bits().use { image ->
                if (
                    !DepthSnapshot.synchronizedTimestamp(
                        image.timestamp,
                        frame.timestamp,
                        frame.androidCameraTimestamp,
                    )
                )
                    return@use null
                val ndc = floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f)
                val output = FloatArray(6)
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    ndc,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    output,
                )
                val uv =
                    floatArrayOf(
                        output[0],
                        output[1],
                        output[2] - output[0],
                        output[3] - output[1],
                        output[4] - output[0],
                        output[5] - output[1],
                    )
                val plane = image.planes[0]
                DepthSnapshot.copy(
                        image.width,
                        image.height,
                        plane.buffer,
                        plane.rowStride,
                        plane.pixelStride,
                        image.timestamp,
                        uv,
                    )
                    .takeIf { it.hasDepth }
            }
        }
            .getOrNull()
        val source =
            ArSource(
                sessionId,
                inverseVp,
                timestampNs = frame.timestamp,
                imageWidth = width,
                imageHeight = height,
                inverseProjection = inverseProjection,
                depth = depth,
            )
        captures[source.captureId] = CaptureSpace(anchor, source)
        latestCaptureId = source.captureId
        return source
    }

    private fun bind(mark: Mark): Bound? {
        val source = mark.arSource ?: return null
        val reference = captures[source.captureId] ?: return null
        if (reference.source !== source || reference.anchor.trackingState != TrackingState.TRACKING)
            return null
        val geometry = CaptureGeometry.reconstruct(mark, source) ?: return null
        // The capture anchor has been tracking world-map corrections throughout network latency.
        // Every vertex stays in that anchor's captured-camera coordinate system.
        return Bound(reference.anchor, geometry, mark, ownsAnchor = false)
    }

    fun detachAll() {
        bindings.values.filter { it.ownsAnchor }.forEach { it.anchor.detach() }
        bindings.clear()
        captures.values.forEach { it.anchor.detach() }
        captures.clear()
        latestCaptureId = null
        tap.set(null)
        publish(emptyList())
    }

    private fun buffer(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}
