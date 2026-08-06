package com.sirtverse.shottimer.airframe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sirtverse.shottimer.storage.SettingsStore

/**
 * Airframe increment (CC-SIRT-APPSHELL-AIRFRAME-001): shot-timer spine + sliding panel +
 * target-selection scaffold, driven entirely by [com.sirtverse.detectioncore.MockLaserDetector].
 * Depends ONLY on the `LaserDetector` seam + `Detection` — no Camera2/CameraX import here,
 * by design (see AirframeScreen.kt).
 *
 * Swap point: when Track 1 (CC-SIRT-CAPTURE-CORE-PARITY-001) lands, replace
 * `MockLaserDetector()` in AirframeScreen.kt with the real `CameraLaserDetector` — one line.
 */
class AirframeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        setContent { AirframeApp(settings) }
    }
}
