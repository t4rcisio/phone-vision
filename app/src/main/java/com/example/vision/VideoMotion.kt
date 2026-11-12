package com.example.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/** Short grayscale history replays motion from the exact inference frame to the current frame. */
internal class VideoMotion : AutoCloseable {
    private data class Frame(val sequence: Long, val time: Long, val gray: Mat)

    private data class Target(
        val mark: Mark,
        val original: Array<Point>,
        val current: Array<Point>,
        val transform: FloatArray,
        val refreshed: Long,
    )

    private val history = ArrayDeque<Frame>()
    private val targets = mutableMapOf<String, Target>()
    private var ready = false
    var processingMs = 0L
        private set

    fun add(sequence: Long, time: Long, bitmap: Bitmap) {
        val start = android.os.SystemClock.elapsedRealtime()
        if (!ready) {
            check(OpenCVLoader.initLocal())
            ready = true
        }
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        } finally {
            rgba.release()
        }
        val previous = history.lastOrNull()
        if (previous != null && previous.gray.size() != gray.size()) reset()
        else if (previous != null) {
            for ((id, target) in targets.toMap()) {
                val next =
                    if (time - target.refreshed <= 1000) follow(previous.gray, gray, target)
                    else null
                if (next == null) targets.remove(id) else targets[id] = next
            }
        }
        history.addLast(Frame(sequence, time, gray))
        while (
            history.size > 28 || (history.size > 1 && time - history.first().time > 1000)
        ) history.removeFirst().gray.release()
        processingMs = android.os.SystemClock.elapsedRealtime() - start
    }

    fun correct(sequence: Long, marks: List<Mark>): Int {
        val frames = history.toList()
        val sourceIndex = frames.indexOfFirst { it.sequence == sequence }
        if (sourceIndex < 0)
            return 0 // Cold loading or a stale result must never create an old outline.
        val source = frames[sourceIndex]
        var accepted = 0
        for (mark in marks) {
            var target = seed(source.gray, mark, source.time)
            for (i in sourceIndex + 1 until frames.size) {
                target = target?.let { follow(frames[i - 1].gray, frames[i].gray, it) }
                if (target == null) break
            }
            if (target != null) {
                targets[mark.id] = target
                accepted++
            }
        }
        return accepted
    }

    fun visible(): Pair<List<Mark>, Map<String, FloatArray>> =
        targets.values.map { it.mark } to targets.mapValues { it.value.transform.copyOf() }

    private fun seed(gray: Mat, mark: Mark, time: Long): Target? {
        val shape = mark.path?.let(SegmentMask::parse) ?: return null
        shape.transform(
            Matrix().apply {
                setValues(
                    floatArrayOf(
                        (mark.right!! - mark.x) * gray.cols(),
                        0f,
                        mark.x * gray.cols(),
                        0f,
                        (mark.bottom!! - mark.y) * gray.rows(),
                        mark.y * gray.rows(),
                        0f,
                        0f,
                        1f,
                    ),
                )
            },
        )
        val bitmap = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
        android.graphics
            .Canvas(bitmap)
            .drawPath(
                shape,
                android.graphics.Paint().apply { color = android.graphics.Color.WHITE },
            )
        val rgba = Mat()
        val mask = Mat()
        val corners = MatOfPoint()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, mask, Imgproc.COLOR_RGBA2GRAY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try {
                Imgproc.erode(mask, mask, kernel)
            } finally {
                kernel.release()
            }
            Imgproc.goodFeaturesToTrack(gray, corners, 70, .008, 2.5, mask, 3, false, .04)
            val points = corners.toArray()
            if (points.size < 3) return null
            return Target(
                mark,
                points,
                points,
                floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                time,
            )
        } finally {
            bitmap.recycle()
            rgba.release()
            mask.release()
            corners.release()
        }
    }

    private fun follow(previous: Mat, current: Mat, target: Target): Target? {
        val before = MatOfPoint2f(*target.current)
        val after = MatOfPoint2f()
        val back = MatOfPoint2f()
        val status = MatOfByte()
        val reverseStatus = MatOfByte()
        val error = MatOfFloat()
        val reverseError = MatOfFloat()
        val src = MatOfPoint2f()
        val dst = MatOfPoint2f()
        val inliers = Mat()
        var affine: Mat? = null
        try {
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 15, .03)
            Video.calcOpticalFlowPyrLK(
                previous,
                current,
                before,
                after,
                status,
                error,
                Size(15.0, 15.0),
                3,
                criteria,
            )
            if (after.empty()) return null
            Video.calcOpticalFlowPyrLK(
                current,
                previous,
                after,
                back,
                reverseStatus,
                reverseError,
                Size(15.0, 15.0),
                3,
                criteria,
            )
            val next = after.toArray()
            val returned = back.toArray()
            val flags = status.toArray()
            val reverse = reverseStatus.toArray()
            val errors = error.toArray()
            val good =
                target.current.indices.filter { i ->
                    flags[i].toInt() != 0 &&
                        reverse[i].toInt() != 0 &&
                        errors[i] < 30 &&
                        kotlin.math.hypot(
                            returned[i].x - target.current[i].x,
                            returned[i].y - target.current[i].y,
                        ) < 1.5 &&
                        next[i].x in 0.0..current.cols().toDouble() &&
                        next[i].y in 0.0..current.rows().toDouble()
                }
            if (good.size < 3 || good.size < target.current.size * .3) return null
            src.fromArray(*good.map { target.original[it] }.toTypedArray())
            dst.fromArray(*good.map { next[it] }.toTypedArray())
            affine =
                Calib3d.estimateAffinePartial2D(src, dst, inliers, Calib3d.RANSAC, 2.0, 300, .98, 5)
            if (
                affine.empty() ||
                    Core.countNonZero(inliers) < 3 ||
                    Core.countNonZero(inliers) < good.size * .55
            )
                return null
            val a = DoubleArray(6)
            affine.get(0, 0, a)
            val scale = a[0] * a[0] + a[3] * a[3]
            if (a.any { !it.isFinite() } || scale !in .08..12.0) return null
            val w = current.cols().toFloat()
            val h = current.rows().toFloat()
            val transform =
                floatArrayOf(
                    a[0].toFloat(),
                    (a[1] * h / w).toFloat(),
                    (a[2] / w).toFloat(),
                    (a[3] * w / h).toFloat(),
                    a[4].toFloat(),
                    (a[5] / h).toFloat(),
                    0f,
                    0f,
                    1f,
                )
            val center =
                floatArrayOf(
                    (target.mark.x + target.mark.right!!) / 2,
                    (target.mark.y + target.mark.bottom!!) / 2,
                )
            Matrix().apply { setValues(transform) }.mapPoints(center)
            if (center[0] !in -.1f..1.1f || center[1] !in -.1f..1.1f) return null
            val mask = ByteArray(good.size)
            inliers.get(0, 0, mask)
            val accepted = good.indices.filter { mask[it].toInt() != 0 }.map { good[it] }
            return target.copy(
                original = accepted.map { target.original[it] }.toTypedArray(),
                current = accepted.map { next[it] }.toTypedArray(),
                transform = transform,
            )
        } finally {
            before.release()
            after.release()
            back.release()
            status.release()
            reverseStatus.release()
            error.release()
            reverseError.release()
            src.release()
            dst.release()
            inliers.release()
            affine?.release()
        }
    }

    fun reset() {
        history.forEach { it.gray.release() }
        history.clear()
        targets.clear()
    }

    override fun close() = reset()
}

