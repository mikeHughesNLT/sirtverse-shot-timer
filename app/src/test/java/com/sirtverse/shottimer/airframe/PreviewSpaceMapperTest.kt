package com.sirtverse.shottimer.airframe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W1a B2 — PreviewSpaceMapper (pure Kotlin, no Android deps).
 * Round-trip and crop-offset verification.
 */
class PreviewSpaceMapperTest {

    private val eps = 0.001f

    private fun assertClose(expected: Float, actual: Float, label: String = "") {
        assertEquals("$label expected=$expected actual=$actual", expected, actual, eps)
    }

    @Test
    fun identity_noFrameAspect_centerMapsToCenter() {
        val m = PreviewSpaceMapper(400f, 220f, frameAspect = null)
        val (nx, ny) = m.boxToNorm(200f, 110f)
        assertClose(0.5f, nx, "nx")
        assertClose(0.5f, ny, "ny")
    }

    @Test
    fun identity_squareBoxSameAspect_roundTripExact() {
        val m = PreviewSpaceMapper(300f, 300f, frameAspect = 1.0f)
        val px = 75f; val py = 225f
        val (nx, ny) = m.boxToNorm(px, py)
        val (px2, py2) = m.normToBox(nx, ny)
        assertClose(px, px2, "px round-trip")
        assertClose(py, py2, "py round-trip")
    }

    @Test
    fun fillCenter_widerFrame_xCropApplied() {
        // Frame is 4:3 (landscape). Box is 400x220 (aspect 1.818).
        // Box is WIDER than frame → frame is scaled to fit height → top/bottom crop only?
        // Wait: frameAspect = 4/3 = 1.333 < boxAspect 1.818 → frame is NARROWER than box
        // → scale to fit width → frame height exceeds box → top/bottom crop.
        val boxW = 400f; val boxH = 220f
        val frameAspect = 4f / 3f  // 1.333
        val m = PreviewSpaceMapper(boxW, boxH, frameAspect)
        // boxAspect = 400/220 = 1.818 > frameAspect 1.333 → yCropFrac > 0
        // Rendered frameH = boxW / frameAspect = 400 / 1.333 = 300dp
        // crop per side = (300 - 220) / 2 = 40dp → yCropFrac = 40/220 = 0.1818
        // A tap at box top (py=0) → normY = 0 * (1 + 2*0.1818) - 0.1818 = -0.1818 → clamped to 0
        val (_, nyTop) = m.boxToNorm(200f, 0f)
        assertClose(0f, nyTop, "ny at box top (clamped)")

        // A tap at box center (py=110) → normY = (110/220) * 1.3636 - 0.1818 = 0.5*1.3636 - 0.1818 = 0.5
        val (_, nyMid) = m.boxToNorm(200f, 110f)
        assertClose(0.5f, nyMid, "ny at center")
    }

    @Test
    fun fillCenter_tallerFrame_yCropApplied() {
        // Frame is 1:2 (portrait 0.5 aspect). Box is 300x300 (square, aspect 1.0).
        // frameAspect = 0.5 < boxAspect 1.0 → frame is taller → yCrop applies.
        val m = PreviewSpaceMapper(300f, 300f, frameAspect = 0.5f)
        val (nxMid, nyMid) = m.boxToNorm(150f, 150f)
        assertClose(0.5f, nxMid, "nx center")
        assertClose(0.5f, nyMid, "ny center")
    }

    @Test
    fun roundTrip_visibleAreaPoint_isExact() {
        val m = PreviewSpaceMapper(400f, 220f, frameAspect = 1.333f)
        // Use a point in the visible area (not in the crop zone)
        val nx = 0.3f; val ny = 0.6f
        val (px, py) = m.normToBox(nx, ny)
        val (nx2, ny2) = m.boxToNorm(px, py)
        assertClose(nx, nx2, "nx round-trip")
        assertClose(ny, ny2, "ny round-trip")
    }

    @Test
    fun noFrameAspect_corners_mapCorrectly() {
        val m = PreviewSpaceMapper(400f, 200f, frameAspect = null)
        val (nxTL, nyTL) = m.boxToNorm(0f, 0f)
        assertClose(0f, nxTL, "nx top-left")
        assertClose(0f, nyTL, "ny top-left")

        val (nxBR, nyBR) = m.boxToNorm(400f, 200f)
        assertClose(1f, nxBR, "nx bottom-right")
        assertClose(1f, nyBR, "ny bottom-right")
    }
}
