package com.sirtverse.detectioncore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import kotlin.math.max

/**
 * Real laser detector — Milestone 3 core, extracted to :detection-core.
 *
 * Pipeline (YUV plane-math, no OpenCV — see DECISIONS.md D-007):
 *   1. Stride-sample Y plane → brightness delta vs rolling per-cell background.
 *   2. Peak-score check: delta > [SCORE_THRESHOLD].
 *   3. Neighbor compactness gate: ≥1 adjacent cell above half-threshold.
 *   4. Green color gate: Cb < [CB_MAX] AND Cr < [CR_MAX].
 *   5. Pulse state machine: rise-and-fall with a [PulseStateMachine.MIN_ABSENT_FRAMES] gap;
 *      refractory = cooldownMs. Extracted to [PulseStateMachine] (CC-SIRT-CAPTURE-CORE-PARITY-001)
 *      so the exact `isShot` spec is camera-free and unit-testable off-rig.
 *   6. JSONL event log (hit + near-miss) when labMode is enabled.
 *
 * Emits a [Detection] per processed frame via [onDetection] (dispatched to main thread).
 * [Detection.isShot] is true only on the frame that fires the shot event.
 *
 * [config] replaces direct SettingsStore access so both the full app and DetectLab can
 * supply their own implementations.
 */
