package com.example.vision

import kotlin.math.tan

data class XY(val x: Float, val y: Float)

data class Mark(
    val x: Float,
    val y: Float,
    val label: String,
    val world: List<Float>? = null,
    val right: Float? = null,
    val bottom: Float? = null,
    val path: String? = null,
    val reference: List<Float>? = null,
    val manual: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
    val arSource: ArSource? = null,
    val imageReference: ImageReference? = null,
    val live: Boolean = true,
)

/** Holds orientation while the gyroscope reports no physical motion. */
class RotationGate {
    private var accepted: List<Float>? = null

    fun update(candidate: List<Float>, moving: Boolean): List<Float> {
        val previous = accepted
        if (previous == null) {
            accepted = candidate
            return candidate
        }
        if (!moving) return previous
        val trace = candidate.indices.sumOf { (candidate[it] * previous[it]).toDouble() }
        val angle = kotlin.math.acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0))
        if (angle > Math.toRadians(.3)) accepted = candidate
        return accepted!!
    }
}

/** Rotational reprojection only. Assumes a 60 degree horizontal preview FOV; no depth/SLAM. */
object Spatial {
    private val halfWidth = tan(Math.toRadians(30.0)).toFloat()

    fun world(x: Float, y: Float, aspect: Float, rotation: List<Float>): List<Float> {
        val ray = floatArrayOf((2 * x - 1) * halfWidth, (1 - 2 * y) * halfWidth / aspect, -1f)
        return (0..2).map { row ->
            (0..2).sumOf { col -> (rotation[row * 3 + col] * ray[col]).toDouble() }.toFloat()
        }
    }

    fun project(
        world: List<Float>,
        aspect: Float,
        rotation: List<Float>,
        clip: Boolean = true,
    ): XY? {
        val ray =
            (0..2).map { col ->
                (0..2).sumOf { row -> (rotation[row * 3 + col] * world[row]).toDouble() }.toFloat()
            }
        if (ray[2] >= -0.001f) return null
        val x = .5f + ray[0] / -ray[2] / (2 * halfWidth)
        val y = .5f - ray[1] / -ray[2] / (2 * halfWidth / aspect)
        return if (!clip || (x in 0f..1f && y in 0f..1f)) XY(x, y) else null
    }
}
