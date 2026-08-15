package com.sirtverse.detectlab

import android.content.Context
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.DetectionConfig
import com.sirtverse.detectioncore.PulseStateMachine

/**
 * SharedPreferences-backed [DetectionConfig] for DetectLab.
 *
 * The bench conductor drives all D2–D9 dials via:
 *   adb shell run-as com.sirtverse.detectlab sh -c "..."  (or equivalent prefs write)
 *   adb shell am force-stop com.sirtverse.detectlab
 *   adb shell am start -n com.sirtverse.detectlab/.DetectLabActivity
 *
 * Every start() reads the current prefs, so no APK rebuild is needed per rung — the
 * zero-rebuild property that is the whole point of P0 (CC-SIRT-CALIBRATION-CAMPAIGN-002).
 *
 * SharedPreferences file name: [PREFS_FILE] = "shot_timer_settings" — same logical name
 * as the :app module's SettingsStore, but scoped to com.sirtverse.detectlab's sandbox.
 *
 * All defaults match the locked companion-object constants in [CameraLaserDetector] and
 * [PulseStateMachine] so a fresh install with no prefs overrides behaves identically to
 * the pre-P0 baseline. labModeEnabled defaults true so DetectLab always logs events.
 */
class SharedPrefsDetectionConfig(context: Context) : DetectionConfig {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override val labModeEnabled:        Boolean get() = prefs.getBoolean("lab_mode_enabled",  true)
    override val benchModeEnabled:      Boolean get() = prefs.getBoolean("bench_mode_enabled", false)
    override val cooldownMs:            Long    get() = prefs.getInt("cooldown_ms", 150).toLong()
    override val lockedExposureEnabled: Boolean get() = prefs.getBoolean("locked_exposure_enabled", true)
    override val lockedShutterNs:       Long    get() = prefs.getLong("locked_shutter_ns", 16_000_000L)
    override val lockedIso:             Int     get() = prefs.getInt("locked_iso", 800)

    // D2–D9 sweep dials (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002)
    override val chromaWeight:          Float   get() = prefs.getFloat("chroma_weight",   CameraLaserDetector.CHROMA_WEIGHT)
    override val scoreThreshold:        Float   get() = prefs.getFloat("score_threshold", CameraLaserDetector.SCORE_THRESHOLD)
    override val emaAlpha:              Float   get() = prefs.getFloat("ema_alpha",        CameraLaserDetector.EMA_ALPHA)
    override val neighborFactor:        Float   get() = prefs.getFloat("neighbor_factor",  CameraLaserDetector.NEIGHBOR_FACTOR)
    override val cbMax:                 Int     get() = prefs.getInt("cb_max",             CameraLaserDetector.CB_MAX)
    override val crMax:                 Int     get() = prefs.getInt("cr_max",             CameraLaserDetector.CR_MAX)
    override val minAbsentFrames:       Int     get() = prefs.getInt("min_absent_frames",  PulseStateMachine.MIN_ABSENT_FRAMES)
    override val maxPulseFrames:        Int     get() = prefs.getInt("max_pulse_frames",   PulseStateMachine.MAX_PULSE_FRAMES)

    // D10 — Target-Region Exposure Control (CC-SIRT-EXPOSURE-CONTROL-001).
    // Setpoint key matches the bench conductor's pre-wired pref; auto-meter defaults OFF.
    override val exposureTargetLuma:       Int     get() = prefs.getInt("target_luma_setpoint", 150)
    override val exposureAutoMeterEnabled: Boolean get() = prefs.getBoolean("exposure_auto_meter_enabled", false)

    companion object {
        const val PREFS_FILE = "shot_timer_settings"
    }
}
