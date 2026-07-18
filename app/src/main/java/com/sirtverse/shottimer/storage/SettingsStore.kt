package com.sirtverse.shottimer.storage

import android.content.Context

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

    /** Random start delay in the configured [min,max] window. */
    fun randomStartDelayMs(): Long {
        val lo = startDelayMinMs
        val hi = startDelayMaxMs.coerceAtLeast(lo)
        return (lo..hi).random().toLong()
    }

    private companion object {
        const val KEY_DELAY_MIN = "delay_min_ms"
        const val KEY_DELAY_MAX = "delay_max_ms"
        const val KEY_COLOR = "laser_color"
        const val KEY_COOLDOWN = "cooldown_ms"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_LAB_MODE = "lab_mode_enabled"
    }
}
