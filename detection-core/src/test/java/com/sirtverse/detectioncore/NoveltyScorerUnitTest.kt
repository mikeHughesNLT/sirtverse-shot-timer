package com.sirtverse.detectioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoveltyScorer] — CC-SIRT-NOVELTY-KOTLIN-RED-001-r3 T2.
 * Synthetic frames only; no goldens dependency.
 */
class NoveltyScorerUnitTest {

    private fun planes(w: Int, h: Int, rv: Int, gv: Int, bv: Int):
        Triple<ByteArray, ByteArray, ByteArray> {
        val n = w * h
        return Triple(
            ByteArray(n) { rv.toByte() },
            ByteArray(n) { gv.toByte() },
            ByteArray(n) { bv.toByte() })
    }

    @Test
    fun `coefficient signs match the fitted triple`() {
        // novelty_scorer.py L63: #6 enters NEGATIVELY (saturating dots have
        // lower contrast than ink), #3 and #9 positively. A sign flip in the
        // port is silent — pin it.
        assertTrue(NoveltyScorer.COEF[0] < 0.0)
        assertTrue(NoveltyScorer.COEF[1] > 0.0)
        assertTrue(NoveltyScorer.COEF[2] > 0.0)
        val base = NoveltyScorer.tripleScore(
            NoveltyScorer.SCALER_MEAN[0], NoveltyScorer.SCALER_MEAN[1],
            NoveltyScorer.SCALER_MEAN[2])
        assertTrue(NoveltyScorer.tripleScore(90.0, 247.0602, 35.0648) < base)
        assertTrue(NoveltyScorer.tripleScore(76.7204, 255.0, 35.0648) > base)
        assertTrue(NoveltyScorer.tripleScore(76.7204, 247.0602, 60.0) > base)
        // logistic at the exact fitted mean vector = sigmoid(intercept)
        assertEquals(1.0 / (1.0 + Math.exp(-NoveltyScorer.INTERCEPT)), base, 1e-9)
    }

    @Test
    fun `constant-ON synthetic scores exactly zero`() {
        // ref == frame, no red-excess content: zero candidates -> score 0.0.
        // (Corpus const-ON pairs are NOT exact zero — the excess mask admits
        // printed ink; measured in T1, documented in the daybook. The exact
        // zero path is only reachable synthetically.)
        val w = 64; val h = 48
        val gray = ByteArray(w * h) { 180.toByte() }
        val (r, g, b) = planes(w, h, 170, 165, 160)   // dull, no excess
        val res = NoveltyScorer().score(gray, gray, r, g, b, w, h)
        assertEquals(0.0, res.score, 0.0)
        assertFalse(res.fired)
        assertEquals(0, res.nCandidates)
    }

    @Test
    fun `novel red dot fires`() {
        // Reference: flat gray. Frame: same + a bright 5x5 dot that is also
        // red-excess — the classic recovered-miss shape.
        val w = 64; val h = 48
        val ref = ByteArray(w * h) { 150.toByte() }
        val gray = ByteArray(w * h) { 150.toByte() }
        val (r, g, b) = planes(w, h, 140, 145, 148)
        for (y in 20 until 25) for (x in 30 until 35) {
            gray[y * w + x] = 255.toByte()
            r[y * w + x] = 255.toByte(); g[y * w + x] = 200.toByte(); b[y * w + x] = 200.toByte()
        }
        val res = NoveltyScorer().score(gray, ref, r, g, b, w, h)
        assertTrue("nCandidates=${res.nCandidates}", res.nCandidates >= 1)
        assertEquals(255, res.f03VPeak)
        assertTrue("f09=${res.f09Novelty}", res.f09Novelty > 30.0)
        assertTrue("score=${res.score}", res.score >= NoveltyScorer.FIRE_THRESHOLD)
        assertTrue(res.fired)
    }

    @Test
    fun `integer warp direction matches the Python register semantics`() {
        // Ref shifted +2 px in x relative to frame: warp by -t must realign.
        // Frame: dot at (30,20). Ref: same dot at (32,20). After registration
        // the up-novelty at the dot must collapse (score well below a
        // no-registration run).
        val w = 64; val h = 48
        fun scene(dotX: Int): ByteArray {
            val img = ByteArray(w * h) { 120.toByte() }
            for (y in 20 until 24) for (x in dotX until dotX + 4) img[y * w + x] = 250.toByte()
            return img
        }
        val frame = scene(30)
        val refShifted = scene(32)
        val (r, g, b) = planes(w, h, 110, 115, 118)   // no red excess anywhere
        val res = NoveltyScorer().score(frame, refShifted, r, g, b, w, h)
        // t = position of REF relative to FRAME (Python register() docstring,
        // novelty_scorer.py L103-107): ref moved +2 px right -> t ~ +2, and
        // the applied integer warp is round(-t) = -2, realigning the dot.
        assertTrue("shift dx=${res.shiftDx} (expect ~ +2)", res.shiftDx > 1.0)
        assertTrue("residual novelty f09=${res.f09Novelty}", res.f09Novelty < 30.0)
    }
}
