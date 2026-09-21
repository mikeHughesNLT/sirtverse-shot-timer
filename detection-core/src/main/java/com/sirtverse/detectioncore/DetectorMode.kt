package com.sirtverse.detectioncore

/**
 * DetectorMode — Kotlin mirror of `detector_mode()` in
 * `SIRTverse/src/core/novelty_scorer.py` L373-378 (CC-SIRT-NOVELTY-KOTLIN-RED-001-r3 T3).
 *
 * The novelty path is OPT-IN and defaults to `legacy`: a fresh install with no
 * flag set is behaviourally identical to pre-port. Resolution order:
 * JVM system property `detector.mode`, then env `DETECTOR_MODE`, then default.
 * (On-device neither is set by default — wiring a user-facing toggle is the
 * device brief's job, not this one's. JVM tests use the system property.)
 */
object DetectorMode {
    const val LEGACY = "legacy"
    const val NOVELTY = "novelty"

    fun current(default: String = LEGACY): String {
        val raw = (System.getProperty("detector.mode")
            ?: System.getenv("DETECTOR_MODE")
            ?: default).trim().lowercase()
        return if (raw == LEGACY || raw == NOVELTY) raw else default
    }

    /** Which gate owns a candidate at the L493 seam. */
    enum class ColorRoute { LEGACY_GATE, NOVELTY_RED }

    /**
     * The seam decision, pure and camera-free (unit-tested on the JVM).
     *
     * NOVELTY_RED is returned ONLY when the mode is novelty AND the peak
     * chroma satisfies the D6b red condition (cb < cbMax && cr > crMin —
     * the exact passRed inequalities of CameraLaserDetector.checkGreen).
     * Every other chroma — green included — routes to the legacy gate, so
     * the green path is unreachable from the novelty branch and the novelty
     * scorer can never fire on a green candidate.
     */
    fun colorRoute(
        mode: String, cb: Int, cr: Int,
        cbMax: Int, crMax: Int, crMin: Int,
    ): ColorRoute {
        val passRed = cb < cbMax && cr > crMin
        return if (mode == NOVELTY && passRed) ColorRoute.NOVELTY_RED
        else ColorRoute.LEGACY_GATE
    }
}
