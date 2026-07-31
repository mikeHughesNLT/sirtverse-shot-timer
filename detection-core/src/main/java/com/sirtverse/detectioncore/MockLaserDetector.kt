package com.sirtverse.detectioncore

import android.os.SystemClock

/**
 * Mock detector: a [Detection] with [Detection.isShot]=true fires only when [simulateHit]
 * is called. Used for M1 regression testing without a real camera.
 */
class MockLaserDetector : LaserDetector {
    override var onDetection: ((Detection) -> Unit)? = null

    private var active = false

    override fun start() { active = true }
    override fun stop() { active = false }

    fun simulateHit() {
        if (!active) return
        onDetection?.invoke(Detection(
            peakCellX = 0, peakCellY = 0,
            normX = 0.5, normY = 0.5,
            peakScore = 100f,
            passNeighbor = true, passColor = true,
            isShot = true,
            gridCell = 4,   // MC
            timestampNs = SystemClock.elapsedRealtimeNanos(),
        ))
    }
}
