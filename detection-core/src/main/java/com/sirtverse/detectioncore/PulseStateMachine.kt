package com.sirtverse.detectioncore

/**
 * The `isShot` pulse state machine — extracted verbatim from [CameraLaserDetector.processFrame]
 * (B-4 iteration history) for CC-SIRT-CAPTURE-CORE-PARITY-001.
 *
 * Pure, camera-free, no I/O: inputs are the per-frame gate results that
 * [CameraLaserDetector] already computes ([isCandidate] = passNeighbor && passColor), a
 * monotonic timestamp, and the configured cooldown. This is the parity spec — iOS's
 * `AVFoundationLaserDetector` must reproduce the same [PulseResult.isShot] pattern given the
 * same frame sequence. Safe to unit test off-rig (see [ReplaySequences]).
 *
 * State:
 *  - [pulseFrames] — consecutive candidate frames in the current pulse.
 *  - [greenAbsentCount] — consecutive non-candidate frames since the last candidate frame.
 *  - `lastShotElapsedMs` — for the cooldown/refractory gap.
 */
class PulseStateMachine(
    private val minAbsentFrames: Int = MIN_ABSENT_FRAMES,
    private val maxPulseFrames: Int = MAX_PULSE_FRAMES,
) {
    companion object {
        const val MIN_ABSENT_FRAMES = 2
        const val MAX_PULSE_FRAMES = 25
    }

    var greenAbsentCount: Int = minAbsentFrames
        private set
    var pulseFrames: Int = 0
        private set
    private var lastShotElapsedMs = -1L

    /** Resets to the same initial state as a fresh instance (mirrors `CameraLaserDetector.start()`). */
    fun reset() {
        greenAbsentCount = minAbsentFrames
        pulseFrames = 0
        lastShotElapsedMs = -1L
    }

    /**
     * Advance the state machine by one frame.
     *
     * @param isCandidate this frame's `passColor` result (score + neighbor + green gates all passed).
     * @param nowElapsedMs monotonic clock for this frame (`SystemClock.elapsedRealtime()` on Android).
     * @param cooldownMs refractory period between shots.
     */
    fun onFrame(isCandidate: Boolean, nowElapsedMs: Long, cooldownMs: Long): PulseResult {
        var isShot = false
        var resumeBackground = false
        var nearMiss = false
        var gapMs = -1L

        if (isCandidate) {
            pulseFrames++
            // B-4 iteration 4: candidate persisting beyond maxPulseFrames = static reflection
            // or lighting step change. Signal the caller to resume background adaptation so
            // the new scene is absorbed (camera-side effect; not this class's concern).
            if (pulseFrames > maxPulseFrames) {
                resumeBackground = true
            }
            if (greenAbsentCount >= minAbsentFrames) {
                gapMs = if (lastShotElapsedMs >= 0) nowElapsedMs - lastShotElapsedMs else Long.MAX_VALUE
                if (gapMs >= cooldownMs && pulseFrames <= maxPulseFrames) {
                    isShot = true
                    lastShotElapsedMs = nowElapsedMs
                }
            }
            greenAbsentCount = 0
        } else {
            if (greenAbsentCount == 0 && pulseFrames in 1..maxPulseFrames) {
                nearMiss = true
            }
            pulseFrames = 0
            greenAbsentCount++
            resumeBackground = true
        }

        return PulseResult(isShot = isShot, resumeBackground = resumeBackground, nearMiss = nearMiss, gapMs = gapMs)
    }
}

/**
 * Per-frame output of [PulseStateMachine.onFrame].
 *
 * @param isShot the parity contract: true only on the frame that fires the shot event.
 * @param resumeBackground true when the caller should run its (camera-dependent) background
 *   EMA update this frame — either because the candidate has persisted past `maxPulseFrames`,
 *   or because this frame was not a candidate at all.
 * @param nearMiss true on the frame a pulse ends without ever having fired (lab logging only).
 * @param gapMs the cooldown gap computed this frame, or -1 if the cooldown gate wasn't evaluated.
 */
data class PulseResult(
    val isShot: Boolean,
    val resumeBackground: Boolean,
    val nearMiss: Boolean,
    val gapMs: Long,
)
