package com.sirtverse.detectioncore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Off-rig parity replay suite — CC-SIRT-CAPTURE-CORE-PARITY-001, Deliverable 2.
 *
 * Feeds the three committed [ReplaySequences] through the pure [PulseStateMachine] and asserts
 * the exact `isShot` pattern. `./gradlew :detection-core:test` running this green is the
 * camera-free proof that the extraction in [CameraLaserDetector] didn't change behavior — the
 * extraction was a line-for-line move (see the commit diff), and these sequences encode the
 * exact patterns traced by hand against the pre-extraction `processFrame` logic. iOS's
 * `AVFoundationLaserDetector` parity test must reproduce the same three patterns.
 */
class PulseStateMachineTest {

    private fun replay(frames: List<ReplaySequences.ReplayFrame>, cooldownMs: Long): List<PulseResult> {
        val pulse = PulseStateMachine()
        return frames.map { pulse.onFrame(it.isCandidate, it.nowElapsedMs, cooldownMs) }
    }

    @Test
    fun cleanSinglePulse_firesOnceOnFirstCandidateFrame() {
        val results = replay(ReplaySequences.cleanSinglePulse, ReplaySequences.COOLDOWN_MS)
        assertEquals(ReplaySequences.cleanSinglePulseExpectedIsShot, results.map { it.isShot })
    }

    @Test
    fun staticBrightReflection_firesOnceThenNeverAgainWhilePersisting() {
        val results = replay(ReplaySequences.staticBrightReflection, ReplaySequences.COOLDOWN_MS)
        assertEquals(ReplaySequences.staticBrightReflectionExpectedIsShot, results.map { it.isShot })

        // Bonus rigor (not part of the isShot parity contract, but locks in the mechanism that
        // makes the "never again" half true): background-resume must kick in once the candidate
        // persists past MAX_PULSE_FRAMES, and not before.
        val resumeFlags = results.map { it.resumeBackground }
        assertEquals(
            List(ReplaySequences.STATIC_REFLECTION_RESUME_FROM_INDEX) { false } +
                List(results.size - ReplaySequences.STATIC_REFLECTION_RESUME_FROM_INDEX) { true },
            resumeFlags,
        )
    }

    @Test
    fun rapidDoublePulse_bothPulsesFire() {
        val results = replay(ReplaySequences.rapidDoublePulse, ReplaySequences.COOLDOWN_MS)
        assertEquals(ReplaySequences.rapidDoublePulseExpectedIsShot, results.map { it.isShot })
    }
}
