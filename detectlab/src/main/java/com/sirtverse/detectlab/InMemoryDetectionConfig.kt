package com.sirtverse.detectlab

import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.DetectionConfig
import com.sirtverse.detectioncore.PulseStateMachine

/**
 * Hardcoded in-memory [DetectionConfig] — useful for unit tests and isolated demos.
 *
 * All defaults match the locked companion-object constants in [CameraLaserDetector] and
 * [PulseStateMachine] so no behaviour changes on a fresh install. For bench-campaign use,
 * prefer [SharedPrefsDetectionConfig] which reads from SharedPreferences so the conductor
 * can drive dials via ADB prefs write + force-stop + relaunch.
 *
 * [lockedExposureEnabled] defaults to true — DetectLab always starts pinned so the
 * HUD immediately reflects the drill-window recipe.
 */
class InMemoryDetectionConfig(
    override val labModeEnabled: Boolean = true,
    override val benchModeEnabled: Boolean = false,
    override val cooldownMs: Long = 150L,
    override val lockedExposureEnabled: Boolean = true,
    override val lockedShutterNs: Long = 16_000_000L,   // 16 ms — matches CameraLaserDetector historic default
    override val lockedIso: Int = 800,
    // D2–D9 defaults match locked constants (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002)
    override val chromaWeight: Float = CameraLaserDetector.CHROMA_WEIGHT,
    override val scoreThreshold: Float = CameraLaserDetector.SCORE_THRESHOLD,
    override val emaAlpha: Float = CameraLaserDetector.EMA_ALPHA,
    override val neighborFactor: Float = CameraLaserDetector.NEIGHBOR_FACTOR,
    override val cbMax: Int = CameraLaserDetector.CB_MAX,
    override val crMax: Int = CameraLaserDetector.CR_MAX,
    override val minAbsentFrames: Int = PulseStateMachine.MIN_ABSENT_FRAMES,
    override val maxPulseFrames: Int = PulseStateMachine.MAX_PULSE_FRAMES,
) : DetectionConfig
