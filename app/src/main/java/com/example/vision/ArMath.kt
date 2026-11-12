package com.example.vision

import android.opengl.Matrix

/** A frozen camera calibration from the exact frame sent to the vision API. */
class ArSource(
    val sessionId: String,
    inverseViewProjection: FloatArray,
    val captureId: String = java.util.UUID.randomUUID().toString(),
    val timestampNs: Long = 0,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    inverseProjection: FloatArray? = null,
    val depth: DepthSnapshot? = null,
) {
    val inverseViewProjection = inverseViewProjection.copyOf()
    val inverseProjection = inverseProjection?.copyOf()

    /** Coordinates relative to the capture anchor, never relative to the later camera pose. */
    fun cameraPoint(x: Float, y: Float, inside: ((Float, Float) -> Boolean)? = null): FloatArray? {
        val projection = inverseProjection ?: return null
        val z = depth?.sample(x, y, inside) ?: return null
        val ray = FloatArray(4)
        Matrix.multiplyMV(ray, 0, projection, 0, floatArrayOf(2 * x - 1, 1 - 2 * y, -1f, 1f), 0)
        if (kotlin.math.abs(ray[3]) < 1e-6f) return null
        val eye = FloatArray(3) { ray[it] / ray[3] }
        if (eye[2] >= -.0001f) return null
        return FloatArray(3) { eye[it] * z / -eye[2] }
    }

    fun ray(x: Float, y: Float): Pair<FloatArray, FloatArray>? {
        val near = FloatArray(4)
        val far = FloatArray(4)
        Matrix.multiplyMV(
            near,
            0,
            inverseViewProjection,
            0,
            floatArrayOf(2 * x - 1, 1 - 2 * y, -1f, 1f),
            0,
        )
        Matrix.multiplyMV(
            far,
            0,
            inverseViewProjection,
            0,
            floatArrayOf(2 * x - 1, 1 - 2 * y, 1f, 1f),
            0,
        )
        if (kotlin.math.abs(near[3]) < 1e-6f || kotlin.math.abs(far[3]) < 1e-6f) return null
        val origin = FloatArray(3) { near[it] / near[3] }
        return origin to FloatArray(3) { far[it] / far[3] - origin[it] }
    }
}

internal object ArMath {
    fun project(point: FloatArray, viewProjection: FloatArray): XY? {
        val clip = FloatArray(4)
        Matrix.multiplyMV(
            clip,
            0,
            viewProjection,
            0,
            floatArrayOf(point[0], point[1], point[2], 1f),
            0,
        )
        if (clip[3] <= .001f) return null
        return XY(.5f + .5f * clip[0] / clip[3], .5f - .5f * clip[1] / clip[3])
    }

    fun intersect(
        source: ArSource,
        x: Float,
        y: Float,
        point: FloatArray,
        normal: FloatArray,
    ): FloatArray? {
        val (origin, direction) = source.ray(x, y) ?: return null
        val denominator = (0..2).sumOf { (direction[it] * normal[it]).toDouble() }.toFloat()
        if (kotlin.math.abs(denominator) < 1e-6f) return null
        val distance =
            (0..2).sumOf { ((point[it] - origin[it]) * normal[it]).toDouble() }.toFloat() /
                denominator
        if (distance <= 0f) return null
        return FloatArray(3) { origin[it] + distance * direction[it] }
    }
}
