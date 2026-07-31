package com.sirtverse.detectlab

import com.sirtverse.detectioncore.DetectionConfig

/** Lab-mode config: always-on labMode, short cooldown. */
class InMemoryDetectionConfig : DetectionConfig {
    override val labModeEnabled = true
    override val cooldownMs = 150L
}
