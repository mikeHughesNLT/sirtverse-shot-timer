package com.sirtverse.shottimer.domain.detection

/**
 * Milestone 1 detector: no camera, no CV. A hit happens only when the user taps
 * "Simulate Laser Hit", which calls [simulateHit]. This lets us prove the entire
 * timer → shot → split → save → history loop before camera complexity enters.
 */
class MockLaserDetector : LaserDetector {
    override var onHit: (() -> Unit)? = null

    private var active = false

    override fun start() { active = true }
    override fun stop() { active = false }

    /** Called by the UI's "Simulate Laser Hit" button. */
    fun simulateHit() {
        if (active) onHit?.invoke()
    }
}
