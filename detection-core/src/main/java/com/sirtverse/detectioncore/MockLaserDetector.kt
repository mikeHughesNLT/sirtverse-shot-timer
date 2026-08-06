package com.sirtverse.detectioncore

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Mock detector for M1 regression testing AND the app-shell airframe
 * (CC-SIRT-APPSHELL-AIRFRAME-001). On [start], emits a scripted [Detection] stream on the
 * main thread: [IDLE_FRAMES_BEFORE_FIRST_PULSE] idle frames (isShot=false), then an
 * isShot=true pulse every [FRAMES_BETWEEN_PULSES] frames thereafter, cycling through
 * [PULSE_POSITIONS]. [simulateHit] remains for manual single-shot triggering, independent
 * of the scripted stream.
 *
 * **This is the reference cadence the iOS `MockLaserDetector` mirrors** — if these
 * constants change, change them on both platforms so the two shells demo identically.
 */
class MockLaserDetector : LaserDetector {
    override var onDetection: ((Detection) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var frame = 0
    private var pulseIndex = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            emitFrame()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun start() {
        if (active) return
        active = true
        frame = 0
        pulseIndex = 0
        handler.post(tick)
    }

    override fun stop() {
        active = false
        handler.removeCallbacks(tick)
    }

    /** Manual single-shot trigger (lab / bench use), independent of the scripted stream. */
    fun simulateHit() {
        if (!active) return
        emit(isShot = true, normX = 0.5, normY = 0.5)
    }

    private fun emitFrame() {
        val framesSinceWarmup = frame - IDLE_FRAMES_BEFORE_FIRST_PULSE
        val isPulseFrame = framesSinceWarmup >= 0 && framesSinceWarmup % FRAMES_BETWEEN_PULSES == 0
        frame++
        if (isPulseFrame) {
            val (x, y) = PULSE_POSITIONS[pulseIndex % PULSE_POSITIONS.size]
            pulseIndex++
            emit(isShot = true, normX = x, normY = y)
        } else {
            emit(isShot = false, normX = 0.5, normY = 0.5)
        }
    }

    private fun emit(isShot: Boolean, normX: Double, normY: Double) {
        val col = (normX * 3).toInt().coerceIn(0, 2)
        val row = (normY * 3).toInt().coerceIn(0, 2)
        onDetection?.invoke(Detection(
            peakCellX = col, peakCellY = row,
            normX = normX, normY = normY,
            peakScore = if (isShot) 100f else 0f,
            passNeighbor = isShot, passColor = isShot,
            isShot = isShot,
            gridCell = row * 3 + col,
            timestampNs = SystemClock.elapsedRealtimeNanos(),
        ))
    }

    companion object {
        /** ~15 Hz — matches the shared ship-gate fps floor (target-spec §Ship gates). */
        const val FRAME_INTERVAL_MS = 66L
        /** ~1 s of idle (non-shot) frames before the first scripted pulse. */
        const val IDLE_FRAMES_BEFORE_FIRST_PULSE = 15
        /** ~3 s between scripted pulses. */
        const val FRAMES_BETWEEN_PULSES = 45
        /** Cycled pulse positions, normalized 0..1, top-left origin. */
        val PULSE_POSITIONS = listOf(
            0.5 to 0.5,
            0.3 to 0.4,
            0.7 to 0.4,
            0.3 to 0.6,
            0.7 to 0.6,
        )
    }
}
