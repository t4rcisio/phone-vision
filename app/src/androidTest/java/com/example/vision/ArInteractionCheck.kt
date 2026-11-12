package com.example.vision

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit device checks covering the actual AR capture/query entry point. */
class ArInteractionCheck {
    private fun engine(activity: ArActivity): ArEngine? =
        (ArActivity::class
                .java
                .getDeclaredField("engine\$delegate")
                .apply { isAccessible = true }
                .get(activity) as State<*>)
            .value as? ArEngine

    @Test
    fun selectedPointIsVisibleEvenBeforeItCanBeAnchored() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("verifyArInteraction") == "true",
        )
        ActivityScenario.launch(ArActivity::class.java).use { scenario ->
            var rendered = false
            var originalIds: Set<String> = emptySet()
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            do {
                scenario.onActivity { activity ->
                    val e = engine(activity)
                    if (e != null && e.frameCount > 0) {
                        val vm = ViewModelProvider(activity)[VisionModel::class.java]
                        originalIds = vm.marks.map { it.id }.toSet()
                        vm.manualMarking = true
                        e.pin(.5f, .5f)
                        rendered = true
                    }
                }
                if (!rendered) android.os.SystemClock.sleep(100)
            } while (!rendered && android.os.SystemClock.uptimeMillis() < deadline)
            assertTrue("AR camera must be ready for tap", rendered)
            android.os.SystemClock.sleep(250)
            scenario.onActivity { activity ->
                val shapes =
                    (ArActivity::class
                            .java
                            .getDeclaredField("shapes\$delegate")
                            .apply { isAccessible = true }
                            .get(activity) as State<*>)
                        .value as List<*>
                assertTrue("Selection should be shown as pending or anchored", shapes.isNotEmpty())
                val vm = ViewModelProvider(activity)[VisionModel::class.java]
                vm.removeMarks(vm.marks.map { it.id }.toSet() - originalIds)
            }
        }
    }

    @Test
    fun queryButtonCapturesAndCallsMoondreamWhileMapping() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyArQuery") == "true")
        ActivityScenario.launch(ArActivity::class.java).use { scenario ->
            var ready = false
            var startCount = 0
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            do {
                scenario.onActivity { activity ->
                    val e = engine(activity)
                    if (e != null && e.frameCount > 0) {
                        val vm = ViewModelProvider(activity)[VisionModel::class.java]
                        assumeTrue("Configure a chave no app", vm.apiKey.isNotBlank())
                        vm.skill = Skill.QUERY
                        vm.prompt = "Briefly describe the objects visible in this image."
                        startCount = vm.requests
                        activity.queryCamera()
                        ready = true
                    }
                }
                if (!ready) android.os.SystemClock.sleep(100)
            } while (!ready && android.os.SystemClock.uptimeMillis() < deadline)
            assertTrue("Camera query should start", ready)
            var complete = false
            val queryDeadline = android.os.SystemClock.uptimeMillis() + 100_000
            do {
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[VisionModel::class.java]
                    if (vm.error != null) fail("Camera query failed: ${vm.error}")
                    complete = vm.requests > startCount && !vm.busy && vm.result.isNotBlank()
                    if (complete) {
                        assertNotNull(vm.image)
                        val saved = vm.history.firstOrNull { it.prompt == vm.prompt }
                        assertNotNull("Result must be persisted in history", saved)
                        assertNotNull(
                            "Annotated image must be exported to gallery",
                            saved!!.galleryUri,
                        )
                        val context = InstrumentationRegistry.getInstrumentation().targetContext
                        assertNotNull(
                            context.contentResolver
                                .openInputStream(android.net.Uri.parse(saved.galleryUri))
                                ?.use { it.read() },
                        )
                        val sheet =
                            ArActivity::class
                                .java
                                .getDeclaredField("resultSheetOpen\$delegate")
                                .apply { isAccessible = true }
                        assertEquals(
                            "Query must keep the live camera visible",
                            false,
                            (sheet.get(activity) as State<*>).value,
                        )
                        InstrumentationRegistry.getInstrumentation()
                            .sendStatus(
                                0,
                                android.os.Bundle().apply {
                                    putString(
                                        "stream",
                                        "\nAR_QUERY_SUCCEEDED=true; TRACKED_CAPTURE=${vm.imageArSource!=null}; DEPTH_CAPTURE=${vm.imageArSource?.depth!=null}; GALLERY_SAVED=${saved.galleryUri!=null}; TRACKING_NOW=${engine(activity)?.tracking}; FRAMES=${engine(activity)?.frameCount}\n",
                                    )
                                },
                            )
                    }
                }
                if (!complete) android.os.SystemClock.sleep(200)
            } while (!complete && android.os.SystemClock.uptimeMillis() < queryDeadline)
            assertTrue("Button must deliver a visible response", complete)
            var frameBefore = 0
            scenario.onActivity { frameBefore = engine(it)?.frameCount ?: 0 }
            android.os.SystemClock.sleep(300)
            scenario.onActivity {
                assertTrue(
                    "Live camera must continue after query",
                    (engine(it)?.frameCount ?: 0) > frameBefore,
                )
            }
        }
    }
}
