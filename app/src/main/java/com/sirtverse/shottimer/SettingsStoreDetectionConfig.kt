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
 *
 * P0 CC-SIRT-CALIBRATION-CAMPAIGN-002: all D2–D9 dial fields forwarded from SettingsStore
 * so the bench conductor can drive any dial via ADB prefs write + force-stop + relaunch.
 */
class SettingsStoreDetectionConfig(private val store: SettingsStore) : DetectionConfig {
    override val labModeEnabled:        Boolean get() = store.labModeEnabled
    override val benchModeEnabled:      Boolean get() = store.benchModeEnabled
    override val cooldownMs:            Long    get() = store.cooldownMs.toLong()
    override val lockedExposureEnabled: Boolean get() = store.lockedExposureEnabled
    override val lockedShutterNs:       Long    get() = store.lockedShutterNs
    override val lockedIso:             Int     get() = store.lockedIso
    // D2–D9
    override val chromaWeight:          Float   get() = store.chromaWeight
    override val scoreThreshold:        Float   get() = store.scoreThreshold
    override val emaAlpha:              Float   get() = store.emaAlpha
    override val neighborFactor:        Float   get() = store.neighborFactor
    override val cbMax:                 Int     get() = store.cbMax
    override val crMax:                 Int     get() = store.crMax
    override val minAbsentFrames:       Int     get() = store.minAbsentFrames
    override val maxPulseFrames:        Int     get() = store.maxPulseFrames
    // D10 (CC-SIRT-EXPOSURE-CONTROL-001)
    override val exposureTargetLuma:       Int     get() = store.exposureTargetLuma
    override val exposureAutoMeterEnabled: Boolean get() = store.exposureAutoMeterEnabled
}
