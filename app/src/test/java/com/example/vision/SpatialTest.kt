package com.example.vision

import org.junit.Assert.*
import org.junit.Test

class SpatialTest {
    private val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test
    fun originalPosePreservesNormalizedCoordinates() {
        for (aspect in listOf(.75f, 1f, 1.7778f)) {
            for (point in listOf(XY(.1f, .2f), XY(.5f, .5f), XY(.9f, .8f))) {
                val projected =
                    Spatial.project(
                        Spatial.world(point.x, point.y, aspect, identity),
                        aspect,
                        identity,
                    )!!
                assertEquals(point.x, projected.x, .00001f)
                assertEquals(point.y, projected.y, .00001f)
            }
        }
    }

    @Test
    fun sameRotatedPosePreservesCoordinates() {
        val roll = listOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val result = Spatial.project(Spatial.world(.3f, .7f, .75f, roll), .75f, roll)!!
        assertEquals(.3f, result.x, .00001f)
        assertEquals(.7f, result.y, .00001f)
    }

    @Test
    fun pointsBehindCameraAreHidden() {
        val turnedAround = listOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)
        assertNull(Spatial.project(Spatial.world(.5f, .5f, .75f, identity), .75f, turnedAround))
    }

    @Test
    fun pointsOutsideViewportAreHidden() {
        assertNull(Spatial.project(listOf(100f, 0f, -1f), .75f, identity))
        assertNull(Spatial.project(listOf(0f, 100f, -1f), .75f, identity))
    }

    @Test
    fun deviceRollMovesPointWithoutNetworkOrChangingSavedCoordinates() {
        val roll = listOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val world = Spatial.world(.7f, .5f, 1f, identity)
        val projected = Spatial.project(world, 1f, roll)!!
        assertEquals(.5f, projected.x, .00001f)
        assertEquals(.7f, projected.y, .00001f)
        assertEquals(.7f, Spatial.project(world, 1f, identity)!!.x, .00001f)
    }
}
