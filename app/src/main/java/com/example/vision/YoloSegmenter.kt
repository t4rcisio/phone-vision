package com.example.vision

import android.content.Context
import android.graphics.*
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Bundled YOLO26n-seg w8a32: NCHW RGB input, raw normalized boxes and 32 mask prototypes. */
internal class YoloSegmenter(private val context: Context, private val preferGpu: Boolean = true) :
    AutoCloseable {
    private var model: CompiledModel? = null
    private var inputs = emptyList<TensorBuffer>()
    private var outputs = emptyList<TensorBuffer>()
    var accelerator = "CPU"
        private set

    private val pixels = IntArray(640 * 640)
    private val tensor = FloatArray(640 * 640 * 3)
    private val square = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun open(gpu: Boolean) {
        releaseModel()
        val options =
            CompiledModel.Options(if (gpu) Accelerator.GPU else Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
            }
        model = CompiledModel.create(context.assets, "yolo26n-seg_w8a32.tflite", options)
        try {
            inputs = model!!.createInputBuffers()
            outputs = model!!.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 2)
        } catch (t: Throwable) {
            releaseModel()
            throw t
        }
        accelerator = if (gpu) "GPU" else "CPU"
    }

    fun detect(
        bitmap: Bitmap,
        threshold: Float = .4f,
        categories: Set<Int> = emptySet(),
        rotation: Int = 0,
    ): List<YoloDetection> {
        require(rotation in setOf(0, 90, 180, 270))
        val upright =
            if (rotation == 0) bitmap
            else
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    Matrix().apply { postRotate(rotation.toFloat()) },
                    true,
                )
        return try {
            detectUpright(upright, threshold, categories).map {
                YoloOrientation.preview(it, rotation)
            }
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
    }

    private fun detectUpright(
        bitmap: Bitmap,
        threshold: Float,
        categories: Set<Int>,
    ): List<YoloDetection> {
        if (model == null) {
            check(OpenCVLoader.initLocal())
            if (preferGpu)
                try {
                    open(true)
                } catch (_: Exception) {
                    open(false)
                }
            else open(false)
        }
        val mapping = Letterbox(bitmap.width, bitmap.height)
        Canvas(square).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(
                    mapping.padX,
                    mapping.padY,
                    mapping.padX + mapping.scaledWidth,
                    mapping.padY + mapping.scaledHeight,
                ),
                paint,
            )
        }
        square.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        val plane = 640 * 640
        for (i in pixels.indices) {
            tensor[i] = ((pixels[i] shr 16) and 255) / 255f
            tensor[plane + i] = ((pixels[i] shr 8) and 255) / 255f
            tensor[2 * plane + i] = (pixels[i] and 255) / 255f
        }
        fun invoke(): List<FloatArray> {
            inputs[0].writeFloat(tensor)
            model!!.run(inputs, outputs)
            return outputs.map { it.readFloat() }
        }
        val result =
            try {
                invoke()
            } catch (e: Exception) {
                if (accelerator != "GPU") throw e
                open(false)
                invoke()
            }
        val raw = result.singleOrNull { it.size == 116 * 8400 } ?: error("Unsupported YOLO output.")
        val proto =
            result.singleOrNull { it.size == 32 * 160 * 160 } ?: error("Unsupported YOLO mask.")
        val selected = YoloDecode.candidates(raw, mapping, threshold, categories)
        if (selected.isEmpty()) return emptyList()
        val prototypes = Mat(32, 160 * 160, CvType.CV_32F).apply { put(0, 0, proto) }
        try {
            return selected.mapNotNull { candidate ->
                mask(candidate, raw, prototypes, mapping)?.let {
                    YoloDetection(candidate.category, candidate.score, candidate.box, it)
                }
            }
        } finally {
            prototypes.release()
        }
    }

    private fun mask(
        candidate: YoloCandidate,
        raw: FloatArray,
        prototypes: Mat,
        mapping: Letterbox,
    ): String? {
        val coefficients = Mat(1, 32, CvType.CV_32F)
        val logits = Mat()
        val empty = Mat()
        val binary = Mat(160, 160, CvType.CV_8U)
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            coefficients.put(0, 0, FloatArray(32) { raw[(84 + it) * 8400 + candidate.index] })
            Core.gemm(coefficients, prototypes, 1.0, empty, 0.0, logits)
            val values = FloatArray(160 * 160)
            logits.get(0, 0, values)
            val b = candidate.box
            val bytes = ByteArray(160 * 160)
            for (y in 0 until 160) for (x in 0 until 160) {
                // Compare to un-clamped source coordinates so letterbox padding never becomes a
                // mask.
                val sx = ((x + .5f) * 4 - mapping.padX) / mapping.scaledWidth
                val sy = ((y + .5f) * 4 - mapping.padY) / mapping.scaledHeight
                if (
                    sx >= b.left &&
                        sx <= b.right &&
                        sy >= b.top &&
                        sy <= b.bottom &&
                        values[y * 160 + x] > 0
                )
                    bytes[y * 160 + x] = 255.toByte()
            }
            binary.put(0, 0, bytes)
            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_CCOMP,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val svg = StringBuilder()
            for (contour in contours) {
                if (kotlin.math.abs(Imgproc.contourArea(contour)) < 3) continue
                val input = MatOfPoint2f(*contour.toArray())
                val simplified = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(input, simplified, .65, true)
                    val points = simplified.toArray()
                    if (points.size < 3) continue
                    points.forEachIndexed { i, p ->
                        val x =
                            ((((p.x + .5) * 4 - mapping.padX) / mapping.scaledWidth - b.left) /
                                    b.width)
                                .coerceIn(0.0, 1.0)
                        val y =
                            ((((p.y + .5) * 4 - mapping.padY) / mapping.scaledHeight - b.top) /
                                    b.height)
                                .coerceIn(0.0, 1.0)
                        svg.append(if (i == 0) "M" else "L")
                            .append(String.format(java.util.Locale.US, "%.4f %.4f", x, y))
                    }
                    svg.append("Z")
                } finally {
                    input.release()
                    simplified.release()
                }
            }
            return svg.toString().takeIf { it.isNotBlank() }
        } finally {
            coefficients.release()
            logits.release()
            empty.release()
            binary.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun releaseModel() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        inputs = emptyList()
        outputs = emptyList()
        model?.close()
        model = null
    }

    override fun close() {
        releaseModel()
        square.recycle()
    }
}

