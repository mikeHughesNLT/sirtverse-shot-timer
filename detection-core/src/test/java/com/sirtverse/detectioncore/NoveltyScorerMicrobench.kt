package com.sirtverse.detectioncore

import org.junit.Test

/**
 * T5 microbench — CC-SIRT-NOVELTY-KOTLIN-RED-001-r3.
 *
 * Host-JVM timing of [NoveltyScorer.score] on a synthetic 640x480 frame
 * (deterministic content: paper-gray + one 5x5 red dot + an ink-like red bar
 * so the candidate path runs). Iterations: env NOVELTY_BENCH (default 3 as a
 * smoke; the brief's x100 run uses NOVELTY_BENCH=100).
 *
 * Prints total ms/frame for score() and for phaseShift() alone (registration
 * = #9's cost center) so the >4 ms over-budget profile is a measurement, not
 * a guess. Results are reported in bench/NOVELTY-KOTLIN-RED-001.md.
 */
class NoveltyScorerMicrobench {

    private fun frame(w: Int, h: Int, withDot: Boolean): Array<ByteArray> {
        val n = w * h
        val gray = ByteArray(n) { 150.toByte() }
        val r = ByteArray(n) { 140.toByte() }
        val g = ByteArray(n) { 145.toByte() }
        val b = ByteArray(n) { 148.toByte() }
        // ink-like red bar (always present — excess candidates every frame)
        for (y in 300 until 340) for (x in 100 until 400) {
            gray[y * w + x] = 110.toByte()
            r[y * w + x] = 180.toByte(); g[y * w + x] = 90.toByte(); b[y * w + x] = 85.toByte()
        }
        if (withDot) {
            for (y in 200 until 205) for (x in 320 until 325) {
                gray[y * w + x] = 255.toByte()
                r[y * w + x] = 255.toByte(); g[y * w + x] = 200.toByte(); b[y * w + x] = 200.toByte()
            }
        }
        return arrayOf(gray, r, g, b)
    }

    @Test
    fun microbench() {
        val iters = (System.getenv("NOVELTY_BENCH") ?: "3").toInt()
        val w = 640; val h = 480
        val scorer = NoveltyScorer(color = "red")
        val (refG) = frame(w, h, withDot = false)
        val (gray, r, g, b) = frame(w, h, withDot = true)

        // warmup (JIT)
        repeat(2) { scorer.score(gray, refG, r, g, b, w, h) }

        val t0 = System.nanoTime()
        repeat(iters) { scorer.score(gray, refG, r, g, b, w, h) }
        val scoreMs = (System.nanoTime() - t0) / 1e6 / iters

        val t1 = System.nanoTime()
        repeat(iters) { scorer.phaseShift(gray, refG, w, h) }
        val shiftMs = (System.nanoTime() - t1) / 1e6 / iters

        println("BENCH 640x480 x$iters: score()=${"%.1f".format(scoreMs)} ms/frame, " +
            "phaseShift()=${"%.1f".format(shiftMs)} ms/frame " +
            "(${"%.0f".format(100.0 * shiftMs / scoreMs)}% of score), " +
            "features+rest=${"%.1f".format(scoreMs - shiftMs)} ms/frame")
        println("BENCH reference points: Python 56.4 ms (thousand_offline measured), " +
            "A-020 budget < 4 ms")
    }
}
