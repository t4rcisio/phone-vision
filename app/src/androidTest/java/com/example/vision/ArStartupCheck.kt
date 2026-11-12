package com.example.vision

import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArStartupCheck {
    @Test
    fun cameraSessionProducesFramesOnConnectedPhone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyArStartup") == "true")
        val scenario = ActivityScenario.launch(ArActivity::class.java)
        try {
            var status = ""
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            do {
                scenario.onActivity { activity ->
                    assertTrue(
                        "Screen must stay awake while camera is in use",
                        activity.window.attributes.flags and
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
                    )
                    val field =
                        ArActivity::class.java.getDeclaredField("status\$delegate").apply {
                            isAccessible = true
                        }
                    status = (field.get(activity) as State<*>).value.toString()
                }
                if (status.startsWith("3D active") || status.startsWith("Initializing 3D position"))
                    break
                android.os.SystemClock.sleep(250)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            val observe =
                InstrumentationRegistry.getArguments().getString("observeArMillis")?.toLongOrNull()
                    ?: 0L
            if (observe > 0) {
                android.os.SystemClock.sleep(observe.coerceAtMost(30_000))
                scenario.onActivity { activity ->
                    val field =
                        ArActivity::class.java.getDeclaredField("status\$delegate").apply {
                            isAccessible = true
                        }
                    status = (field.get(activity) as State<*>).value.toString()
                }
            }
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString(
                            "stream",
                            "\nAR_OBSERVATION_PID=${android.os.Process.myPid()}; STATUS=$status\n",
                        )
                    },
                )
            assertTrue(
                "AR camera should produce frames: $status",
                status.startsWith("3D active") || status.startsWith("Initializing 3D position"),
            )
        } finally {
            scenario.close()
        }
    }
}
