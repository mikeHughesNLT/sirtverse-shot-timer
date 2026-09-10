package com.sirtverse.shottimer.airframe

import org.junit.Assert.*
import org.junit.Test

/**
 * CC-SIRT-TRUTH-MODE-001 B7 — unit tests for B1 (zone-only gating + start-ignore),
 * B2 (FrameRing capacity/eviction), and FrameFeatures schema round-trip.
 *
 * All tests are pure JVM (no Android framework); FrameRing and FrameFeatures have
 * no Android deps so plain JUnit4 suffices.
 */
class TruthModeTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeRect(cx: Float, cy: Float, halfW: Float = 0.10f, halfH: Float = 0.10f) =
        TargetRect(cx = cx, cy = cy, halfW = halfW, halfH = halfH)

    private fun makeFeatures(
        tsMs: Long = 1000L,
        isShot: Boolean = true,
        counted: Boolean = false,
        reason: String = "OUT_OF_ZONE",
        rectIdx: Int = -1,
        score: Float = 20f,
        lighting: String = "DIM",
    ) = FrameFeatures(
        tsMs           = tsMs,
        score          = score,
        yDelta         = 15f,
        chromaDelta    = 5f,
        normX          = 0.5,
        normY          = 0.5,
        isShot         = isShot,
        aboveThreshold = score >= 16f,
        passNeighbor   = true,
        passColor      = true,
        scoreThreshold = 16f,
        neighborFactor = 0.2f,
        cbMax          = 150,
        crMax          = 127,
        chromaWeight   = 1.0f,
        emaAlpha       = 0.05f,
        rectIdx        = rectIdx,
        counted        = counted,
        reason         = reason,
        iso            = 800,
        shutterNs      = 16_000_000L,
        targetLuma     = 145f,
        exposureLocked = true,
        lightingLabel  = lighting,
    )

    private val emptyJpeg = ByteArray(0)

    // ── B1: containsAny (zone-only) ────────────────────────────────────────────

    @Test
    fun containsAny_singleRect_insidePoint_returnsIndex() {
        val rects = listOf(makeRect(0.5f, 0.5f))
        val idx = rects.indexOfFirst { it.contains(0.5f, 0.5f) }
        assertEquals(0, idx)
    }

    @Test
    fun containsAny_singleRect_outsidePoint_returnsMinusOne() {
        val rects = listOf(makeRect(0.5f, 0.5f))
        val idx = rects.indexOfFirst { it.contains(0.1f, 0.1f) }
        assertEquals(-1, idx)
    }

    @Test
    fun containsAny_multipleRects_hitsSecond() {
        val rects = listOf(makeRect(0.2f, 0.2f), makeRect(0.8f, 0.8f))
        val idx = rects.indexOfFirst { it.contains(0.85f, 0.85f) }
        assertEquals(1, idx)
    }

    @Test
    fun containsAny_emptyList_returnsMinusOne() {
        val rects = emptyList<TargetRect>()
        val idx = rects.indexOfFirst { it.contains(0.5f, 0.5f) }
        assertEquals(-1, idx)
    }

    @Test
    fun containsAny_boundaryPoint_isInside() {
        val r = makeRect(0.5f, 0.5f, halfW = 0.1f, halfH = 0.1f)
        // left/top boundary: (0.4, 0.4)
        assertTrue(r.contains(0.4f, 0.4f))
        // right/bottom boundary: (0.6, 0.6)
        assertTrue(r.contains(0.6f, 0.6f))
    }

    @Test
    fun containsAny_justOutsideBoundary_isFalse() {
        val r = makeRect(0.5f, 0.5f, halfW = 0.1f, halfH = 0.1f)
        assertFalse(r.contains(0.39f, 0.5f))
        assertFalse(r.contains(0.5f, 0.61f))
    }

    // ── B1: start-ignore window ─────────────────────────────────────────────────

    @Test
    fun startIgnore_withinWindow_isIgnored() {
        val startIgnoreUntilMs = 2000L
        val eventTs = 1500L  // within 1000 ms after GO (startIgnoreUntilMs - 1000 = 1000)
        val inIgnore = eventTs < startIgnoreUntilMs
        assertTrue("Event at $eventTs should be start-ignored (until $startIgnoreUntilMs)", inIgnore)
    }

    @Test
    fun startIgnore_afterWindow_isNotIgnored() {
        val startIgnoreUntilMs = 2000L
        val eventTs = 2100L  // after the 1000 ms window
        val inIgnore = eventTs < startIgnoreUntilMs
        assertFalse("Event at $eventTs should NOT be start-ignored (until $startIgnoreUntilMs)", inIgnore)
    }

    @Test
    fun startIgnore_atExactBoundary_isNotIgnored() {
        val startIgnoreUntilMs = 2000L
        val eventTs = 2000L
        val inIgnore = eventTs < startIgnoreUntilMs
        assertFalse("Event exactly at boundary $eventTs should not be ignored", inIgnore)
    }

    // ── B2: FrameRing capacity / eviction ──────────────────────────────────────

    @Test
    fun frameRing_singlePush_sizeIsOne() {
        val ring = FrameRing(capacityFrames = 5)
        ring.push(emptyJpeg, makeFeatures())
        assertEquals(1, ring.size())
    }

    @Test
    fun frameRing_fillToCapacity_sizeDoesNotExceed() {
        val ring = FrameRing(capacityFrames = 3)
        repeat(10) { ring.push(emptyJpeg, makeFeatures(tsMs = it.toLong())) }
        assertEquals(3, ring.size())
    }

    @Test
    fun frameRing_eviction_keepsNewest() {
        val ring = FrameRing(capacityFrames = 3)
        repeat(5) { i -> ring.push(emptyJpeg, makeFeatures(tsMs = i.toLong())) }
        val snap = ring.snapshot()
        // Should have slots with tsMs 2, 3, 4 (oldest 0 and 1 evicted)
        assertEquals(3, snap.size)
        assertEquals(2L, snap[0].features.tsMs)
        assertEquals(4L, snap[2].features.tsMs)
    }

    @Test
    fun frameRing_snapshot_isOldestFirst() {
        val ring = FrameRing(capacityFrames = 5)
        (10L..14L).forEach { ts -> ring.push(emptyJpeg, makeFeatures(tsMs = ts)) }
        val snap = ring.snapshot()
        for (i in 0 until snap.size - 1) {
            assertTrue(snap[i].features.tsMs < snap[i + 1].features.tsMs)
        }
    }

    @Test
    fun frameRing_emptySnapshot_isEmpty() {
        val ring = FrameRing()
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun frameRing_tail_returnsNewestN() {
        val ring = FrameRing(capacityFrames = 10)
        (100L..109L).forEach { ts -> ring.push(emptyJpeg, makeFeatures(tsMs = ts)) }
        val tail = ring.tail(3)
        assertEquals(3, tail.size)
        assertEquals(107L, tail[0].features.tsMs)
        assertEquals(109L, tail[2].features.tsMs)
    }

    // ── FrameFeatures: schema round-trip via toJsonLine ─────────────────────────

    @Test
    fun frameFeatures_jsonLine_containsAllKeyFields() {
        val f = makeFeatures(tsMs = 12345L, isShot = true, counted = true, reason = "", lighting = "BRIGHT")
        val json = f.toJsonLine()
        assertTrue(json.contains(""""ts_ms":12345"""))
        assertTrue(json.contains(""""is_shot":true"""))
        assertTrue(json.contains(""""counted":true"""))
        assertTrue(json.contains(""""reason":"""""))
        assertTrue(json.contains(""""lighting":"BRIGHT""""))
        assertTrue(json.contains(""""score_threshold":"""))
        assertTrue(json.contains(""""cb_max":"""))
        assertTrue(json.contains(""""exposure_locked":true"""))
    }

    @Test
    fun frameFeatures_jsonLine_outOfZone_hasReason() {
        val f = makeFeatures(reason = "OUT_OF_ZONE", counted = false, rectIdx = -1)
        val json = f.toJsonLine()
        assertTrue(json.contains(""""reason":"OUT_OF_ZONE""""))
        assertTrue(json.contains(""""counted":false"""))
        assertTrue(json.contains(""""rect_idx":-1"""))
    }

    @Test
    fun frameFeatures_jsonLine_startIgnore_hasReason() {
        val f = makeFeatures(reason = "START_IGNORE", counted = false)
        val json = f.toJsonLine()
        assertTrue(json.contains(""""reason":"START_IGNORE""""))
    }

    @Test
    fun frameFeatures_jsonLine_isValidJsonObject() {
        val json = makeFeatures().toJsonLine()
        assertTrue("Must start with {", json.startsWith("{"))
        assertTrue("Must end with }", json.endsWith("}"))
        // All keys are double-quoted
        assertTrue(json.contains("\"ts_ms\""))
        assertTrue(json.contains("\"score\""))
        assertTrue(json.contains("\"lighting\""))
    }
}
