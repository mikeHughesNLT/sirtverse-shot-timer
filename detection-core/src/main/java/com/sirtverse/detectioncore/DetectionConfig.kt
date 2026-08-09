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
     * When true, [CameraLaserDetector.start] locks the camera to [CameraLaserDetector.LOCKED_SHUTTER_NS]
     * and [CameraLaserDetector.LOCKED_ISO] so the background is dark and the laser dot is bright.
     * [CameraLaserDetector.stop] always restores auto-exposure regardless of this flag.
     */
    val lockedExposureEnabled: Boolean
}
