package com.sirtverse.shottimer.storage

import android.content.Context
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.PulseStateMachine

/**
 * Thin typed wrapper over SharedPreferences for the Settings screen.
 * Defaults match the brief: 3–5 s random start delay, green laser, 120 ms cooldown.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("shot_timer_settings", Context.MODE_PRIVATE)

    enum class LaserColor { RED, GREEN, BOTH }

    var startDelayMinMs: Int
        get() = prefs.getInt(KEY_DELAY_MIN, 3000)
        set(v) = prefs.edit().putInt(KEY_DELAY_MIN, v).apply()

    var startDelayMaxMs: Int
        get() = prefs.getInt(KEY_DELAY_MAX, 5000)
        set(v) = prefs.edit().putInt(KEY_DELAY_MAX, v).apply()

    var laserColor: LaserColor
        get() = LaserColor.valueOf(prefs.getString(KEY_COLOR, LaserColor.GREEN.name)!!)
        set(v) = prefs.edit().putString(KEY_COLOR, v.name).apply()

    /** Cooldown / debounce window in ms (used by the real detector at M3). */
    var cooldownMs: Int
        get() = prefs.getInt(KEY_COOLDOWN, 120)
        set(v) = prefs.edit().putInt(KEY_COOLDOWN, v).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    /**
     * Lab mode: enables the detection debug overlay (score, fps) and JSONL event logging.
     * Toggle in Settings → required for rig-referee run (B-4, CC-SIRT-TIMER-M3-DETECT-001).
     */
    var labModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_LAB_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_LAB_MODE, v).apply()

    /**
     * Bench mode: when true, the JSONL log emits a "frame" record on every processed frame
     * containing the full peak record (y_delta, chroma_delta, peak_score, cb, cr, cell x/y,
     * all gate booleans, isShot). Required for the per-channel confidence analysis in the
     * calibration campaign (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002).
     */
    var benchModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BENCH_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_BENCH_MODE, v).apply()

    /**
     * Feature 22 (Locked Exposure Context, Detection-Arsenal.md — #1 impact-ranked).
     * Defaults ON (B0 TARGET-EXPOSURE-001: prevents ~5 phantoms/10s on fresh session).
     */
    var lockedExposureEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCKED_EXPOSURE, true)
        set(v) = prefs.edit().putBoolean(KEY_LOCKED_EXPOSURE, v).apply()

    /**
     * B2 TARGET-EXPOSURE-001: runtime-tunable shutter (ns) and ISO for the exposure sweep.
     * Defaults match historical constants so existing behaviour is unchanged.
     * Override via ADB prefs write to sweep rungs without rebuilding the APK.
     */
    var lockedShutterNs: Long
        get() = prefs.getLong(KEY_LOCKED_SHUTTER_NS, 16_000_000L)   // 16 ms default
        set(v) = prefs.edit().putLong(KEY_LOCKED_SHUTTER_NS, v).apply()

    var lockedIso: Int
        get() = prefs.getInt(KEY_LOCKED_ISO, 800)
        set(v) = prefs.edit().putInt(KEY_LOCKED_ISO, v).apply()

    // ── D2–D9 sweep dials — P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 ─────────────────────────
    // Defaults match the locked companion-object constants in CameraLaserDetector /
    // PulseStateMachine so a fresh install with no prefs overrides behaves identically to
    // the pre-P0 baseline. Override via ADB prefs write for each sweep rung.

    /** D2 — chroma contribution to the combined score (0 = kill-switch, restores luma-only). */
    var chromaWeight: Float
        get() = prefs.getFloat(KEY_CHROMA_WEIGHT, CameraLaserDetector.CHROMA_WEIGHT)
        set(v) = prefs.edit().putFloat(KEY_CHROMA_WEIGHT, v).apply()

    /** D3 — minimum combined score to enter the neighbor / color / pulse pipeline. */
    var scoreThreshold: Float
        get() = prefs.getFloat(KEY_SCORE_THRESHOLD, CameraLaserDetector.SCORE_THRESHOLD)
        set(v) = prefs.edit().putFloat(KEY_SCORE_THRESHOLD, v).apply()

    /** D4 — per-cell background EMA rate (0–1). */
    var emaAlpha: Float
        get() = prefs.getFloat(KEY_EMA_ALPHA, CameraLaserDetector.EMA_ALPHA)
        set(v) = prefs.edit().putFloat(KEY_EMA_ALPHA, v).apply()

    /** D5 — neighbor gate threshold = scoreThreshold × neighborFactor. */
    var neighborFactor: Float
        get() = prefs.getFloat(KEY_NEIGHBOR_FACTOR, CameraLaserDetector.NEIGHBOR_FACTOR)
        set(v) = prefs.edit().putFloat(KEY_NEIGHBOR_FACTOR, v).apply()

    /** D6 — green color gate: Cb must be below this value. */
    var cbMax: Int
        get() = prefs.getInt(KEY_CB_MAX, CameraLaserDetector.CB_MAX)
        set(v) = prefs.edit().putInt(KEY_CB_MAX, v).apply()

    /** D6 — green color gate: Cr must be below this value. */
    var crMax: Int
        get() = prefs.getInt(KEY_CR_MAX, CameraLaserDetector.CR_MAX)
        set(v) = prefs.edit().putInt(KEY_CR_MAX, v).apply()

    /** D8 — frames candidate must be absent before a new pulse can fire. */
    var minAbsentFrames: Int
        get() = prefs.getInt(KEY_MIN_ABSENT_FRAMES, PulseStateMachine.MIN_ABSENT_FRAMES)
        set(v) = prefs.edit().putInt(KEY_MIN_ABSENT_FRAMES, v).apply()

    /** D9 — candidate frames beyond this trigger background resume (reflection guard). */
    var maxPulseFrames: Int
        get() = prefs.getInt(KEY_MAX_PULSE_FRAMES, PulseStateMachine.MAX_PULSE_FRAMES)
        set(v) = prefs.edit().putInt(KEY_MAX_PULSE_FRAMES, v).apply()

    /** Random start delay in the configured [min,max] window. */
    fun randomStartDelayMs(): Long {
        val lo = startDelayMinMs
        val hi = startDelayMaxMs.coerceAtLeast(lo)
        return (lo..hi).random().toLong()
    }

    private companion object {
        const val KEY_DELAY_MIN          = "delay_min_ms"
        const val KEY_DELAY_MAX          = "delay_max_ms"
        const val KEY_COLOR              = "laser_color"
        const val KEY_COOLDOWN           = "cooldown_ms"
        const val KEY_SOUND              = "sound_enabled"
        const val KEY_LAB_MODE           = "lab_mode_enabled"
        const val KEY_BENCH_MODE         = "bench_mode_enabled"
        const val KEY_LOCKED_EXPOSURE    = "locked_exposure_enabled"
        const val KEY_LOCKED_SHUTTER_NS  = "locked_shutter_ns"
        const val KEY_LOCKED_ISO         = "locked_iso"
        // D2–D9 (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002)
        const val KEY_CHROMA_WEIGHT      = "chroma_weight"
        const val KEY_SCORE_THRESHOLD    = "score_threshold"
        const val KEY_EMA_ALPHA          = "ema_alpha"
        const val KEY_NEIGHBOR_FACTOR    = "neighbor_factor"
        const val KEY_CB_MAX             = "cb_max"
        const val KEY_CR_MAX             = "cr_max"
        const val KEY_MIN_ABSENT_FRAMES  = "min_absent_frames"
        const val KEY_MAX_PULSE_FRAMES   = "max_pulse_frames"
    }
}