internal val YoloLabels =
    listOf(
        "Person",
        "Bicycle",
        "Car",
        "Motorcycle",
        "Airplane",
        "Bus",
        "Train",
        "Truck",
        "Boat",
        "Traffic light",
        "Fire hydrant",
        "Stop sign",
        "Parking meter",
        "Bench",
        "Bird",
        "Cat",
        "Dog",
        "Horse",
        "Sheep",
        "Cow",
        "Elephant",
        "Bear",
        "Zebra",
        "Giraffe",
        "Backpack",
        "Umbrella",
        "Handbag",
        "Tie",
        "Suitcase",
        "Frisbee",
        "Skis",
        "Snowboard",
        "Sports ball",
        "Kite",
        "Baseball bat",
        "Baseball glove",
        "Skateboard",
        "Surfboard",
        "Tennis racket",
        "Bottle",
        "Wine glass",
        "Cup",
        "Fork",
        "Knife",
        "Spoon",
        "Bowl",
        "Banana",
        "Apple",
        "Sandwich",
        "Orange",
        "Broccoli",
        "Carrot",
        "Hot dog",
        "Pizza",
        "Donut",
        "Cake",
        "Chair",
        "Couch",
        "Potted plant",
        "Bed",
        "Dining table",
        "Toilet",
        "Tv",
        "Laptop",
        "Mouse",
        "Remote",
        "Keyboard",
        "Cell phone",
        "Microwave",
        "Oven",
        "Toaster",
        "Sink",
        "Refrigerator",
        "Book",
        "Clock",
        "Vase",
        "Scissors",
        "Teddy bear",
        "Hair drier",
        "Toothbrush",
    )
