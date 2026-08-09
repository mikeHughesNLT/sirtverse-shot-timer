package com.sirtverse.detectlab

import com.sirtverse.detectioncore.DetectionConfig

/**
 * Lab-mode config: always-on labMode, short cooldown, exposure pinned by default.
 *
 * [lockedExposureEnabled] defaults to true — DetectLab always starts pinned so the
 * HUD immediately reflects the drill-window recipe. The exposure toggle button in
 * [DetectLabActivity] calls cameraController directly (bypassing this flag) once the
 * session is running, so the flag only governs the initial state on start().
 */
class InMemoryDetectionConfig(
    override val labModeEnabled: Boolean = true,
    override val cooldownMs: Long = 150L,
    override val lockedExposureEnabled: Boolean = true,
) : DetectionConfig
