package com.example.vision

import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArCaptureDepthCheck {
    @Test
    fun captureKeepsSynchronizedGeometryWhileFramesAdvance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyCaptureDepth") == "true")
        ActivityScenario.launch(ArActivity::class.java).use { scenario ->
            var renderer: ArEngine? = null
            val deadline = android.os.SystemClock.uptimeMillis() + 25_000
            do {
                scenario.onActivity { a ->
                    renderer =
                        (ArActivity::class
                                .java
                                .getDeclaredField("engine\$delegate")
                                .apply { isAccessible = true }
                                .get(a) as State<*>)
                            .value as? ArEngine
                }
                if (renderer?.tracking == true) break
                android.os.SystemClock.sleep(100)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            assertTrue("Move the phone gently to initialize tracking", renderer?.tracking == true)
            var captured: ArSource? = null
            fun validPixel(): XY? =
                listOf(.5f, .3f, .7f, .15f, .85f)
                    .flatMap { x -> listOf(.5f, .3f, .7f).map { y -> XY(x, y) } }
                    .firstOrNull { captured?.cameraPoint(it.x, it.y) != null }
            repeat(30) {
                if (validPixel() == null) {
                    val latch = CountDownLatch(1)
                    scenario.onActivity {
                        renderer!!.capture(
                            { bitmap, source ->
                                captured = source
                                bitmap.recycle()
                                latch.countDown()
                            },
                            { latch.countDown() },
                        )
                    }
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                    if (validPixel() == null) android.os.SystemClock.sleep(500)
                }
            }
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString(
                            "stream",
                            "\nCAPTURE_FRAME_NS=${captured?.timestampNs}; DEPTH_FRAME_NS=${captured?.depth?.timestampNs}; DEPTH_AVAILABLE=${captured?.depth!=null}\n",
                        )
                    },
                )
            assertNotNull(
                "A synchronized depth frame must be available to test live 3D compensation",
                captured?.depth,
            )
            val originalTimestamp = captured!!.timestampNs
            val originalId = captured!!.captureId
            captured?.depth?.let { d ->
                val samples =
                    DepthSnapshot::class
                        .java
                        .getDeclaredField("data")
                        .apply { isAccessible = true }
                        .get(d) as IntArray
                val uv =
                    DepthSnapshot::class
                        .java
                        .getDeclaredField("uv")
                        .apply { isAccessible = true }
                        .get(d) as FloatArray
                InstrumentationRegistry.getInstrumentation()
                    .sendStatus(
                        0,
                        android.os.Bundle().apply {
                            putString(
                                "stream",
                                "\nDEPTH_VALID=${samples.count {it in 150..15000}}/${samples.size}; DEPTH_RANGE=${samples.minOrNull()}..${samples.maxOrNull()}; UV=${uv.joinToString()}; INV_P=${captured?.inverseProjection?.joinToString()}\n",
                            )
                        },
                    )
            }
            val pixel = validPixel()
            assertNotNull("Captured image needs at least one valid depth sample", pixel)
            val point = captured!!.cameraPoint(pixel!!.x, pixel.y)
            val before = renderer!!.frameCount
            android.os.SystemClock.sleep(1500)
            assertTrue(renderer!!.frameCount > before)
            assertEquals(originalTimestamp, captured!!.timestampNs)
            assertEquals(originalId, captured!!.captureId)
            assertArrayEquals(point, captured!!.cameraPoint(pixel.x, pixel.y), .00001f)
        }
    }
}
