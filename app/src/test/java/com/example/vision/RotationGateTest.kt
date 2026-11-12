package com.example.vision

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class RotationGateTest {
    private fun yaw(degrees: Double): List<Float> {
        val r = Math.toRadians(degrees)
        return listOf(
            cos(r).toFloat(),
            0f,
            sin(r).toFloat(),
            0f,
            1f,
            0f,
            -sin(r).toFloat(),
            0f,
            cos(r).toFloat(),
        )
    }

    @Test
    fun tripodDoesNotDriftEvenWhenMagneticHeadingChanges() {
        val gate = RotationGate()
        val original = gate.update(yaw(0.0), false)
        for (i in 1..100) assertEquals(original, gate.update(yaw(i / 10.0), false))
    }

    @Test
    fun tinyOscillationsAreIgnoredButRealMotionUpdates() {
        val gate = RotationGate()
        val original = gate.update(yaw(0.0), true)
        assertEquals(original, gate.update(yaw(.1), true))
        assertEquals(original, gate.update(yaw(-.1), true))
        assertNotEquals(original, gate.update(yaw(5.0), true))
    }
}
