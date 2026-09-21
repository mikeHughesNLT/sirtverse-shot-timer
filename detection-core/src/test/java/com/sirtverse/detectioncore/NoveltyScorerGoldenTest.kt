package com.sirtverse.detectioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Golden parity test — CC-SIRT-NOVELTY-KOTLIN-RED-001-r3 T2.
 *
 * Runs [NoveltyScorer] over the 70 red goldens exported by
 * `sirtverse-capture/research/autoresearch/novelty_red_goldens.py` into
 * `~/.sirt/goldens/red-70/` and asserts the acceptance bar:
 *   - every golden's score within ±0.02 of the Python expectation
 *   - ≥ 68/70 verdict agreement at threshold 0.8025
 *   - integer registration warp matches (subpixel shift within 0.75 px)
 *
 * `frame.pgm` is the byte-exact Python BGR2GRAY output; `frame.ppm` carries
 * chroma (excess red + V=max(R,G,B)). The test therefore exercises the
 * ALGORITHM (registration, tolerant diff, features, logistic), not a second
 * BGR2GRAY rounding implementation — recorded deviation, daybook T1.
 *
 * Skips cleanly (assumeTrue) when the goldens dir is absent, so PR checks on
 * other machines don't red.
 */
class NoveltyScorerGoldenTest {

    companion object {
        val GOLDENS_DIR = File(System.getProperty("user.home"), ".sirt/goldens/red-70")
        const val SCORE_TOL = 0.02
        const val MIN_AGREE = 68
    }

    /** Minimal PNM (P5/P6 binary) reader — no maxval other than 255 occurs. */
    private fun readPnm(f: File): Triple<ByteArray, Int, Int> {
        val bytes = f.readBytes()
        var i = 0
        fun token(): String {
            while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
            if (i < bytes.size && bytes[i].toInt().toChar() == '#') {
                while (i < bytes.size && bytes[i].toInt() != '\n'.code) i++
                return token()
            }
            val sb = StringBuilder()
            while (i < bytes.size && !bytes[i].toInt().toChar().isWhitespace()) {
                sb.append(bytes[i].toInt().toChar()); i++
            }
            return sb.toString()
        }
        val magic = token()
        val w = token().toInt()
        val h = token().toInt()
        token() // maxval (255)
        i++     // single whitespace after maxval
        val n = if (magic == "P6") w * h * 3 else w * h
        return Triple(bytes.copyOfRange(i, i + n), w, h)
    }

    private data class Expected(
        val kind: String, val score: Double, val verdict: Boolean,
        val f06: Double, val f03: Int, val f09: Double,
        val shiftDx: Double, val shiftDy: Double, val nCandidates: Int,
    )

    /** expected.json is written by the exporter with fixed keys — parse the
     *  handful of numeric/string fields without a JSON dependency. */
    private fun readExpected(f: File): Expected {
        val t = f.readText()
        fun num(key: String): Double {
            val m = Regex(""""$key":\s*(-?[0-9.eE+-]+)""").find(t)
                ?: error("missing $key in ${f.path}")
            return m.groupValues[1].toDouble()
        }
        fun bool(key: String): Boolean =
            Regex(""""$key":\s*(true|false)""").find(t)!!.groupValues[1] == "true"
        fun str(key: String): String =
            Regex(""""$key":\s*"([^"]*)"""").find(t)!!.groupValues[1]
        val shift = Regex(""""shift":\s*\[\s*(-?[0-9.eE+-]+),\s*(-?[0-9.eE+-]+)\s*]""").find(t)!!
        return Expected(
            kind = str("kind"),
            score = num("score"),
            verdict = bool("verdict"),
            f06 = num("f06_local_contrast"),
            f03 = num("f03_v_peak").toInt(),
            f09 = num("f09_novelty"),
            shiftDx = shift.groupValues[1].toDouble(),
            shiftDy = shift.groupValues[2].toDouble(),
            nCandidates = num("n_candidates").toInt(),
        )
    }

    @Test
    fun goldensMatchPython() {
        assumeTrue("goldens not exported on this machine", GOLDENS_DIR.isDirectory)
        val dirs = GOLDENS_DIR.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertEquals("golden count", 70, dirs.size)

        val scorer = NoveltyScorer(color = "red")
        var agree = 0
        var worstScoreDelta = 0.0
        var worstDir = ""
        val rows = StringBuilder()
        for (d in dirs) {
            val (ref, rw, rh) = readPnm(File(d, "ref.pgm"))
            val (gray, w, h) = readPnm(File(d, "frame.pgm"))
            val (rgb, w2, h2) = readPnm(File(d, "frame.ppm"))
            assertTrue("dims $d", rw == w && rh == h && w2 == w && h2 == h)
            val n = w * h
            val r = ByteArray(n); val g = ByteArray(n); val b = ByteArray(n)
            for (i in 0 until n) {
                r[i] = rgb[i * 3]; g[i] = rgb[i * 3 + 1]; b[i] = rgb[i * 3 + 2]
            }
            val exp = readExpected(File(d, "expected.json"))
            val res = scorer.score(gray, ref, r, g, b, w, h)

            val dScore = kotlin.math.abs(res.score - exp.score)
            if (dScore > worstScoreDelta) { worstScoreDelta = dScore; worstDir = d.name }
            val okVerdict = (res.fired == exp.verdict)
            if (okVerdict) agree++
            // registration: integer warp identical after round (0.75 px slack)
            val dShift = kotlin.math.abs(res.shiftDx - exp.shiftDx) +
                kotlin.math.abs(res.shiftDy - exp.shiftDy)
            val row = ("%-22s %-9s exp=%.4f got=%.4f d=%.4f verdict=%s dShift=%.3f " +
                "f06 %.2f/%.2f f09 %.2f/%.2f f03 %d/%d cand %d/%d\n").format(
                    d.name, exp.kind, exp.score, res.score, dScore,
                    if (okVerdict) "ok" else "MISMATCH",
                    dShift, exp.f06, res.f06LocalContrast,
                    exp.f09, res.f09Novelty, exp.f03, res.f03VPeak,
                    exp.nCandidates, res.nCandidates)
            rows.append(row)
            print(row)
            assertTrue(
                "score delta ${"%.4f".format(dScore)} > $SCORE_TOL in $d " +
                    "(exp ${exp.score}, got ${res.score})",
                dScore <= SCORE_TOL)
            assertTrue("registration drift ${"%.3f".format(dShift)} px in $d",
                dShift <= 1.5)
        }
        println(rows.toString())
        println("verdict agreement: $agree/70, worst score delta " +
            "${"%.4f".format(worstScoreDelta)} ($worstDir)")
        assertTrue("verdict agreement $agree < $MIN_AGREE", agree >= MIN_AGREE)
    }
}
