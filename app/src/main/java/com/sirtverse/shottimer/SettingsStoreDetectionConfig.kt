package com.sirtverse.shottimer

import com.sirtverse.detectioncore.DetectionConfig
import com.sirtverse.shottimer.storage.SettingsStore

/**
 * Bridges [SettingsStore] to the [DetectionConfig] interface consumed by :detection-core.
 *
 * Feature 22 (lockedExposureEnabled): the Airframe "Locked Exposure" toggle writes to
 * [SettingsStore.lockedExposureEnabled]; this bridge propagates that choice to
 * [com.sirtverse.detectioncore.CameraLaserDetector.start] when the real detector is
 * swapped in for the mock (one-line swap in AirframeScreen.kt).
 */
class SettingsStoreDetectionConfig(private val store: SettingsStore) : DetectionConfig {
    override val labModeEnabled: Boolean get() = store.labModeEnabled
    override val cooldownMs: Long get() = store.cooldownMs.toLong()
    override val lockedExposureEnabled: Boolean get() = store.lockedExposureEnabled
}
