package com.example.vision

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryStoreTest {
    @Test
    fun historyAndImagesSurviveReopeningAndConcurrentWriters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-test-" + java.util.UUID.randomUUID().toString()
        val folder = File(context.filesDir, name)
        try {
            val a = HistoryStore(context, name, false)
            val b = HistoryStore(context, name, false)
            val image = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            coroutineScope {
                val first = async {
                    a.save(
                        image,
                        listOf(Mark(.5f, .5f, "point")),
                        Skill.POINT,
                        "one",
                        "response one",
                    )
                }
                val second = async {
                    b.save(image, emptyList(), Skill.QUERY, "two", "response two")
                }
                first.await()
                second.await()
            }
            val entries = HistoryStore(context, name, false).list()
            assertEquals(2, entries.size)
            assertTrue(entries.any { it.answer == "response one" })
            entries.forEach {
                assertTrue(File(it.original).length() > 0)
                assertTrue(File(it.annotated).length() > 0)
            }
            assertEquals(1, HistoryStore.marks(entries.first { it.prompt == "one" }.marksJson).size)
        } finally {
            check(folder.parentFile == context.filesDir)
            folder.deleteRecursively()
        }
    }
}
