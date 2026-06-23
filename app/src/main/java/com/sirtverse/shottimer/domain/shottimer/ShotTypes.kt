package com.sirtverse.shottimer.domain.shottimer

/**
 * One recorded shot within a session.
 *
 * @param number  1-based shot index within the session.
 * @param timeMs  Elapsed time from the GO cue to this shot (ms).
 * @param splitMs For shot #1 this equals timeMs (the first-shot time).
 *                For shot #n>1 it is the gap from the previous shot.
 */
data class Shot(
    val number: Int,
    val timeMs: Double,
    val splitMs: Double,
)

/**
 * A completed (or in-progress) shot-timer session.
 *
 * Pure data — no Android dependencies — so it is trivially unit-testable and
 * serialisable. Persistence lives in storage/SessionStorage.kt.
 */
data class ShotSession(
    val id: String,
    val startedAtEpochMs: Long,
    val shots: List<Shot>,
    val durationMs: Double,
    val notes: String = "",
) {
    val shotCount: Int get() = shots.size

    /** Time from GO to the first shot, or null if no shots were fired. */
    val firstShotMs: Double? get() = shots.firstOrNull()?.timeMs

    /** Mean of all inter-shot splits (excludes the first-shot time). */
    val averageSplitMs: Double? get() = SplitCalculator.averageSplit(shots)

    /** Fastest inter-shot split, or null with fewer than two shots. */
    val bestSplitMs: Double? get() = SplitCalculator.bestSplit(shots)
}