/** Used when saving: bake the live transform into both the box and each contour vertex. */
internal fun warpedMark(mark: Mark, h: FloatArray): Mark {
    val matrix = Matrix().apply { setValues(h) }
    val corners =
        floatArrayOf(
            mark.x,
            mark.y,
            mark.right!!,
            mark.y,
            mark.right,
            mark.bottom!!,
            mark.x,
            mark.bottom,
        )
    matrix.mapPoints(corners)
    val left = corners.filterIndexed { i, _ -> i % 2 == 0 }.min()
    val right = corners.filterIndexed { i, _ -> i % 2 == 0 }.max()
    val top = corners.filterIndexed { i, _ -> i % 2 == 1 }.min()
    val bottom = corners.filterIndexed { i, _ -> i % 2 == 1 }.max()
    val path = buildString {
        for (token in Regex("([ML])([-+0-9.eE]+)\\s+([-+0-9.eE]+)|Z").findAll(mark.path!!)) {
            if (token.value == "Z") append("Z")
            else {
                val p =
                    floatArrayOf(
                        mark.x + token.groupValues[2].toFloat() * (mark.right - mark.x),
                        mark.y + token.groupValues[3].toFloat() * (mark.bottom - mark.y),
                    )
                matrix.mapPoints(p)
                append(token.groupValues[1])
                append(
                    String.format(
                        java.util.Locale.US,
                        "%.4f %.4f",
                        (p[0] - left) / (right - left),
                        (p[1] - top) / (bottom - top),
                    ),
                )
            }
        }
    }
    return mark.copy(x = left, y = top, right = right, bottom = bottom, path = path)
}
