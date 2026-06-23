package com.sirtverse.shottimer.domain.shottimer

/**
 * Pure split math. Kept separate from [Shot] so timing rules live in one place
 * and can be unit-tested without constructing sessions.
 *
 * Convention (matches LaserDetector's shot accumulator on the detection side):
 *   - "first-shot time" = elapsed ms from GO to shot #1.
 *   - "split"           = elapsed gap between consecutive shots.
 *   - The first-shot time is NOT counted as a split.
 */
object SplitCalculator {

    /** Inter-shot splits only (shot #2 onward). Empty for <2 shots. */
    fun splits(shots: List<Shot>): List<Double> =
        if (shots.size < 2) emptyList()
        else shots.drop(1).map { it.splitMs }

    fun averageSplit(shots: List<Shot>): Double? =
        splits(shots).takeIf { it.isNotEmpty() }?.average()

    fun bestSplit(shots: List<Shot>): Double? =
        splits(shots).minOrNull()

    fun worstSplit(shots: List<Shot>): Double? =
        splits(shots).maxOrNull()
}
