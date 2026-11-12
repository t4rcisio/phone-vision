package com.example.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Immutable, strided depth image and view-to-depth transform copied during capture. */
class DepthSnapshot(
    val width: Int,
    val height: Int,
    samples: IntArray,
    val timestampNs: Long,
    viewToDepth: FloatArray,
) {
    private val data = samples.copyOf()
    private val uv = viewToDepth.copyOf()
    val hasDepth: Boolean
        get() = data.any { it in 150..15000 }

    init {
        require(data.size == width * height && uv.size == 6)
    }

    fun sample(x: Float, y: Float, inside: ((Float, Float) -> Boolean)? = null): Float? {
        val u = uv[0] + uv[2] * x + uv[4] * y
        val v = uv[1] + uv[3] * x + uv[5] * y
        if (u !in 0f..1f || v !in 0f..1f) return null
        val px = (u * width - .5f).roundToInt().coerceIn(0, width - 1)
        val py = (v * height - .5f).roundToInt().coerceIn(0, height - 1)
        val determinant = uv[2] * uv[5] - uv[4] * uv[3]
        if (abs(determinant) < 1e-8f) return null
        val values = mutableListOf<Pair<Float, Int>>()
        for (dy in -2..2) for (dx in -2..2) {
            val sx = px + dx
            val sy = py + dy
            if (sx !in 0 until width || sy !in 0 until height) continue
            val du = (sx + .5f) / width - uv[0]
            val dv = (sy + .5f) / height - uv[1]
            val vx = (du * uv[5] - dv * uv[4]) / determinant
            val vy = (dv * uv[2] - du * uv[3]) / determinant
            if (inside != null && !inside(vx, vy)) continue
            val mm = data[sy * width + sx]
            if (mm in 150..15000)
                values +=
                    ((sx + .5f - u * width) * (sx + .5f - u * width) +
                        (sy + .5f - v * height) * (sy + .5f - v * height)) to mm
        }
        if (values.isEmpty()) return null
        val nearest = values.sortedBy { it.first }.take(5).map { it.second }.sorted()
        val median = nearest[nearest.size / 2]
        val reliable = nearest.count { abs(it - median) <= maxOf(40, median / 20) }
        if (reliable < minOf(3, nearest.size)) return null
        return median / 1000f
    }

    companion object {
        fun copy(
            width: Int,
            height: Int,
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            timestampNs: Long,
            uv: FloatArray,
        ): DepthSnapshot {
            val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val start = bytes.position()
            val values = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val offset = start + y * rowStride + x * pixelStride
                if (offset + 1 < bytes.limit())
                    values[y * width + x] = bytes.getShort(offset).toInt() and 0xffff
            }
            return DepthSnapshot(width, height, values, timestampNs, uv)
        }

        fun synchronizedTimestamp(depth: Long, frame: Long, camera: Long): Boolean =
            minOf(abs(depth - frame), abs(depth - camera)) <= 5_000_000L
    }
}
