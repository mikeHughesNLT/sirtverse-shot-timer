package com.sirtverse.shottimer.airframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1a B5 — TargetRect serialization, contains(), and boundary invariants.
 */
class TargetRectTest {

    private val eps = 0.0001f

    private fun assertClose(expected: Float, actual: Float, label: String = "") {
        assertEquals("$label", expected, actual, eps)
    }

    @Test
    fun serialization_roundTrip_single() {
        val r = TargetRect(0.4f, 0.6f, 0.1f, 0.15f)
        val json = r.toJson()
        val restored = TargetRect.fromJson(json)
        assertClose(r.cx, restored.cx, "cx")
        assertClose(r.cy, restored.cy, "cy")
        assertClose(r.halfW, restored.halfW, "halfW")
        assertClose(r.halfH, restored.halfH, "halfH")
    }

    @Test
    fun serialization_roundTrip_list() {
        val rects = listOf(
            TargetRect(0.2f, 0.3f, 0.08f, 0.12f),
            TargetRect(0.7f, 0.5f, 0.05f, 0.07f),
        )
        val json = TargetRect.listToJson(rects)
        val restored = TargetRect.listFromJson(json)
        assertEquals(2, restored.size)
        assertClose(rects[0].cx, restored[0].cx, "r0.cx")
        assertClose(rects[1].halfH, restored[1].halfH, "r1.halfH")
    }

    @Test
    fun serialization_emptyList_roundTrips() {
        val json = TargetRect.listToJson(emptyList())
        val restored = TargetRect.listFromJson(json)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun serialization_threeRects_allPresent() {
        val rects = listOf(
            TargetRect(0.1f, 0.1f, 0.05f, 0.05f),
            TargetRect(0.5f, 0.5f, 0.10f, 0.10f),
            TargetRect(0.9f, 0.9f, 0.05f, 0.05f),
        )
        val json = TargetRect.listToJson(rects)
        val restored = TargetRect.listFromJson(json)
        assertEquals(3, restored.size)
        for (i in 0..2) {
            assertClose(rects[i].cx, restored[i].cx, "rect[$i].cx")
        }
    }

    @Test
    fun contains_centerPoint_true() {
        val r = TargetRect(0.5f, 0.5f, 0.1f, 0.1f)
        assertTrue(r.contains(0.5f, 0.5f))
    }

    @Test
    fun contains_exactBoundary_true() {
        val r = TargetRect(0.5f, 0.5f, 0.1f, 0.1f)
        assertTrue(r.contains(0.4f, 0.4f))  // left, top boundary
        assertTrue(r.contains(0.6f, 0.6f))  // right, bottom boundary
    }

    @Test
    fun contains_outsidePoint_false() {
        val r = TargetRect(0.5f, 0.5f, 0.1f, 0.1f)
        assertFalse(r.contains(0.39f, 0.5f))  // left of left edge
        assertFalse(r.contains(0.5f, 0.61f))  // below bottom edge
    }

    @Test
    fun edgeAccessors_consistentWithCenterAndHalf() {
        val r = TargetRect(0.5f, 0.6f, 0.1f, 0.15f)
        assertClose(0.4f, r.left(),   "left")
        assertClose(0.6f, r.right(),  "right")
        assertClose(0.45f, r.top(),   "top")
        assertClose(0.75f, r.bottom(),"bottom")
    }

    @Test
    fun toTargetRoi_usesMaxHalfExtent() {
        val r = TargetRect(0.3f, 0.4f, 0.05f, 0.12f)
        val roi = r.toTargetRoi()
        assertClose(0.3f, roi.cx, "roi.cx")
        assertClose(0.4f, roi.cy, "roi.cy")
        assertClose(0.12f, roi.halfSize, "roi.halfSize = max(halfW, halfH)")
    }

    @Test
    fun constants_maxRects_isThree() {
        assertEquals(3, TargetRect.MAX_RECTS)
    }

    @Test
    fun constants_minHalf_isTwoPercent() {
        assertEquals(0.02f, TargetRect.MIN_HALF, eps)
    }
}
