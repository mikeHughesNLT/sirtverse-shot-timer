package com.sirtverse.detectioncore

/**
 * Configuration seam consumed by [CameraLaserDetector].
 *
 * The full app provides a [SettingsStore]-backed impl; DetectLab provides a
 * [SharedPrefsDetectionConfig]-backed impl so the bench conductor can drive all
 * dials via ADB prefs write + force-stop + relaunch without an APK rebuild.
 */
interface DetectionConfig {
    val labModeEnabled: Boolean

    /**
     * P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 — bench mode.
     * When true, the JSONL log emits a "frame" record on every processed frame containing
     * the full peak record (y_delta, chroma_delta, peak_score, cb, cr, cell x/y, all gate
     * booleans, isShot). The existing hit/near_miss records are emitted regardless.
     * Requires labModeEnabled OR benchModeEnabled to open the log file — either flag alone
     * is sufficient.
     */
    val benchModeEnabled: Boolean

    val cooldownMs: Long

    /**
     * Feature 22 (Detection-Arsenal.md rank #1 — Locked Exposure Context).
     * When true, [CameraLaserDetector.start] locks the camera to [lockedShutterNs] / [lockedIso]
     * so the background is dark and the laser dot is bright.
     * [CameraLaserDetector.stop] always restores auto-exposure regardless of this flag.
     */
    val lockedExposureEnabled: Boolean

    /**
     * B2 TARGET-EXPOSURE-001 sweep: runtime-tunable shutter and ISO for the locked-exposure
     * context. Defaults mirror the historical hardcoded constants (16 ms / ISO 800).
     * Override via ADB prefs write so each sweep rung can be driven without an APK rebuild.
     */
    val lockedShutterNs: Long
    val lockedIso: Int

    // ── D2–D9 sweep dials — P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 ─────────────────────────
    // Defaults in all implementations must match the locked constants in [CameraLaserDetector]
    // so existing behaviour is identical until a dial is deliberately changed.
    // Applied to companion @JvmField vars at every start() — zero APK rebuilds per rung.

    /** D2 — combined score = yDelta + chromaWeight × (|dCb| + |dCr|). 0 = chroma off (kill-switch). */
    val chromaWeight: Float

    /** D3 — minimum combined score to enter the neighbor / color / pulse pipeline. */
    val scoreThreshold: Float

    /** D4 — per-cell background exponential-moving-average rate (fraction 0–1). */
    val emaAlpha: Float

    /** D5 — neighbor gate threshold = scoreThreshold × neighborFactor. */
    val neighborFactor: Float

    /** D6 — green color gate: Cb must be below this value. */
    val cbMax: Int

    /** D6 — green color gate: Cr must be below this value. */
    val crMax: Int

    /** D8 — frames a candidate must be absent before a new pulse can fire. */
    val minAbsentFrames: Int

    /** D9 — candidate frames beyond this trigger background resume (reflection guard). */
    val maxPulseFrames: Int
}
