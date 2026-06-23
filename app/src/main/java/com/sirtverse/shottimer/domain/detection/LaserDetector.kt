package com.sirtverse.shottimer.domain.detection

/**
 * App-layer detection contract — the seam between the timer shell and the
 * (eventually camera-based) laser detector.
 *
 * Milestone 1 implementation: [MockLaserDetector] (button-driven).
 * Milestone 3 implementation: a CameraLaserDetector that wraps the proven
 *   OpenCV pipeline in prototypes/laser-detector-android/LaserDetector.kt and
 *   calls [onHit] whenever its shot accumulator registers a deduplicated green
 *   shot. The timer shell never changes — only the implementation behind this
 *   interface does. That is the whole point of building shell-first.
 */
interface LaserDetector {
    /** Invoked once per registered shot. Set by the screen that owns the timer. */
    var onHit: (() -> Unit)?

    /** Begin watching for hits (open camera, start frame loop, etc.). */
    fun start()

    /** Stop watching and release any resources. */
    fun stop()
}
