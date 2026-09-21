package com.sirtverse.detectioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam routing tests — CC-SIRT-NOVELTY-KOTLIN-RED-001-r3 T3.
 *
 * The brief's green-safety test: "one unit test that the green branch is not
 * reachable from the novelty branch." The decision is pure
 * ([DetectorMode.colorRoute]) so it is exhaustively testable off-device.
 */
class NoveltyGateTest {

    private val cbMax = CameraLaserDetector.CB_MAX
    private val crMax = CameraLaserDetector.CR_MAX
    private val crMin = 140   // D6b red enabled (255 would disable the red path entirely)

    @Test
    fun `green chroma is never routed to the novelty branch`() {
        // Exhaustive chroma sweep in novelty mode: any cell that PASSES the
        // green gate (cb < CB_MAX && cr < CR_MAX — checkGreen's passGreen,
        // CameraLaserDetector.kt L641) must route to the legacy gate.
        for (cb in 0..255) {
            for (cr in 0..255) {
                val route = DetectorMode.colorRoute(
                    DetectorMode.NOVELTY, cb, cr, cbMax, crMax, crMin)
                val passGreen = cb < cbMax && cr < crMax
                if (passGreen) {
                    assertEquals(
                        "green-passing chroma cb=$cb cr=$cr must stay on the legacy gate",
                        DetectorMode.ColorRoute.LEGACY_GATE, route)
                }
                if (route == DetectorMode.ColorRoute.NOVELTY_RED) {
                    // ...and the novelty branch may ONLY see D6b-red chroma
                    assertTrue("novelty branch saw cb=$cb cr=$cr",
                        cb < cbMax && cr > crMin)
                }
            }
        }
    }

    @Test
    fun `legacy mode never routes to novelty`() {
        for (cb in intArrayOf(0, 64, 128, 192, 255)) {
            for (cr in intArrayOf(0, 64, 128, 192, 255)) {
                assertEquals(DetectorMode.ColorRoute.LEGACY_GATE,
                    DetectorMode.colorRoute(DetectorMode.LEGACY, cb, cr, cbMax, crMax, crMin))
            }
        }
    }

    @Test
    fun `garbage mode never routes to novelty`() {
        assertEquals(DetectorMode.ColorRoute.LEGACY_GATE,
            DetectorMode.colorRoute("banana", 100, 200, cbMax, crMax, crMin))
    }

    @Test
    fun `mode resolution mirrors python detector_mode`() {
        // novelty_scorer.py L373-378: values {legacy|novelty}, default legacy,
        // unrecognised values fall back to the default.
        val prev = System.getProperty("detector.mode")
        try {
            System.clearProperty("detector.mode")
            assertEquals(DetectorMode.LEGACY, DetectorMode.current())
            System.setProperty("detector.mode", "novelty")
            assertEquals(DetectorMode.NOVELTY, DetectorMode.current())
            System.setProperty("detector.mode", " NOVELTY ")
            assertEquals(DetectorMode.NOVELTY, DetectorMode.current())
            System.setProperty("detector.mode", "banana")
            assertEquals(DetectorMode.LEGACY, DetectorMode.current())
        } finally {
            if (prev == null) System.clearProperty("detector.mode")
            else System.setProperty("detector.mode", prev)
        }
    }
}
