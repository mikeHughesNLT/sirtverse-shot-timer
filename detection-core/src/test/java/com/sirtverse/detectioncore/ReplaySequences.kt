package com.sirtverse.detectioncore

/**
 * Committed off-rig replay fixtures for [PulseStateMachine] — CC-SIRT-CAPTURE-CORE-PARITY-001,
 * Deliverable 2. Each sequence is per-frame `isCandidate` (= passNeighbor && passColor, what
 * [CameraLaserDetector.processFrame] feeds into [PulseStateMachine.onFrame]) plus a monotonic
 * elapsed-ms timestamp, at a nominal 30fps (33ms/frame) cadence.
 *
 * iOS's parity test must reproduce the same `isShot` pattern for the same three sequences.
 * Any future session can prove/disprove parity from these fixtures alone — no rig, no phone.
 */
object ReplaySequences {

    data class ReplayFrame(val isCandidate: Boolean, val nowElapsedMs: Long)

    /** App default (`SettingsStore.cooldownMs` fallback, see `SettingsActivity.kt`). */
    const val COOLDOWN_MS = 120L

    private fun frames(vararg candidates: Boolean, stepMs: Long = 33L): List<ReplayFrame> =
        candidates.mapIndexed { i, c -> ReplayFrame(c, i * stepMs) }

    /**
     * (a) One clean pulse: two idle frames, three candidate frames, then idle again.
     * Fires exactly once, on the first candidate frame of the pulse.
     */
    val cleanSinglePulse: List<ReplayFrame> =
        frames(false, false, true, true, true, false, false, false)

    val cleanSinglePulseExpectedIsShot =
        listOf(false, false, true, false, false, false, false, false)

    /**
     * (b) A static bright reflection: candidate from frame 0, persisting for 41 frames straight
     * (well past [PulseStateMachine.MAX_PULSE_FRAMES] = 25). It fires exactly once, on the onset
     * frame — indistinguishable from a real pulse at that instant, same as Android production —
     * then it must NOT fire again for as long as it persists, no matter how long. This is the
     * behavior the missing-resume iOS bug (Daybooks/2026-08-04-CC-SIRT-IOS-SHOTPARITY-001.md)
     * broke in the other direction (froze `greenAbsentCount` and blocked all FUTURE real pulses).
     */
    val staticBrightReflection: List<ReplayFrame> =
        List(41) { i -> ReplayFrame(true, i * 33L) }

    val staticBrightReflectionExpectedIsShot: List<Boolean> =
        listOf(true) + List(40) { false }

    /** Frame index (0-based) from which the state machine must signal a background-EMA resume. */
    const val STATIC_REFLECTION_RESUME_FROM_INDEX = 25 // pulseFrames=26 > MAX_PULSE_FRAMES=25

    /**
     * (c) Rapid double-pulse: two separate clean pulses 132ms apart (> [COOLDOWN_MS] = 120ms),
     * separated by 2 idle frames each ([PulseStateMachine.MIN_ABSENT_FRAMES]). Both must fire —
     * proves the cooldown gate doesn't merge or eat a genuine fast double-tap.
     */
    val rapidDoublePulse: List<ReplayFrame> =
        frames(false, false, true, true, false, false, true, true, false, false)

    val rapidDoublePulseExpectedIsShot =
        listOf(false, false, true, false, false, false, true, false, false, false)
}
