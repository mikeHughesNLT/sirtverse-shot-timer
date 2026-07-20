package com.sirtverse.shottimer.domain.detection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.sirtverse.shottimer.camera.CameraController
import com.sirtverse.shottimer.storage.SettingsStore
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import kotlin.math.max

/**
 * Real laser detector — Milestone 3.
 *
 * Implements the [LaserDetector] seam; the timer shell never changes.
 *
 * Pipeline (YUV plane-math, no OpenCV — see DECISIONS.md D-007):
 *   1. Stride-sample Y plane → brightness delta vs rolling per-cell background.
 *   2. Peak-score check: delta > [SCORE_THRESHOLD].
 *   3. Neighbor compactness gate: ≥1 adjacent cell above half-threshold → laser blob, not noise.
 *   4. Green color gate: Cb < [CB_MAX] AND Cr < [CR_MAX] (green laser → low blue/red chroma).
 *   5. Pulse state machine: rise-and-fall with [MIN_ABSENT_FRAMES] gap; refractory = cooldownMs.
 *   6. JSONL event log (hit + near-miss) to getExternalFilesDir("detections")/<session>.jsonl
 *      when lab mode is enabled (SettingsStore.labModeEnabled).
 *
 * Threshold rationale (seeded from patch_ml_report.md feature importances):
 *   - val_delta_blob (#3 importance, 0.114): Y brightness delta at blob → SCORE_THRESHOLD.
 *   - sat_delta_blob (#7, 0.046) + core_sat_mean (#11, 0.027): color purity → CB/CR gates.
 *   All thresholds are tunable on the rig (B-4 of CC-SIRT-TIMER-M3-DETECT-001).
 *
 * Thread safety: all state-machine fields are only accessed from the single-threaded
 * CameraX analysis executor. [onHit] is dispatched to the main thread.
 * [lastScore] and [frameCount] are @Volatile for safe reads from the UI thread.
 */
