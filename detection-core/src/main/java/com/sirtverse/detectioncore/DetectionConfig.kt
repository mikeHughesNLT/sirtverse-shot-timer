package com.sirtverse.detectioncore

/**
 * Configuration seam consumed by [CameraLaserDetector].
 *
 * The full app provides a [SettingsStore]-backed impl; DetectLab provides an in-memory impl.
 */
interface DetectionConfig {
    val labModeEnabled: Boolean
    val cooldownMs: Long
}
