package com.sirtverse.detectioncore

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NoveltyScorer — pure-Kotlin port of the red novelty triple (#6 + #3 + #9)
 * from `SIRTverse/src/core/novelty_scorer.py` (CC-SIRT-NOVELTY-KOTLIN-RED-001-r3
 * T2). Zero Android imports so the exact class under test on the JVM is the
 * class that ships in the APK.
 *
 * What is mirrored, line-cited to the Python (SIRTverse @ master 2026-09-20):
 *  - fitted constants          novelty_scorer.py L61-68  (provenance there — do not retune)
 *  - register()                L102-121  phaseCorrelate + INTEGER warp of the ref,
 *                              INTER_NEAREST / BORDER_REPLICATE semantics
 *  - tolerant_diff()           L124-135  up = max(0, frame - dilate3(ref_w)) — up-only
 *  - excess_map()              L138-145  red = R - max(G,B), int arithmetic
 *  - candidate floors          L72-75    EXCESS_MIN=20, V_MIN=50, NOVELTY_MIN=30,
 *                              AREA [2, 20000]
 *  - _blob_features()          L191-228  #6 blob-mean minus ring (5x5 vs 45x45
 *                              square dilation = Chebyshev 2..22), #3 max V, #9 mean up
 *  - frame-aggregate scoring   L343-350  per-feature MAX over blobs, then logistic
 *                              (the only measured configuration that meets the bar)
 *  - triple_score()            L148-156  StandardScaler + logistic
 *
 * Registration mirrors OpenCV 4.x `phasecorr.cpp` exactly in algorithm:
 * sqrt-Hann window (0.5*(1-cos(2*pi*k/(N-1))) outer product, square-rooted),
 * zero-pad, forward DFTs, cross-power P = F1 * conj(F2), normalize by
 * mag/(mag^2 + FLT_EPSILON), inverse DFT (1/(M*N) scale), fftShift, peak,
 * 5x5 weighted centroid (raw signed weights, sum + DBL_EPSILON), and
 * shift = (cols/2, rows/2) - centroid.
 *
 * DOCUMENTED DEVIATIONS (each measured harmless against the red-70 goldens;
 * see bench/NOVELTY-KOTLIN-RED-001.md):
 *  1. Blob mask = the 8-connected component's pixels, not a re-rasterized
 *     drawContours fill. Identical for compact blobs (dots, glints, ink);
 *     differs only for 1-px spur pathologies and holes-in-blobs.
 *  2. Contour area = shoelace over the Moore-traced outer boundary through
 *     pixel centers — the same lattice polygon cv2.contourArea spans for
 *     CHAIN_APPROX_SIMPLE external contours.
 *  3. All math in Double (Python mixes float32 arrays). Deviations ~1e-7.
 *  4. Reported centroid = component pixel centroid (Python: contour polygon
 *     moments). Centroid is reported only; never scored.
 *  5. Non-power-of-2 DFT sizes via Bluestein (OpenCV uses mixed-radix
 *     2/3/5). Mathematically identical transforms; the PADDED SIZES match
 *     getOptimalDFTSize exactly, because the fftShift DC-position/center
 *     convention carries a +0.5 px bias on odd axes (measured 2026-09-20:
 *     optimal 135 vs pow2 128 shifted every shiftDy by 0.5, flipping the
 *     integer warp on borderline goldens).
 *
 * cr_mean (Python's soft-hue input) is NOT computed: goldens and the shipped
 * seam both run hue_soft_weight = 0.0, where it cannot affect the score.
 */
class NoveltyScorer(
    private val color: String = "red",
    private val threshold: Double = FIRE_THRESHOLD,
) {
    data class Result(
        val score: Double,
        val fired: Boolean,
        val f06LocalContrast: Double,
        val f03VPeak: Int,
        val f09Novelty: Double,
        val nCandidates: Int,
        val shiftDx: Double,
        val shiftDy: Double,
        val centroidX: Double,
        val centroidY: Double,
    )

    companion object {
        // novelty_scorer.py L61-68 — fitted 2026-09-15 on DISCRIMINATOR-FEATURES-001
        val SCALER_MEAN = doubleArrayOf(76.72037037037036, 247.0601851851852, 35.06481481481482)
        val SCALER_SCALE = doubleArrayOf(18.99613878533516, 9.2085451440176, 44.7059007100745)
        val COEF = doubleArrayOf(-0.5546457064104928, 2.099978996340539, 2.9060972275617822)
        const val INTERCEPT = 1.8921950423624263
        const val FIRE_THRESHOLD = 0.8025

        // novelty_scorer.py L72-75 — candidate floors (stated, not tuned)
        const val EXCESS_MIN = 20
        const val V_MIN = 50
        const val NOVELTY_MIN = 30.0
        const val AREA_MIN = 2.0
        const val AREA_MAX = 20000.0

        /** triple_score() — novelty_scorer.py L148-156. */
        fun tripleScore(f06: Double, f03: Double, f09: Double): Double {
            val x0 = (f06 - SCALER_MEAN[0]) / SCALER_SCALE[0]
            val x1 = (f03 - SCALER_MEAN[1]) / SCALER_SCALE[1]
            val x2 = (f09 - SCALER_MEAN[2]) / SCALER_SCALE[2]
            val z = INTERCEPT + COEF[0] * x0 + COEF[1] * x1 + COEF[2] * x2
            return 1.0 / (1.0 + exp(-z))
        }
    }

    /**
     * Score one frame against a supplied reference — the golden/bench path
     * (Python: `score_frame(frame_bgr, reference=ref_bgr)`, L287-370).
     *
     * @param gray  row-major luma, w*h bytes (unsigned)
     * @param refGray  row-major luma reference, same dimensions
     * @param r,g,b  row-major color planes, same dimensions (excess + V only)
     */
    fun score(
        gray: ByteArray, refGray: ByteArray,
        r: ByteArray, g: ByteArray, b: ByteArray,
        w: Int, h: Int,
    ): Result {
        // ── register() — Python L102-121 ─────────────────────────────────────
        val shift = phaseShift(gray, refGray, w, h)
        val ix = roundHalfEven(-shift[0])
        val iy = roundHalfEven(-shift[1])
        val refW = warpReplicate(refGray, w, h, ix, iy)

        // ── tolerant_diff() — Python L124-135 (up direction only) ────────────
        val dil = dilate3(refW, w, h)
        val up = DoubleArray(w * h)
        for (i in up.indices) {
            val d = (gray[i].toInt() and 0xFF) - dil[i].toDouble()
            up[i] = if (d > 0.0) min(d, 255.0) else 0.0
        }

        // ── candidate masks — Python L305-312 ────────────────────────────────
        val n = w * h
        val union = BooleanArray(n)
        val excMask = BooleanArray(n)
        for (i in 0 until n) {
            val rv = r[i].toInt() and 0xFF
            val gv = g[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            val exc = if (color == "red") rv - max(gv, bv) else gv - max(rv, bv)
            val v = max(rv, max(gv, bv))
            val e = exc >= EXCESS_MIN && v >= V_MIN
            excMask[i] = e
            union[i] = e || up[i] >= NOVELTY_MIN
        }

        // ── components + per-blob features — Python L314-328, L191-228 ───────
        val blobs = collectBlobs(union, w, h)
        var f06 = Double.NEGATIVE_INFINITY
        var f03 = 0
        var f09 = Double.NEGATIVE_INFINITY
        var bestF09 = Double.NEGATIVE_INFINITY
        var bestCx = 0.0
        var bestCy = 0.0
        var nCand = 0
        for (blob in blobs) {
            if (blob.area < AREA_MIN || blob.area > AREA_MAX) continue
            nCand++
            val m = blob.pixels
            var graySum = 0.0
            var upSum = 0.0
            var vPeak = 0
            for (p in m) {
                graySum += (gray[p].toInt() and 0xFF).toDouble()
                upSum += up[p]
                val rv = r[p].toInt() and 0xFF
                val gv = g[p].toInt() and 0xFF
                val bv = b[p].toInt() and 0xFF
                vPeak = max(vPeak, max(rv, max(gv, bv)))
            }
            val blobMean = graySum / m.size
            val ringMean = ringMean(blob, gray, w, h)
            val lc = blobMean - ringMean
            val nov = upSum / m.size
            if (lc > f06) f06 = lc
            if (vPeak > f03) f03 = vPeak
            if (nov > f09) f09 = nov
            if (nov > bestF09) {
                bestF09 = nov
                bestCx = blob.cx
                bestCy = blob.cy
            }
        }

        if (nCand == 0) {
            return Result(0.0, false, 0.0, 0, 0.0, 0,
                shift[0], shift[1], 0.0, 0.0)
        }
        val s = tripleScore(f06, f03.toDouble(), f09)
        return Result(s, s >= threshold, f06, f03, f09, nCand,
            shift[0], shift[1], bestCx, bestCy)
    }

    // ── registration ─────────────────────────────────────────────────────────

    /** Python's int(round(x)): ties to even (Math.rint semantics). */
    private fun roundHalfEven(x: Double): Int = Math.rint(x).toInt()

    /** warpAffine M=[[1,0,ix],[0,1,iy]] INTER_NEAREST + BORDER_REPLICATE,
     *  with the MEASURED OpenCV sampling direction (novelty_scorer.py
     *  L103-121 + 2026-09-20 empirical pin: phaseCorrelate(frame, ref) with
     *  the ref dot +2 px right returns t=+2; warp with ix=round(-t)=-2 then
     *  aligns with 0.0 mean abs diff — i.e. dst(x,y) = src(x-ix, y-iy)).
     *  Coords clamped (replicate border). */
    private fun warpReplicate(src: ByteArray, w: Int, h: Int, ix: Int, iy: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val sy = (y - iy).coerceIn(0, h - 1)
            val rowOff = y * w
            val srcRow = sy * w
            for (x in 0 until w) {
                out[rowOff + x] = src[srcRow + (x - ix).coerceIn(0, w - 1)]
            }
        }
        return out
    }

    /** dilate with a 3x3 square kernel, border = ignore-OOB (OpenCV's
     *  BORDER_CONSTANT with morphology default for dilate). */
    private fun dilate3(src: ByteArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - 1)
            val y1 = min(h - 1, y + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                var m = 0
                for (yy in y0..y1) {
                    val off = yy * w
                    for (xx in x0..x1) {
                        val v = src[off + xx].toInt() and 0xFF
                        if (v > m) m = v
                    }
                }
                out[y * w + x] = m.toDouble()
            }
        }
        return out
    }

    // ── phase correlation (OpenCV 4.x phasecorr.cpp algorithm) ───────────────

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** getOptimalDFTSize: smallest 2^a*3^b*5^c >= n (OpenCV dxt.cpp table
     *  semantics for our size range). The padded size MUST match OpenCV's —
     *  see deviation 5 in the class doc. */
    private fun optimalDftSize(n: Int): Int {
        var m = max(1, n)
        while (true) {
            var v = m
            while (v % 2 == 0) v /= 2
            while (v % 3 == 0) v /= 3
            while (v % 5 == 0) v /= 5
            if (v == 1) return m
            m++
        }
    }

    /** sqrt-Hann window, separable: w(k) = 0.5*(1-cos(2*pi*k/(N-1))), then
     *  sqrt of the outer product (createHanningWindow, phasecorr.cpp). */
    private fun hann(n: Int): DoubleArray {
        val c = DoubleArray(n)
        if (n <= 1) {
            c[0] = 0.0
            return c
        }
        val coeff = 2.0 * Math.PI / (n - 1).toDouble()
        for (k in 0 until n) c[k] = 0.5 * (1.0 - cos(coeff * k))
        return c
    }

    /** Returns doubleArrayOf(dx, dy) with OpenCV's sign convention:
     *  shift = paddedCenter - weightedCentroid(fftShifted response).
     *  `internal` (not private) so the T5 microbench can time registration
     *  separately from feature extraction. */
    internal fun phaseShift(frame: ByteArray, ref: ByteArray, w: Int, h: Int): DoubleArray {
        val m = optimalDftSize(h)
        val n = optimalDftSize(w)
        val wc = hann(w)
        val wr = hann(h)

        val re1 = DoubleArray(m * n)
        val im1 = DoubleArray(m * n)
        val re2 = DoubleArray(m * n)
        val im2 = DoubleArray(m * n)
        for (y in 0 until h) {
            val wy = wr[y]
            val off = y * w
            val poff = y * n
            for (x in 0 until w) {
                val win = sqrt(wy * wc[x])
                re1[poff + x] = (frame[off + x].toInt() and 0xFF).toDouble() * win
                re2[poff + x] = (ref[off + x].toInt() and 0xFF).toDouble() * win
            }
        }
        fft2d(re1, im1, m, n, false)
        fft2d(re2, im2, m, n, false)

        // Cross-power P = F1 * conj(F2), normalized by mag/(mag^2 + FLT_EPSILON)
        val eps = 1.1920929e-7
        for (i in re1.indices) {
            val ar = re1[i]
            val ai = im1[i]
            val br = re2[i]
            val bi = im2[i]
            val pr = ar * br + ai * bi   // F1 * conj(F2)
            val pi = ai * br - ar * bi
            val mag = sqrt(pr * pr + pi * pi)
            val denom = mag * mag + eps
            re1[i] = pr * mag / denom
            im1[i] = pi * mag / denom
        }
        fft2d(re1, im1, m, n, true)   // inverse: conjugate twiddles + 1/(M*N)

        // fftShift: dst(x,y) = src((x+ceil(n/2))%n, (y+ceil(m/2))%m) — OpenCV's
        // quadrant swap for BOTH parities; the DC lands at (floor(m/2),
        // floor(n/2)) while OpenCV's shift center is (n/2.0, m/2.0), which is
        // the +0.5 px odd-axis bias we mirror deliberately.
        var peak = Double.NEGATIVE_INFINITY
        var px = 0
        var py = 0
        val shiftN = (n + 1) / 2
        val shiftM = (m + 1) / 2
        val shifted = DoubleArray(m * n)
        for (y in 0 until m) {
            val sy = (y + shiftM) % m
            for (x in 0 until n) {
                val v = re1[sy * n + (x + shiftN) % n]
                shifted[y * n + x] = v
                if (v > peak) {
                    peak = v
                    px = x
                    py = y
                }
            }
        }

        // weightedCentroid 5x5, clamped, raw signed weights (phasecorr.cpp)
        var sumX = 0.0
        var sumY = 0.0
        var sumW = 0.0
        val cy0 = max(0, py - 2)
        val cy1 = min(m - 1, py + 2)
        val cx0 = max(0, px - 2)
        val cx1 = min(n - 1, px + 2)
        for (yy in cy0..cy1) {
            for (xx in cx0..cx1) {
                val wgt = shifted[yy * n + xx]
                sumX += xx * wgt
                sumY += yy * wgt
                sumW += wgt
            }
        }
        sumW += 2.220446049250313e-16   // DBL_EPSILON
        val tx = sumX / sumW
        val ty = sumY / sumW
        return doubleArrayOf(n / 2.0 - tx, m / 2.0 - ty)
    }

    /** Iterative radix-2 complex FFT, in place. invert=true applies the
     *  conjugate twiddles and the 1/N scale (so fft2d(...,true) == idft). */
    private fun fft1d(re: DoubleArray, im: DoubleArray, off: Int, len: Int, stride: Int, invert: Boolean) {
        // bit-reversal permutation
        var j = 0
        for (i in 1 until len) {
            var bit = len shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val ii = off + i * stride
                val jj = off + j * stride
                var t = re[ii]; re[ii] = re[jj]; re[jj] = t
                t = im[ii]; im[ii] = im[jj]; im[jj] = t
            }
        }
        var size = 2
        while (size <= len) {
            val half = size shr 1
            val ang = 2.0 * Math.PI / size * (if (invert) 1.0 else -1.0)
            val wRe = cos(ang)
            val wIm = sin(ang)
            var start = 0
            while (start < len) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val a = off + (start + k) * stride
                    val bIdx = off + (start + k + half) * stride
                    val tRe = re[bIdx] * curRe - im[bIdx] * curIm
                    val tIm = re[bIdx] * curIm + im[bIdx] * curRe
                    re[bIdx] = re[a] - tRe
                    im[bIdx] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                start += size
            }
            size = size shl 1
        }
        if (invert) {
            for (i in 0 until len) {
                val idx = off + i * stride
                re[idx] /= len
                im[idx] /= len
            }
        }
    }

    private fun fft2d(re: DoubleArray, im: DoubleArray, rows: Int, cols: Int, invert: Boolean) {
        for (y in 0 until rows) dft1d(re, im, y * cols, cols, 1, invert)
        for (x in 0 until cols) dft1d(re, im, x, rows, cols, invert)
    }

    /** Length-agnostic 1D DFT: radix-2 when len is a power of two, Bluestein
     *  otherwise (optimal sizes carry factors 3/5 — 600, 135, 640, 480). */
    private fun dft1d(re: DoubleArray, im: DoubleArray, off: Int, len: Int, stride: Int, invert: Boolean) {
        if (len and (len - 1) == 0) {
            fft1d(re, im, off, len, stride, invert)
        } else {
            bluestein(re, im, off, len, stride, invert)
        }
    }

    /** Bluestein chirp-z: any-n DFT via pow2 convolution. Inverse computed as
     *  conj(dft(conj(x)))/n so one code path serves both directions. */
    private fun bluestein(re: DoubleArray, im: DoubleArray, off: Int, len: Int, stride: Int, invert: Boolean) {
        val xr = DoubleArray(len)
        val xi = DoubleArray(len)
        for (k in 0 until len) {
            xr[k] = re[off + k * stride]
            xi[k] = im[off + k * stride]
        }
        if (invert) {
            for (k in 0 until len) xi[k] = -xi[k]
        }
        val m = nextPow2(2 * len - 1)
        val ar = DoubleArray(m)
        val ai = DoubleArray(m)
        val br = DoubleArray(m)
        val bi = DoubleArray(m)
        for (k in 0 until len) {
            val ang = Math.PI * k.toDouble() * k.toDouble() / len
            val cr = cos(ang)
            val ci = -sin(ang)          // chirp = exp(-i*pi*k^2/n), forward sign
            ar[k] = xr[k] * cr - xi[k] * ci
            ai[k] = xr[k] * ci + xi[k] * cr
            br[k] = cr                  // convolve against conj(chirp)
            bi[k] = -ci
            if (k > 0) {
                br[m - k] = cr
                bi[m - k] = -ci
            }
        }
        fft1d(ar, ai, 0, m, 1, false)
        fft1d(br, bi, 0, m, 1, false)
        for (k in 0 until m) {
            val tr = ar[k] * br[k] - ai[k] * bi[k]
            ai[k] = ar[k] * bi[k] + ai[k] * br[k]
            ar[k] = tr
        }
        fft1d(ar, ai, 0, m, 1, true)
        for (k in 0 until len) {
            val ang = Math.PI * k.toDouble() * k.toDouble() / len
            val cr = cos(ang)
            val ci = -sin(ang)
            var outR = ar[k] * cr - ai[k] * ci
            var outI = ar[k] * ci + ai[k] * cr
            if (invert) {                 // idft = conj(dft(conj)) / n
                outR /= len
                outI = -outI / len
            }
            re[off + k * stride] = outR
            im[off + k * stride] = outI
        }
    }

    // ── components, contour area, ring ───────────────────────────────────────

    private class Blob(
        val pixels: IntArray,
        val area: Double,
        val cx: Double,
        val cy: Double,
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    )

    /** 8-connected components of `union` (findContours RETR_EXTERNAL treats
     *  foreground as 8-connected), each with Moore-traced shoelace area. */
    private fun collectBlobs(union: BooleanArray, w: Int, h: Int): List<Blob> {
        val labels = IntArray(w * h) { -1 }
        val blobs = ArrayList<Blob>()
        val stack = IntArray(w * h)
        for (start in union.indices) {
            if (!union[start] || labels[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = start
            val px = ArrayList<Int>()
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            while (sp > 0) {
                val p = stack[--sp]
                px.add(p)
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val y0 = max(0, y - 1)
                val y1 = min(h - 1, y + 1)
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                for (yy in y0..y1) {
                    val off = yy * w
                    for (xx in x0..x1) {
                        val q = off + xx
                        if (union[q] && labels[q] < 0) {
                            labels[q] = start
                            stack[sp++] = q
                        }
                    }
                }
            }
            val area = contourArea(px, w, h)
            var sx = 0.0
            var sy = 0.0
            for (p in px) {
                sx += (p % w).toDouble()
                sy += (p / w).toDouble()
            }
            blobs.add(Blob(px.toIntArray(), area,
                sx / px.size, sy / px.size, minX, minY, maxX, maxY))
        }
        return blobs
    }

    /** Shoelace area of the Moore-traced outer boundary through pixel centers —
     *  the same lattice polygon cv2.contourArea spans (CHAIN_APPROX_SIMPLE
     *  drops only collinear points, which do not change the area). */
    private fun contourArea(pixels: List<Int>, w: Int, h: Int): Double {
        if (pixels.size < 3) return 0.0
        val set = HashSet<Int>(pixels)
        // start: topmost, then leftmost (Suzuki's outer-border start)
        var s = pixels[0]
        for (p in pixels) {
            if (p / w < s / w || (p / w == s / w && p % w < s % w)) s = p
        }
        // Directions 0..7 = E, SE, S, SW, W, NW, N, NE (clockwise, y-down).
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val boundary = ArrayList<Int>()
        boundary.add(s)
        var cur = s
        var backtrack = 4                 // raster entry: W of start is background
        var second = -1
        var guard = 0
        val maxSteps = 8 * pixels.size + 16
        while (guard++ < maxSteps) {
            val cx = cur % w
            val cy = cur / w
            var next = -1
            var moveDir = -1
            for (k in 1..8) {
                val d = (backtrack + k) % 8   // clockwise from just past backtrack
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val q = ny * w + nx
                if (set.contains(q)) {
                    next = q
                    moveDir = d
                    break
                }
            }
            if (next < 0) break                 // isolated pixel
            if (second < 0) {
                second = next
            } else if (cur == s && next == second) {
                break                           // Jacob's criterion: first edge retraced
            }
            boundary.add(next)
            cur = next
            backtrack = (moveDir + 4) % 8
        }
        if (boundary.size < 3) return 0.0
        var acc = 0L
        for (i in boundary.indices) {
            val a = boundary[i]
            val bIdx = boundary[(i + 1) % boundary.size]
            acc += (a % w).toLong() * (bIdx / w).toLong() -
                    (bIdx % w).toLong() * (a / w).toLong()
        }
        return abs(acc).toDouble() / 2.0
    }

    /** #6 ring: gray mean over Chebyshev distance (2, 22] from the component
     *  (square 5x5 / 45x45 dilations of the blob mask — Python L202-206).
     *  Iterative 3x3 dilations of the component set; frame-clamped. */
    private fun ringMean(blob: Blob, gray: ByteArray, w: Int, h: Int): Double {
        val x0 = max(0, blob.minX - 22)
        val x1 = min(w - 1, blob.maxX + 22)
        val y0 = max(0, blob.minY - 22)
        val y1 = min(h - 1, blob.maxY + 22)
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        var cur = BooleanArray(rw * rh)
        for (p in blob.pixels) {
            cur[(p / w - y0) * rw + (p % w - x0)] = true
        }
        var inner = cur        // will become dilate-by-2 (5x5 kernel)
        for (it in 0 until 2) inner = dilateBool(inner, rw, rh)
        cur = inner
        var outer = cur        // continue to dilate-by-22 total (45x45 kernel)
        for (it in 2 until 22) outer = dilateBool(outer, rw, rh)
        var sum = 0.0
        var cnt = 0
        for (yy in 0 until rh) {
            val fOff = (yy + y0) * w
            val rOff = yy * rw
            for (xx in 0 until rw) {
                if (outer[rOff + xx] && !inner[rOff + xx]) {
                    sum += (gray[fOff + xx + x0].toInt() and 0xFF).toDouble()
                    cnt++
                }
            }
        }
        return if (cnt > 0) sum / cnt else 0.0
    }

    private fun dilateBool(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - 1)
            val y1 = min(h - 1, y + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                var v = false
                for (yy in y0..y1) {
                    val off = yy * w
                    for (xx in x0..x1) {
                        if (src[off + xx]) {
                            v = true
                            break
                        }
                    }
                    if (v) break
                }
                out[y * w + x] = v
            }
        }
        return out
    }
}