class CameraLaserDetector(
    private val cameraController: CameraController,
    private val context: Context,
    private val config: DetectionConfig,
) : LaserDetector {

    override var onDetection: ((Detection) -> Unit)? = null

    companion object {
        private const val TAG = "CameraLaserDet"

        const val STRIDE = 4

        // B-4 iteration 6 (2026-07-20 night, CC Fable): 24f→16f. Under pinned exposure
        // the noise band collapsed to 5.3-11.1; 16f sits ~45% above max observed noise
        // and catches dim pulses (misses fell in 12-23 gap at 24f).
        @JvmField var SCORE_THRESHOLD = 16f

        // B-4 iteration 2 (2026-07-19 night): "not-red, not-strongly-blue" semantics.
        // Cb < 150 rejects strong blue; Cr < 127 rejects red.
        const val CB_MAX = 150
        const val CR_MAX = 127

        const val EMA_ALPHA = 0.05f
        const val DIAG_EVERY_N_FRAMES = 30L

        // Feature 22 — Locked Exposure Context (Detection-Arsenal.md rank #1).
        // Values chosen 2026-07-20 (D-007 iteration): 16ms / ISO 800 — noise band collapsed
        // to 5.3–11.1 under these settings, laser pulses score 47–238. Toggle via
        // DetectionConfig.lockedExposureEnabled; stop() always restores auto-exposure.
        // Named constants so the diagnostic overlay (CC-SIRT-F22-VISIBILITY-001 §B3) can
        // display them and Mike can tune them via a future device-verify pass.
        const val LOCKED_SHUTTER_NS = 16_000_000L  // 16 ms (1/62 s)
        const val LOCKED_ISO = 800
    }

    private var lastScore = 0f
    private var frameCount = 0L

    @Volatile private var active = false

    private var background: FloatArray? = null
    private var gridW = 0
    private var gridH = 0

    private var pulse = PulseStateMachine()
    private var sessionId = ""
    private var logWriter: PrintWriter? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start() {
        sessionId = "M3-${System.currentTimeMillis()}"
        pulse = PulseStateMachine()
        background = null
        frameCount = 0L
        lastScore = 0f
        active = true

        if (config.labModeEnabled) openLog()

        // Feature 22 — Locked Exposure Context (Detection-Arsenal.md rank #1, D-007).
        // Pinning removes the AE feedback loop that causes oscillation phantoms in ambient light.
        // Only applied when lockedExposureEnabled; stop() always restores auto-exposure.
        if (config.lockedExposureEnabled) {
            cameraController.setExposure(shutterNs = LOCKED_SHUTTER_NS, iso = LOCKED_ISO)
        }
        cameraController.frameListener = { image -> analyzeFrame(image) }

        Log.i(TAG, "start session=$sessionId labMode=${config.labModeEnabled} " +
                "threshold=$SCORE_THRESHOLD cooldown=${config.cooldownMs}ms " +
                "lockedExposure=${config.lockedExposureEnabled} " +
                "(${LOCKED_SHUTTER_NS / 1_000_000}ms/ISO$LOCKED_ISO)")
    }

    override fun stop() {
        active = false
        cameraController.frameListener = null
        cameraController.setAutoExposure()
        closeLog()
        Log.i(TAG, "stop session=$sessionId frames=$frameCount")
    }

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

        val bg = background?.takeIf { gridW == gW && gridH == gH }
            ?: FloatArray(gW * gH) { 128f }.also {
                background = it; gridW = gW; gridH = gH
            }

        val yPlane       = image.planes[0]
        val yBuffer      = yPlane.buffer
        val yRowStride   = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit       = yBuffer.limit()

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
                    "grid=${gW}x${gH} absent=${pulse.greenAbsentCount}")
        }

        // Peak cell coordinates and 3×3 grid position
        val peakGx = peakIdx % gW
        val peakGy = peakIdx / gW
        // B0 TARGET-EXPOSURE-001 (2026-08-12): analysis buffer is transposed relative to the
        // display — a plain swap sends markers the wrong direction. Measured mapping:
        //   markerX = 1 − normY_old  (swap + horizontal mirror: A-LR rises → dot moves left)
        //   markerY = normX_old      (swap only)
        // Verified: LR=51 normY_old=0.546 → markerX=0.454 vs measured 0.458 ✅
        //           LR=57 normY_old=0.613 → markerX=0.387 vs measured 0.392 ✅
        // RULE-ARSENAL-001: coordinate fix only — no threshold or gate change.
        val normX  = 1.0 - (peakGy + 0.5) / gH   // markerX = 1 − normY
        val normY  = (peakGx + 0.5) / gW           // markerY = normX
        val col    = (normX * 3).toInt().coerceIn(0, 2)
        val row    = (normY * 3).toInt().coerceIn(0, 2)
        val gridCell = row * 3 + col

        // ── Step 2: candidate gates (capture results for Detection stream) ─────
        val aboveThreshold = peakScore >= SCORE_THRESHOLD
        val passNeighbor   = aboveThreshold && checkNeighbor(scores, peakIdx, gW, gH)
        val passColor      = passNeighbor && checkGreen(image, peakIdx, gW)
        val isCandidate    = passColor

        // ── Step 3: pulse state machine ───────────────────────────────────────
        // Camera-free spec lives in PulseStateMachine (CC-SIRT-CAPTURE-CORE-PARITY-001);
        // this call site owns only the camera-dependent side effects it reports back.
        val now = SystemClock.elapsedRealtime()
        val result = pulse.onFrame(isCandidate, now, config.cooldownMs)
        val isShot = result.isShot

        if (result.resumeBackground) {
            updateBackground(bg, gW, yBuffer, yRowStride, yPixelStride, yLimit)
        }
        if (isShot) {
            logHit(peakScore, peakGx, peakGy, now, image.imageInfo.timestamp)
            Log.i(TAG, "SHOT frame=$frameCount score=${"%.1f".format(peakScore)} " +
                    "gap=${result.gapMs}ms pulseFrames=${pulse.pulseFrames}")
        }
        if (result.nearMiss) {
            logNearMiss(peakScore)
        }

        // ── Emit Detection every frame ─────────────────────────────────────────
        val detection = Detection(
            peakCellX    = peakGx,
            peakCellY    = peakGy,
            normX        = normX,
            normY        = normY,
            peakScore    = peakScore,
            passNeighbor = passNeighbor,
            passColor    = passColor,
            isShot       = isShot,
            gridCell     = gridCell,
            timestampNs  = image.imageInfo.timestamp,
        )
        mainHandler.post { onDetection?.invoke(detection) }
    }

    private fun updateBackground(
        bg: FloatArray, gW: Int, yBuffer: java.nio.ByteBuffer,
        yRowStride: Int, yPixelStride: Int, yLimit: Int,
    ) {
        for (i in bg.indices) {
            val gy     = i / gW
            val gx     = i % gW
            val bufIdx = gy * STRIDE * yRowStride + gx * STRIDE * yPixelStride
            if (bufIdx >= yLimit) continue
            val yVal = (yBuffer.get(bufIdx).toInt() and 0xFF).toFloat()
            bg[i] += EMA_ALPHA * (yVal - bg[i])
        }
    }

    /**
     * Compactness gate: ≥1 4-connected neighbor above half-threshold.
     * Rejects isolated noise spikes (single-pixel glints).
     *
     * B-4 iteration 1: factor 0.5f→0.2f. At the new phone distance the dot covers
     * ~1 grid cell; no 4-neighbor reached half-threshold → 0/5 missed pulses.
     */
    private fun checkNeighbor(scores: FloatArray, peakIdx: Int, gW: Int, gH: Int): Boolean {
        val halfThresh = SCORE_THRESHOLD * 0.2f
        val gy = peakIdx / gW
        val gx = peakIdx % gW
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
     * Gate: Cb < [CB_MAX] and Cr < [CR_MAX].
     * UV planes are half-resolution; pixelStride may be 1 (I420) or 2 (NV12/NV21).
     */
    private fun checkGreen(image: ImageProxy, peakIdx: Int, gW: Int): Boolean {
        val peakGx = peakIdx % gW
        val peakGy = peakIdx / gW
        val px = peakGx * STRIDE
        val py = peakGy * STRIDE

        val uvPlane1      = image.planes[1]
        val uvPlane2      = image.planes[2]
        val uvRowStride   = uvPlane1.rowStride
        val uvPixelStride = uvPlane1.pixelStride
        val uvBufIdx      = (py / 2) * uvRowStride + (px / 2) * uvPixelStride

        if (uvBufIdx >= uvPlane1.buffer.limit() || uvBufIdx >= uvPlane2.buffer.limit()) return true

        val cb = uvPlane1.buffer.get(uvBufIdx).toInt() and 0xFF
        val cr = uvPlane2.buffer.get(uvBufIdx).toInt() and 0xFF
        val isGreen = cb < CB_MAX && cr < CR_MAX

        if (frameCount % DIAG_EVERY_N_FRAMES == 0L) {
            Log.d(TAG, "DIAG_UV cb=$cb cr=$cr isGreen=$isGreen (gate cb<$CB_MAX cr<$CR_MAX)")
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

    private fun logHit(score: Float, gx: Int, gy: Int, elapsedMs: Long, frameTsNs: Long) {
        val lw = logWriter ?: return
        lw.println(
            """{"type":"hit","session_id":"$sessionId",""" +
            """"elapsed_ms":$elapsedMs,"wall_ms":${System.currentTimeMillis()},""" +
            """"frame_ts_ns":$frameTsNs,""" +
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
