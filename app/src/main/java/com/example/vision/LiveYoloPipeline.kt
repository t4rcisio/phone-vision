package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class VideoFrame(val bitmap: Bitmap, val sequence: Long, val time: Long) {
    private val references = AtomicInteger(1)

    fun retain(): VideoFrame {
        check(references.incrementAndGet() > 1)
        return this
    }

    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0)
        if (remaining == 0) bitmap.recycle()
    }
}

internal data class YoloOptions(
    val generation: Long,
    val categories: Set<Int>,
    val threshold: Float,
    val automatic: Boolean,
    val hint: Int,
    val manual: Int,
    val paused: Boolean,
)

internal data class VideoOutput(
    val generation: Long,
    val frame: VideoFrame,
    val marks: List<Mark>,
    val transforms: Map<String, FloatArray>,
    val inferenceMs: Long,
    val motionMs: Long,
    val fps: Float,
    val accelerator: String?,
    val rotation: Int,
    val inferences: Long,
    val thumbnail: Bitmap?,
)

/** Camera/flow and GPU inference run independently. Every result is rebased to the latest image. */
internal class LiveYoloPipeline(
    context: Context,
    private val onOutput: (VideoOutput) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    @Volatile var options = YoloOptions(0, emptySet(), .4f, true, 0, 0, false)
    @Volatile private var active = false
    @Volatile private var closed = false
    val wantsFrames
        get() = active && !closed && !options.paused

    private data class Result(
        val generation: Long,
        val epoch: Long,
        val frame: VideoFrame,
        val detections: List<YoloDetection>,
        val ms: Long,
        val accelerator: String,
        val rotation: Int,
    )

    private val app = context.applicationContext
    private val gpu = Executors.newSingleThreadExecutor()
    private var detector: YoloSegmenter? = null // GPU thread only, including disposal.
    private val busy = AtomicBoolean(false)
    private val pending = AtomicReference<Result?>()
    private val pendingUi = AtomicReference<VideoOutput?>()
    private val uiPosted = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val motion = VideoMotion()
    private val ids = YoloTrackIds()
    private var generation = -1L
    private var epoch = 0L
    private var sequence = 0L
    private var inferenceMs = 0L
    private var accelerator: String? = null
    private var rotation = 0
    private var inferenceCount = 0L
    private var thumbnailTime = 0L
    private var fpsStart = 0L
    private var fpsFrames = 0
    private var fps = 0f

    @Synchronized
    fun setActive(value: Boolean) {
        if (active != value) {
            active = value
            epoch++
            motion.reset()
            ids.reset()
            pending.getAndSet(null)?.frame?.release()
            pendingUi.getAndSet(null)?.frame?.release()
        }
    }

    /** Takes ownership of bitmap. Called only by the CameraX analysis executor. */
    @Synchronized
    fun frame(bitmap: Bitmap) {
        if (!wantsFrames) {
            bitmap.recycle()
            return
        }
        val settings = options
        if (settings.generation != generation) {
            generation = settings.generation
            motion.reset()
            ids.reset()
        }
        val now = SystemClock.elapsedRealtime()
        val frame = VideoFrame(bitmap, ++sequence, now)
        try {
            motion.add(frame.sequence, now, bitmap)
            pending.getAndSet(null)?.let { result ->
                try {
                    if (result.generation == generation && result.epoch == epoch) {
                        val marks =
                            ids.update(result.detections, result.frame.time).map { (id, d) ->
                                Mark(
                                    d.box.left,
                                    d.box.top,
                                    "${YoloLabels[d.category]} #$id · ${(d.score*100).toInt()}%",
                                    right = d.box.right,
                                    bottom = d.box.bottom,
                                    path = d.path,
                                    id = "yolo-$id",
                                )
                            }
                        motion.correct(result.frame.sequence, marks)
                        inferenceMs = result.ms
                        accelerator = result.accelerator
                        rotation = result.rotation
                        inferenceCount++
                    }
                } finally {
                    result.frame.release()
                }
            }
            if (busy.compareAndSet(false, true)) {
                val source = frame.retain()
                val captureEpoch = epoch
                try {
                    gpu.execute {
                        try {
                            if (detector == null) detector = YoloSegmenter(app)
                            val usedRotation =
                                if (settings.automatic) orientation(settings) else settings.manual
                            val start = SystemClock.elapsedRealtime()
                            val detections =
                                detector!!.detect(
                                    source.bitmap,
                                    settings.threshold,
                                    settings.categories,
                                    usedRotation,
                                )
                            orientationFound(detections.isNotEmpty())
                            val result =
                                Result(
                                    settings.generation,
                                    captureEpoch,
                                    source,
                                    detections,
                                    SystemClock.elapsedRealtime() - start,
                                    detector!!.accelerator,
                                    usedRotation,
                                )
                            synchronized(this) {
                                if (closed || captureEpoch != epoch) source.release()
                                else pending.getAndSet(result)?.frame?.release()
                            }
                        } catch (e: Exception) {
                            source.release()
                            logFailure("Inference failed", e)
                            main.post { if (!closed) onError("Could not run YOLO on this frame.") }
                        } finally {
                            busy.set(false)
                        }
                    }
                } catch (e: Exception) {
                    source.release()
                    busy.set(false)
                    throw e
                }
            }
            fpsFrames++
            if (now - fpsStart >= 600) {
                fps = if (fpsStart == 0L) 0f else fpsFrames * 1000f / (now - fpsStart)
                fpsStart = now
                fpsFrames = 0
            }
            val thumb =
                if (now - thumbnailTime >= 500) {
                    thumbnailTime = now
                    Bitmap.createScaledBitmap(
                        bitmap,
                        160,
                        (160f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1),
                        true,
                    )
                } else null
            val (marks, transforms) = motion.visible()
            publish(
                VideoOutput(
                    generation,
                    frame.retain(),
                    marks,
                    transforms,
                    inferenceMs,
                    motion.processingMs,
                    fps,
                    accelerator,
                    rotation,
                    inferenceCount,
                    thumb,
                ),
            )
        } catch (e: Exception) {
            logFailure("Motion frame failed", e)
            motion.reset()
            main.post { if (!closed) onError("Tracking restarted.") }
        } finally {
            frame.release()
        }
    }

    private val orientationFinder = YoloAutoOrientation()
    private var orientationGeneration = -1L

    private fun orientation(settings: YoloOptions): Int {
        if (orientationGeneration != settings.generation) {
            orientationFinder.reset()
            orientationGeneration = settings.generation
        }
        return orientationFinder.next(settings.hint)
    }

    private fun orientationFound(found: Boolean) = orientationFinder.observe(found)

    private fun publish(value: VideoOutput) {
        pendingUi.getAndSet(value)?.frame?.release()
        postUi()
    }

    private fun postUi() {
        if (!uiPosted.compareAndSet(false, true)) return
        main.post {
            val value = pendingUi.getAndSet(null)
            try {
                if (value != null) {
                    if (
                        closed ||
                            !active ||
                            value.generation != options.generation ||
                            options.paused
                    )
                        value.frame.release()
                    else onOutput(value)
                }
            } finally {
                uiPosted.set(false)
                if (pendingUi.get() != null) postUi()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        active = false
        epoch++
        motion.close()
        pending.getAndSet(null)?.frame?.release()
        pendingUi.getAndSet(null)?.frame?.release()
        gpu.execute {
            detector?.close()
            detector = null
        }
        gpu.shutdown()
    }
}
