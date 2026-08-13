package com.sirtverse.detectioncore

/**
 * Configuration seam consumed by [CameraLaserDetector].
 *
 * The full app provides a [SettingsStore]-backed impl; DetectLab provides an in-memory impl.
 */
interface DetectionConfig {
    val labModeEnabled: Boolean
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
     * Override via [com.sirtverse.shottimer.storage.SettingsStore] so each sweep rung can
     * be driven without an APK rebuild (ADB prefs write → force-stop → relaunch).
     */
    val lockedShutterNs: Long
    val lockedIso: Int
}
