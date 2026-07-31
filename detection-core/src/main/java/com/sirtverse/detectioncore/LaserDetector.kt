package com.sirtverse.detectioncore

/**
 * Detection contract — the seam between the shell and the laser detector.
 *
 * Emits a [Detection] every processed frame via [onDetection].
 * Consumers act on [Detection.isShot] for shot-timer events and on all frames
 * for live visualisation (DetectLab).
 */
interface LaserDetector {
    /** Invoked on the main thread for every processed frame. */
    var onDetection: ((Detection) -> Unit)?

    fun start()
    fun stop()
}
