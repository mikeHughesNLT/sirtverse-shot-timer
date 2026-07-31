package com.sirtverse.shottimer

import com.sirtverse.detectioncore.DetectionConfig
import com.sirtverse.shottimer.storage.SettingsStore

/** Bridges [SettingsStore] to the [DetectionConfig] interface consumed by :detection-core. */
class SettingsStoreDetectionConfig(private val store: SettingsStore) : DetectionConfig {
    override val labModeEnabled: Boolean get() = store.labModeEnabled
    override val cooldownMs: Long get() = store.cooldownMs.toLong()
}
