package com.example.vision

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/** Immutable image reference retained until its associated overlays are no longer needed. */
class ImageReference(bitmap: Bitmap, val id: String = java.util.UUID.randomUUID().toString()) {
    val image: Bitmap

    init {
        val factor = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
        image =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true,
            )
    }
}

/** Direct reference-to-live matching avoids accumulating motion errors during API latency. */
class ImageTracker : AutoCloseable {
    private data class Features(
        val points: Array<KeyPoint>,
        val descriptors: Mat,
        val width: Int,
        val height: Int,
    )

    private val cache = mutableMapOf<String, Features>()
    private var orb: ORB? = null
    private var matcher: BFMatcher? = null

    private fun features(bitmap: Bitmap): Features {
        val rgba = Mat()
        val gray = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            orb!!.detectAndCompute(gray, mask, keypoints, descriptors)
            return Features(keypoints.toArray(), descriptors, bitmap.width, bitmap.height)
        } finally {
            rgba.release()
            gray.release()
            keypoints.release()
            mask.release()
        }
    }

    @Synchronized
    fun update(bitmap: Bitmap, marks: List<Mark>): Map<String, FloatArray> {
        val tracked = marks.filter { it.live && it.imageReference != null }
        if (tracked.isEmpty()) {
            cache.values.forEach { it.descriptors.release() }
            cache.clear()
            return emptyMap()
        }
        if (orb == null) {
            if (!OpenCVLoader.initLocal()) return emptyMap()
            orb = ORB.create(1600)
            matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        }
        val factor = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
        val small =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        val current = features(small)
        if (small !== bitmap) small.recycle()
        try {
            val active = tracked.map { it.imageReference!!.id }.toSet()
            cache.keys
                .filter { it !in active }
                .toList()
                .forEach { cache.remove(it)?.descriptors?.release() }
            val result = mutableMapOf<String, FloatArray>()
            for (group in tracked.groupBy { it.imageReference!!.id }.values) {
                val reference = group.first().imageReference!!
                val original = cache.getOrPut(reference.id) { features(reference.image) }
                if (original.descriptors.empty() || current.descriptors.empty()) continue
                val pairs = mutableListOf<MatOfDMatch>()
                try {
                    matcher!!.knnMatch(original.descriptors, current.descriptors, pairs, 2)
                    val matches =
                        pairs
                            .mapNotNull { p ->
                                p.toArray()
                                    .takeIf {
                                        it.size == 2 && it[0].distance < .72f * it[1].distance
                                    }
                                    ?.first()
                            }
                            .distinctBy { it.trainIdx }
                    for (mark in group) {
                        val region =
                            mark.path?.let(SegmentMask::parse)?.let { path ->
                                val right = mark.right ?: mark.x
                                val bottom = mark.bottom ?: mark.y
                                path.transform(
                                    android.graphics.Matrix().apply {
                                        setValues(
                                            floatArrayOf(
                                                (right - mark.x) * original.width,
                                                0f,
                                                mark.x * original.width,
                                                0f,
                                                (bottom - mark.y) * original.height,
                                                mark.y * original.height,
                                                0f,
                                                0f,
                                                1f,
                                            ),
                                        )
                                    },
                                )
                                android.graphics.Region().apply {
                                    setPath(
                                        path,
                                        android.graphics.Region(
                                            0,
                                            0,
                                            original.width,
                                            original.height,
                                        ),
                                    )
                                }
                            }
                        val inObject = matches.filter { m ->
                            val p = original.points[m.queryIdx].pt
                            val x = p.x / original.width
                            val y = p.y / original.height
                            if (region != null) region.contains(p.x.toInt(), p.y.toInt())
                            else if (mark.right != null && mark.bottom != null)
                                x >= mark.x && x <= mark.right && y >= mark.y && y <= mark.bottom
                            else
                                kotlin.math.abs(x - mark.x) < .22 &&
                                    kotlin.math.abs(y - mark.y) < .22
                        }
                        if (inObject.size < 8) continue
                        val src =
                            MatOfPoint2f(
                                *inObject.map { original.points[it.queryIdx].pt }.toTypedArray(),
                            )
                        val dst =
                            MatOfPoint2f(
                                *inObject.map { current.points[it.trainIdx].pt }.toTypedArray(),
                            )
                        val inliers = Mat()
                        val homography =
                            Calib3d.findHomography(
                                src,
                                dst,
                                Calib3d.RANSAC,
                                2.5,
                                inliers,
                                2000,
                                .995,
                            )
                        try {
                            if (
                                homography.empty() ||
                                    Core.countNonZero(inliers) < 8 ||
                                    Core.countNonZero(inliers) < inObject.size * .45
                            )
                                continue
                            val h = DoubleArray(9)
                            homography.get(0, 0, h)
                            val normalized =
                                floatArrayOf(
                                    (h[0] * original.width / current.width).toFloat(),
                                    (h[1] * original.height / current.width).toFloat(),
                                    (h[2] / current.width).toFloat(),
                                    (h[3] * original.width / current.height).toFloat(),
                                    (h[4] * original.height / current.height).toFloat(),
                                    (h[5] / current.height).toFloat(),
                                    (h[6] * original.width).toFloat(),
                                    (h[7] * original.height).toFloat(),
                                    h[8].toFloat(),
                                )
                            if (plausible(mark, normalized)) result[mark.id] = normalized
                        } finally {
                            src.release()
                            dst.release()
                            inliers.release()
                            homography.release()
                        }
                    }
                } finally {
                    pairs.forEach { it.release() }
                }
            }
            return result
        } finally {
            current.descriptors.release()
        }
    }

    private fun plausible(mark: Mark, h: FloatArray): Boolean {
        if (h.any { !it.isFinite() } || kotlin.math.abs(h[8]) < 1e-5f) return false
        val left = if (mark.right != null) mark.x else (mark.x - .1f).coerceAtLeast(0f)
        val top = if (mark.bottom != null) mark.y else (mark.y - .1f).coerceAtLeast(0f)
        val right = mark.right ?: (mark.x + .1f).coerceAtMost(1f)
        val bottom = mark.bottom ?: (mark.y + .1f).coerceAtMost(1f)
        val corners =
            listOf(XY(left, top), XY(right, top), XY(right, bottom), XY(left, bottom)).map { p ->
                val w = h[6] * p.x + h[7] * p.y + h[8]
                if (w <= 1e-5f) return false
                XY((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
            }
        if (corners.any { it.x !in -2f..3f || it.y !in -2f..3f }) return false
        val area =
            kotlin.math.abs(
                corners.indices.sumOf { i ->
                    val a = corners[i]
                    val b = corners[(i + 1) % 4]
                    (a.x * b.y - b.x * a.y).toDouble()
                } / 2,
            )
        val original = (right - left) * (bottom - top)
        return area > original * .08 && area < original * 12
    }

    @Synchronized
    override fun close() {
        cache.values.forEach { it.descriptors.release() }
        cache.clear()
        orb?.clear()
        matcher?.clear()
        orb = null
        matcher = null
    }
}