class CameraLaserDetector(
    private val cameraController: CameraController,
    private val context: Context,
    private val settings: SettingsStore,
) : LaserDetector {

    override var onHit: (() -> Unit)? = null

    companion object {
        private const val TAG = "CameraLaserDet"

        // ── Stride sampling ──────────────────────────────────────────────────
        // Sample every STRIDE pixels in both axes → working grid.
        // At 640×480 with STRIDE=4 → 160×120 = 19,200 cells.
        // Tune up (larger stride) if sustained fps drops below 15.
        const val STRIDE = 4

        // ── Detection thresholds (tune via B-4 rig iteration) ────────────────
        // Y brightness delta above rolling background to score a cell as a candidate.
        // Seeded from patch_ml_report val_delta_blob median separation.
        // B-4 iteration 1 (2026-07-19 night, CC Fable): 25f→18f. At the repositioned
        // phone distance the commanded-pulse peak measured 28.3 vs baseline ~8-11
        // (logcat DIAG); 25f left no margin for weaker pulses. Phantom cost of the
        // looser gate is measured by the overnight referee laser-off windows.
        @JvmField var SCORE_THRESHOLD = 18f

        // Cb/Cr gates for green color confirmation (YCbCr, neutral = 128).
        // Pure 532 nm green → Cb ≈ 44, Cr ≈ 21. Set conservatively to tolerate
        // compression artifacts and mixed surfaces.
        const val CB_MAX = 110
        const val CR_MAX = 110

        // ── Pulse state machine ───────────────────────────────────────────────
        // Laser must be absent for this many frames before a new shot arms.
        const val MIN_ABSENT_FRAMES = 2
        // Shots longer than this are static reflections, not SIRT pulses.
        const val MAX_PULSE_FRAMES = 25

        // ── Background EMA ────────────────────────────────────────────────────
        // Only update when no candidate is active (freeze while laser is present).
        const val EMA_ALPHA = 0.05f

        // ── Diag heartbeat ────────────────────────────────────────────────────
        const val DIAG_EVERY_N_FRAMES = 30L
    }

    // Volatile: read from UI thread for debug overlay.
    @Volatile var lastScore = 0f
    @Volatile var frameCount = 0L

    @Volatile private var active = false

    // Background grid (lazy-initialized on first frame — size depends on resolution).
    private var background: FloatArray? = null
    private var gridW = 0
    private var gridH = 0

    // Pulse state machine (accessed only from analysis executor thread).
    private var greenAbsentCount = MIN_ABSENT_FRAMES   // start "armed"
    private var pulseFrames = 0
    private var lastShotElapsedMs = -1L

    // Session identity.
    private var sessionId = ""

    // JSONL event log (lab mode only).
    private var logWriter: PrintWriter? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── LaserDetector ─────────────────────────────────────────────────────────

    override fun start() {
        sessionId = "M3-${System.currentTimeMillis()}"
        greenAbsentCount = MIN_ABSENT_FRAMES
        pulseFrames = 0
        lastShotElapsedMs = -1L
        background = null           // fresh background per session
        frameCount = 0L
        lastScore = 0f
        active = true

        if (settings.labModeEnabled) openLog()

        // Register frame sink — exposure policy applied per DECISIONS.md D-007.
        // v1 ships with auto-exposure (gate-dependent parachute in the brief).
        cameraController.frameListener = { image -> analyzeFrame(image) }

        Log.i(TAG, "start session=$sessionId labMode=${settings.labModeEnabled} " +
                "threshold=$SCORE_THRESHOLD cooldown=${settings.cooldownMs}ms")
    }

    override fun stop() {
        active = false
        cameraController.frameListener = null
        cameraController.setAutoExposure()
        closeLog()
        Log.i(TAG, "stop session=$sessionId frames=$frameCount")
    }

    // ── Frame processing (runs on CameraX analysis executor) ─────────────────

    private fun analyzeFrame(image: ImageProxy) {
        if (!active) { image.close(); return }
        try {
            frameCount++
            processFrame(image)
        } finally {
            image.close()
        }
    }

    private fun processFrame(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val gW = (w + STRIDE - 1) / STRIDE
        val gH = (h + STRIDE - 1) / STRIDE

        // Lazy-init background grid.
        val bg = background?.takeIf { gridW == gW && gridH == gH }
            ?: FloatArray(gW * gH) { 128f }.also {
                background = it; gridW = gW; gridH = gH
            }

        val yPlane      = image.planes[0]
        val yBuffer     = yPlane.buffer
        val yRowStride  = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit      = yBuffer.limit()

        // ── Step 1: stride-sample Y → delta scores ────────────────────────────
        val scores = FloatArray(gW * gH)
        var peakScore = 0f
        var peakIdx   = 0

        for (gy in 0 until gH) {
            for (gx in 0 until gW) {
                val px     = gx * STRIDE
                val py     = gy * STRIDE
                val bufIdx = py * yRowStride + px * yPixelStride
                if (bufIdx >= yLimit) continue
                val yVal  = (yBuffer.get(bufIdx).toInt() and 0xFF).toFloat()
                val bgIdx = gy * gW + gx
                val delta = max(0f, yVal - bg[bgIdx])
                scores[bgIdx] = delta
                if (delta > peakScore) { peakScore = delta; peakIdx = bgIdx }
            }
        }

        lastScore = peakScore

        if (frameCount % DIAG_EVERY_N_FRAMES == 0L) {
            Log.d(TAG, "DIAG frame=$frameCount peak=${"%.1f".format(peakScore)} " +
                    "grid=${gW}x${gH} active=$active absent=$greenAbsentCount")
        }

        // ── Step 2: candidate gates ───────────────────────────────────────────
        val isCandidate = peakScore >= SCORE_THRESHOLD
                && checkNeighbor(scores, peakIdx, gW, gH)
                && checkGreen(image, peakIdx, gW)

        // ── Step 3: pulse state machine ───────────────────────────────────────
        if (isCandidate) {
            pulseFrames++
            if (greenAbsentCount >= MIN_ABSENT_FRAMES) {
                // Rising edge — candidate for a new shot.
                val now   = SystemClock.elapsedRealtime()
                val gapMs = if (lastShotElapsedMs >= 0) now - lastShotElapsedMs else Long.MAX_VALUE
                if (gapMs >= settings.cooldownMs && pulseFrames <= MAX_PULSE_FRAMES) {
                    lastShotElapsedMs = now
                    logHit(peakScore, peakIdx, gW, now)
                    mainHandler.post { onHit?.invoke() }
                    Log.i(TAG, "SHOT frame=$frameCount score=${"%.1f".format(peakScore)} " +
                            "gap=${gapMs}ms pulseFrames=$pulseFrames")
                }
            }
            greenAbsentCount = 0

        } else {
            if (greenAbsentCount == 0 && pulseFrames in 1..MAX_PULSE_FRAMES) {
                // Falling edge — log near-miss for lab analysis.
                logNearMiss(peakScore)
            }
            pulseFrames = 0
            greenAbsentCount++

            // Update rolling background only when no candidate is active.
            for (i in bg.indices) {
                val gy     = i / gW
                val gx     = i % gW
                val bufIdx = gy * STRIDE * yRowStride + gx * STRIDE * yPixelStride
                if (bufIdx >= yLimit) continue
                val yVal = (yBuffer.get(bufIdx).toInt() and 0xFF).toFloat()
                bg[i] += EMA_ALPHA * (yVal - bg[i])
            }
        }
    }

    // ── Gate helpers ──────────────────────────────────────────────────────────

    /**
     * Compactness gate: at least one 4-connected neighbor of the peak cell must score
     * above half-threshold. Rejects isolated noise spikes (single-pixel glints).
     */
    private fun checkNeighbor(scores: FloatArray, peakIdx: Int, gW: Int, gH: Int): Boolean {
        // B-4 iteration 1 (2026-07-19 night, CC Fable): factor 0.5f→0.2f. Root cause
        // of 0/5 commanded-pulse misses: at the new phone distance the dot covers
        // ~1 grid cell (STRIDE=4 @ 640px on a ~2px dot), so no 4-neighbor reached
        // half-threshold — confirmed via logcat frame 16800 (peak 28.3 passed score
        // gate, checkGreen's DIAG_UV never printed → neighbor gate was the rejector).
        val halfThresh = SCORE_THRESHOLD * 0.2f
        val gy = peakIdx / gW
        val gx = peakIdx % gW
        // 4-connected neighbors
        val n = listOf(
            if (gx > 0)      gy * gW + (gx - 1) else -1,
            if (gx < gW - 1) gy * gW + (gx + 1) else -1,
            if (gy > 0)      (gy - 1) * gW + gx  else -1,
            if (gy < gH - 1) (gy + 1) * gW + gx  else -1,
        )
        return n.any { it >= 0 && scores[it] >= halfThresh }
    }

    /**
     * Green color gate using Cb/Cr planes (YUV_420_888).
     *
     * Green (532 nm laser): Cb ≈ 44, Cr ≈ 21 (vs neutral 128).
     * Gate: both Cb < [CB_MAX] and Cr < [CR_MAX].
     *
     * UV planes are half-resolution; pixelStride may be 1 (I420) or 2 (NV12/NV21).
     */
    private fun checkGreen(image: ImageProxy, peakIdx: Int, gW: Int): Boolean {
        val peakGx = peakIdx % gW
        val peakGy = peakIdx / gW
        val px = peakGx * STRIDE
        val py = peakGy * STRIDE

        val uvPlane1    = image.planes[1]   // Cb (U)
        val uvPlane2    = image.planes[2]   // Cr (V)
        val uvRowStride  = uvPlane1.rowStride
        val uvPixelStride = uvPlane1.pixelStride
        val uvBufIdx    = (py / 2) * uvRowStride + (px / 2) * uvPixelStride

        if (uvBufIdx >= uvPlane1.buffer.limit() || uvBufIdx >= uvPlane2.buffer.limit()) return true

        val cb = uvPlane1.buffer.get(uvBufIdx).toInt() and 0xFF
        val cr = uvPlane2.buffer.get(uvBufIdx).toInt() and 0xFF
        val isGreen = cb < CB_MAX && cr < CR_MAX

        if (frameCount % DIAG_EVERY_N_FRAMES == 0L) {
            Log.d(TAG, "DIAG_UV cb=$cb cr=$cr isGreen=$isGreen " +
                    "(gate cb<$CB_MAX cr<$CR_MAX)")
        }
        return isGreen
    }

    // ── JSONL event log ───────────────────────────────────────────────────────

    private fun openLog() {
        try {
            val dir = context.getExternalFilesDir("detections")
            dir?.mkdirs()
            val file = File(dir, "$sessionId.jsonl")
            logWriter = PrintWriter(FileWriter(file, false))
            Log.i(TAG, "log opened: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "log open failed: ${e.message}")
        }
    }

    private fun closeLog() {
        logWriter?.flush()
        logWriter?.close()
        logWriter = null
    }

    private fun logHit(score: Float, peakIdx: Int, gW: Int, elapsedMs: Long) {
        val lw = logWriter ?: return
        val gx = peakIdx % gW
        val gy = peakIdx / gW
        lw.println(
            """{"type":"hit","session_id":"$sessionId",""" +
            """"elapsed_ms":$elapsedMs,"wall_ms":${System.currentTimeMillis()},""" +
            """"peak_score":${"%.2f".format(score)},""" +
            """"peak_cell_x":$gx,"peak_cell_y":$gy,"frame":$frameCount}"""
        )
        lw.flush()
    }

    private fun logNearMiss(score: Float) {
        val lw = logWriter ?: return
        lw.println(
            """{"type":"near_miss","session_id":"$sessionId",""" +
            """"elapsed_ms":${SystemClock.elapsedRealtime()},""" +
            """"wall_ms":${System.currentTimeMillis()},""" +
            """"peak_score":${"%.2f".format(score)},"frame":$frameCount}"""
        )
        lw.flush()
    }
}
