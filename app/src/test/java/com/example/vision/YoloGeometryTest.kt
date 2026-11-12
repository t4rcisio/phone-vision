package com.example.vision

import org.junit.Assert.*
import org.junit.Test

class YoloGeometryTest {
    private fun detection(x: Float, category: Int = 2) =
        YoloDetection(category, .9f, YoloBox(x, .3f, x + .15f, .5f), "M0 0L1 0L1 1Z")

    @Test
    fun portraitLetterboxRemovesPaddingExactly() {
        val map = Letterbox(480, 960)
        assertEquals(160, map.padX)
        assertEquals(0, map.padY)
        val box = map.box(.5f, .5f, .5f, 1f)
        assertEquals(0f, box.left, 1e-6f)
        assertEquals(1f, box.right, 1e-6f)
        assertEquals(0f, box.top, 1e-6f)
        assertEquals(1f, box.bottom, 1e-6f)
    }

    @Test
    fun nmsIsClassAwareAndFilterIsAppliedBeforeSelection() {
        val raw = FloatArray(116 * 8400)
        for (i in 0..2) {
            raw[i] = .5f
            raw[8400 + i] = .5f
            raw[16800 + i] = .4f
            raw[25200 + i] = .4f
        }
        raw[(4 + 2) * 8400] = .9f
        raw[(4 + 2) * 8400 + 1] = .8f
        raw[(4 + 5) * 8400 + 2] = .85f
        assertEquals(2, YoloDecode.candidates(raw, Letterbox(640, 640), .4f, emptySet()).size)
        val filtered = YoloDecode.candidates(raw, Letterbox(640, 640), .4f, setOf(2))
        assertEquals(1, filtered.size)
        assertEquals(0, filtered.single().index)
    }

    @Test
    fun idsFollowMovingObjectsDespiteDetectionOrderChanges() {
        val tracker = YoloTrackIds()
        val start = tracker.update(listOf(detection(.1f), detection(.7f)), 0)
        val moved = tracker.update(listOf(detection(.66f), detection(.14f)), 100)
        assertEquals(start[0].first, moved[1].first)
        assertEquals(start[1].first, moved[0].first)
        assertTrue(tracker.update(emptyList(), 200).isEmpty())
        val recovered = tracker.update(listOf(detection(.22f)), 300)
        assertEquals(start[0].first, recovered.single().first)
    }

    @Test
    fun differentClassesAndExpiredTargetsReceiveNewIds() {
        val tracker = YoloTrackIds()
        val id = tracker.update(listOf(detection(.3f)), 0).single().first
        assertNotEquals(id, tracker.update(listOf(detection(.3f, 5)), 100).single().first)
        assertNotEquals(id, tracker.update(listOf(detection(.3f)), 1100).single().first)
        tracker.reset()
        assertTrue(tracker.update(emptyList(), 1200).isEmpty())
    }

    @Test
    fun multipleCategoriesAreIncludedAndOthersAreExcluded() {
        val raw = FloatArray(116 * 8400)
        for (i in 0..2) {
            raw[i] = .2f + i * .25f
            raw[8400 + i] = .5f
            raw[16800 + i] = .15f
            raw[25200 + i] = .2f
        }
        raw[6 * 8400] = .9f
        raw[7 * 8400 + 1] = .8f
        raw[9 * 8400 + 2] = .95f
        assertEquals(
            setOf(2, 3),
            YoloDecode.candidates(raw, Letterbox(640, 640), .4f, setOf(2, 3))
                .map { it.category }
                .toSet(),
        )
    }

    @Test
    fun rotationReturnsBoxesAndMaskVerticesToOriginalPreview() {
        val original = YoloDetection(2, .8f, YoloBox(.2f, .3f, .6f, .7f), "M0 0L1 0L1 1L0 1Z")
        val p = YoloOrientation.preview(original, 90)
        assertEquals(.3f, p.box.left, 1e-5f)
        assertEquals(.4f, p.box.top, 1e-5f)
        assertEquals(.7f, p.box.right, 1e-5f)
        assertEquals(.8f, p.box.bottom, 1e-5f)
        assertTrue(p.path.startsWith("M0.0000 1.0000"))
        for (rotation in listOf(90, 180, 270)) {
            val roundtrip =
                YoloOrientation.preview(
                    YoloOrientation.preview(original, rotation),
                    (360 - rotation) % 360,
                )
            assertEquals(original.box.left, roundtrip.box.left, 1e-5f)
            assertEquals(original.box.top, roundtrip.box.top, 1e-5f)
        }
    }

    @Test
    fun automaticOrientationFindsSidewaysContentAndHoldsSuccessfulAngle() {
        val finder = YoloAutoOrientation()
        assertEquals(0, finder.next(0))
        repeat(2) { finder.observe(false) }
        assertEquals(90, finder.next(0))
        repeat(2) { finder.observe(false) }
        assertEquals(270, finder.next(0))
        finder.observe(true)
        assertEquals(270, finder.next(0))
        assertEquals(90, finder.next(90))
        assertEquals(0, YoloOrientation.snapped(-1, 0))
        assertEquals(0, YoloOrientation.snapped(44, 0))
        assertEquals(90, YoloOrientation.snapped(80, 0))
    }
}
