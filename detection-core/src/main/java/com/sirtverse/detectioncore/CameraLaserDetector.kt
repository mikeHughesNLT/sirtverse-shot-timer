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
import kotlin.math.abs
import kotlin.math.max

/**
 * Real laser detector — Milestone 3 core, extracted to :detection-core.
 *
 * Pipeline (YUV plane-math, no OpenCV — see DECISIONS.md D-007):
 *   1. Stride-sample Y plane → brightness delta vs rolling per-cell background.
 *   2. Peak-score check: delta > [SCORE_THRESHOLD].
 *   3. Neighbor compactness gate: ≥1 adjacent cell above [NEIGHBOR_FACTOR] × threshold.
 *   4. Green color gate: Cb < [CB_MAX] AND Cr < [CR_MAX].
 *   5. Pulse state machine: rise-and-fall with a [PulseStateMachine.MIN_ABSENT_FRAMES] gap;
 *      refractory = cooldownMs. Extracted to [PulseStateMachine] (CC-SIRT-CAPTURE-CORE-PARITY-001)
 *      so the exact `isShot` spec is camera-free and unit-testable off-rig.
 *   6. JSONL event log: hit + near-miss when labMode is enabled; every-frame peak record
 *      when benchMode is enabled (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002).
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
        // P0 CAMPAIGN-002: applied from config at start() — runtime-tunable as D3.
        @JvmField var SCORE_THRESHOLD = 16f

        // B-4 iteration 2 (2026-07-19 night): "not-red, not-strongly-blue" semantics.
        // Cb < 150 rejects strong blue; Cr < 127 rejects red.
        // P0 CAMPAIGN-002: @JvmField var (was const) — runtime-tunable as D6.
        @JvmField var CB_MAX = 150
        @JvmField var CR_MAX = 127

        // P0 CAMPAIGN-002: @JvmField var (was const) — runtime-tunable as D4.
        @JvmField var EMA_ALPHA = 0.05f

        const val DIAG_EVERY_N_FRAMES = 30L

        // CHROMA-SCORE-001 (2026-08-13, Mike ruling in DECISIONS [TARGET-EXPOSURE-001]):
        // on the white paper card a real dot moves luma only 5-15 Y-units (bar: 16) but
        // shifts CHROMA hard — and until now chroma was read only as a pass/fail gate at
        // the luma peak, never scored, so an on-paper dot could never even become the peak.
        // Per-cell score is now yDelta + CHROMA_WEIGHT * (|dCb| + |dCr|) vs per-cell EMA
        // chroma backgrounds. Frame-difference stays king; chroma tells laser from fly.
        // KILL-SWITCH: 0f restores the pre-change detector bit-for-bit. This ADDS a signal
        // (RULE-ARSENAL-001: tune, don't amputate); checkGreen is untouched, so red
        // take-up light still never fires a shot.
        // P0 CAMPAIGN-002: applied from config at start() — runtime-tunable as D2.
        @JvmField var CHROMA_WEIGHT = 1.0f

        // D5 neighbor factor — was hardcoded 0.2f in checkNeighbor (B-4 iteration 1).
        // P0 CAMPAIGN-002: @JvmField var — runtime-tunable as D5.
        @JvmField var NEIGHBOR_FACTOR = 0.2f

        // Feature 22 — Locked Exposure Context (Detection-Arsenal.md rank #1).
        // Values chosen 2026-07-20 (D-007 iteration): 16ms / ISO 800 — noise band collapsed
        // to 5.3–11.1 under these settings, laser pulses score 47–238. Toggle via
        // DetectionConfig.lockedExposureEnabled; stop() always restores auto-exposure.
        // Named constants so the diagnostic overlay (CC-SIRT-F22-VISIBILITY-001 §B3) can
        // display them and Mike can tune them via config (B2 TARGET-EXPOSURE-001).
        const val LOCKED_SHUTTER_NS = 16_000_000L  // 16 ms (1/62 s)
        const val LOCKED_ISO = 800
    }

    private var lastScore = 0f
    private var frameCount = 0L

    @Volatile private var active = false

    // Bench-mode flag cached at start() so it isn't read from config on every frame.
    private var benchModeActive = false

    private var background: FloatArray? = null
    private var backgroundCb: FloatArray? = null  // CHROMA-SCORE-001
    private var backgroundCr: FloatArray? = null  // CHROMA-SCORE-001
    private var gridW = 0
    private var gridH = 0

    private var pulse = PulseStateMachine()
    private var sessionId = ""
    private var logWriter: PrintWriter? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start() {
        sessionId = "M3-${System.currentTimeMillis()}"
        background = null
        backgroundCb = null
        backgroundCr = null
        frameCount = 0L
        lastScore = 0f

        // P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 — apply all D2–D9 dial values from config at
        // every start() so adb prefs write + force-stop + relaunch changes any dial without
        // an APK rebuild. Defaults in all config implementations match the locked constants
        // above, so a fresh install with no prefs overrides is behaviourally identical to
        // pre-P0. (d) git diff shows plumbing only — no threshold/value changes here.
        CHROMA_WEIGHT   = config.chromaWeight
        SCORE_THRESHOLD = config.scoreThreshold
        EMA_ALPHA       = config.emaAlpha
        NEIGHBOR_FACTOR = config.neighborFactor
        CB_MAX          = config.cbMax
        CR_MAX          = config.crMax
        benchModeActive = config.benchModeEnabled
        // D8+D9 applied via PulseStateMachine constructor — camera-free spec is in PSM.
        pulse = PulseStateMachine(config.minAbsentFrames, config.maxPulseFrames)

        active = true

        // Open log if labMode or benchMode — both modes write to the same JSONL file;
        // bench adds a "frame" record per frame on top of the existing hit/near_miss records.
        if (config.labModeEnabled || benchModeActive) openLog()

        // Feature 22 — Locked Exposure Context (Detection-Arsenal.md rank #1, D-007).
        // Pinning removes the AE feedback loop that causes oscillation phantoms in ambient light.
        // ShutterNs and ISO come from config (SettingsStore) so the B2 sweep can change them
        // per rung via ADB prefs write without rebuilding the APK.
        // stop() always restores auto-exposure regardless of this flag.
        val shutterNs = config.lockedShutterNs
        val iso       = config.lockedIso
        if (config.lockedExposureEnabled) {
            cameraController.setExposure(shutterNs = shutterNs, iso = iso)
        }
        cameraController.frameListener = { image -> analyzeFrame(image) }

        Log.i(TAG, "start session=$sessionId " +
                "labMode=${config.labModeEnabled} benchMode=$benchModeActive " +
                "score=$SCORE_THRESHOLD chroma=$CHROMA_WEIGHT ema=$EMA_ALPHA " +
                "neighbor=$NEIGHBOR_FACTOR cbMax=$CB_MAX crMax=$CR_MAX " +
                "cooldown=${config.cooldownMs}ms " +
                "absent=${config.minAbsentFrames} maxPulse=${config.maxPulseFrames} " +
                "lockedExposure=${config.lockedExposureEnabled} " +
                "(${shutterNs / 1_000_000}ms/ISO$iso)")
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
        // CHROMA-SCORE-001: per-cell chroma backgrounds. Init 128 (UV neutral); the EMA
        // converges within ~60 frames (~2 s), same startup transient class as the Y bg.
        val bgCb = backgroundCb?.takeIf { it.size == gW * gH }
            ?: FloatArray(gW * gH) { 128f }.also { backgroundCb = it }
        val bgCr = backgroundCr?.takeIf { it.size == gW * gH }
            ?: FloatArray(gW * gH) { 128f }.also { backgroundCr = it }

        val yPlane       = image.planes[0]
        val yBuffer      = yPlane.buffer
        val yRowStride   = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit       = yBuffer.limit()

        // UV planes (half resolution) — sampled per cell for the chroma-delta term.
        val uvPlane1      = image.planes[1]
        val uvPlane2      = image.planes[2]
        val uvRowStride   = uvPlane1.rowStride
        val uvPixelStride = uvPlane1.pixelStride
        val cbBuffer      = uvPlane1.buffer
        val crBuffer      = uvPlane2.buffer
        val cbLimit       = cbBuffer.limit()
        val crLimit       = crBuffer.limit()

        // ── Step 1: stride-sample Y (+ chroma delta) → combined scores ─────────
        // CHROMA-SCORE-001: on white paper a dot barely moves Y but moves Cb/Cr hard;
        // scoring luma-only made an on-paper dot unable to even become the peak.
        //
        // Bench mode additionally captures the absolute Cb/Cr at the peak cell for
        // per-frame JSONL logging (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 §(b)).
        val scores = FloatArray(gW * gH)
        var peakScore  = 0f
        var peakIdx    = 0
        var peakYDelta = 0f
        var peakChroma = 0f
        var peakCb     = 128f   // absolute Cb at peak cell — bench-mode JSONL
        var peakCr     = 128f   // absolute Cr at peak cell — bench-mode JSONL

        val readUv = CHROMA_WEIGHT > 0f || benchModeActive

        for (gy in 0 until gH) {
            for (gx in 0 until gW) {
                val px     = gx * STRIDE
                val py     = gy * STRIDE
                val bufIdx = py * yRowStride + px * yPixelStride
                if (bufIdx >= yLimit) continue
                val yVal  = (yBuffer.get(bufIdx).toInt() and 0xFF).toFloat()
                val bgIdx = gy * gW + gx
                val delta = max(0f, yVal - bg[bgIdx])

                var cbVal  = 128f
                var crVal  = 128f
                var chroma = 0f
                val uvIdx  = (py / 2) * uvRowStride + (px / 2) * uvPixelStride
                if (readUv && uvIdx < cbLimit && uvIdx < crLimit) {
                    cbVal = (cbBuffer.get(uvIdx).toInt() and 0xFF).toFloat()
                    crVal = (crBuffer.get(uvIdx).toInt() and 0xFF).toFloat()
                    if (CHROMA_WEIGHT > 0f) {
                        chroma = abs(cbVal - bgCb[bgIdx]) + abs(crVal - bgCr[bgIdx])
                    }
                }

                val combined = delta + CHROMA_WEIGHT * chroma
                scores[bgIdx] = combined
                if (combined > peakScore) {
                    peakScore = combined; peakIdx = bgIdx
                    peakYDelta = delta; peakChroma = chroma
                    peakCb = cbVal; peakCr = crVal
                }
            }
        }

        lastScore = peakScore

        if (frameCount % DIAG_EVERY_N_FRAMES == 0L) {
            Log.d(TAG, "DIAG frame=$frameCount peak=${"%.1f".format(peakScore)} " +
                    "y=${"%.1f".format(peakYDelta)} chroma=${"%.1f".format(peakChroma)} " +
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
            updateBackground(
                bg, bgCb, bgCr, gW,
                yBuffer, yRowStride, yPixelStride, yLimit,
                cbBuffer, crBuffer, uvRowStride, uvPixelStride, cbLimit, crLimit,
            )
        }
        if (isShot) {
            logHit(peakScore, peakYDelta, peakChroma, peakGx, peakGy, now, image.imageInfo.timestamp)
            Log.i(TAG, "SHOT frame=$frameCount score=${"%.1f".format(peakScore)} " +
                    "y=${"%.1f".format(peakYDelta)} chroma=${"%.1f".format(peakChroma)} " +
                    "gap=${result.gapMs}ms pulseFrames=${pulse.pulseFrames}")
        }
        if (result.nearMiss) {
            logNearMiss(peakScore)
        }

        // ── Step 4: bench-mode per-frame JSONL (P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 §(b)) ──
        if (benchModeActive) {
            logFrame(
                score          = peakScore,
                yDelta         = peakYDelta,
                chromaDelta    = peakChroma,
                cb             = peakCb,
                cr             = peakCr,
                gx             = peakGx,
                gy             = peakGy,
                aboveThreshold = aboveThreshold,
                passNeighbor   = passNeighbor,
                passColor      = passColor,
                isShot         = isShot,
                elapsedMs      = now,
                frameTsNs      = image.imageInfo.timestamp,
            )
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
            yDelta       = peakYDelta,
            chromaDelta  = peakChroma,
        )
        mainHandler.post { onDetection?.invoke(detection) }
    }

    private fun updateBackground(
        bg: FloatArray, bgCb: FloatArray, bgCr: FloatArray, gW: Int,
        yBuffer: java.nio.ByteBuffer, yRowStride: Int, yPixelStride: Int, yLimit: Int,
        cbBuffer: java.nio.ByteBuffer, crBuffer: java.nio.ByteBuffer,
        uvRowStride: Int, uvPixelStride: Int, cbLimit: Int, crLimit: Int,
    ) {
        for (i in bg.indices) {
            val gy     = i / gW
            val gx     = i % gW
            val px     = gx * STRIDE
            val py     = gy * STRIDE
            val bufIdx = py * yRowStride + px * yPixelStride
            if (bufIdx < yLimit) {
                val yVal = (yBuffer.get(bufIdx).toInt() and 0xFF).toFloat()
                bg[i] += EMA_ALPHA * (yVal - bg[i])
            }
            // CHROMA-SCORE-001: chroma backgrounds adapt on the same frames as the Y
            // background so slow lighting-color drift (e.g. Hue scenes) is absorbed.
            val uvIdx = (py / 2) * uvRowStride + (px / 2) * uvPixelStride
            if (uvIdx < cbLimit && uvIdx < crLimit) {
                val cb = (cbBuffer.get(uvIdx).toInt() and 0xFF).toFloat()
                val cr = (crBuffer.get(uvIdx).toInt() and 0xFF).toFloat()
                bgCb[i] += EMA_ALPHA * (cb - bgCb[i])
                bgCr[i] += EMA_ALPHA * (cr - bgCr[i])
            }
        }
    }

    /**
     * Compactness gate: ≥1 4-connected neighbor above [NEIGHBOR_FACTOR] × threshold.
     * Rejects isolated noise spikes (single-pixel glints).
     *
     * B-4 iteration 1: factor 0.5f→0.2f. At the new phone distance the dot covers
     * ~1 grid cell; no 4-neighbor reached half-threshold → 0/5 missed pulses.
     * P0 CAMPAIGN-002: factor promoted to [NEIGHBOR_FACTOR] companion var (D5).
     */
    private fun checkNeighbor(scores: FloatArray, peakIdx: Int, gW: Int, gH: Int): Boolean {
        val halfThresh = SCORE_THRESHOLD * NEIGHBOR_FACTOR
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

    private fun logHit(score: Float, yDelta: Float, chromaDelta: Float, gx: Int, gy: Int, elapsedMs: Long, frameTsNs: Long) {
        val lw = logWriter ?: return
        lw.println(
            """{"type":"hit","session_id":"$sessionId",""" +
            """"elapsed_ms":$elapsedMs,"wall_ms":${System.currentTimeMillis()},""" +
            """"frame_ts_ns":$frameTsNs,""" +
            """"peak_score":${"%.2f".format(score)},""" +
            """"y_delta":${"%.2f".format(yDelta)},"chroma_delta":${"%.2f".format(chromaDelta)},""" +
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

    /**
     * Bench-mode per-frame record — P0 CC-SIRT-CALIBRATION-CAMPAIGN-002 §(b).
     *
     * Written for every processed frame when [benchModeActive] is true. Contains the full
     * peak record needed for per-channel confidence analysis: the raw luma and chroma deltas,
     * the absolute Cb/Cr values at the peak cell, all three gate booleans, and the final
     * isShot verdict. Hit and near-miss records continue to be written independently.
     */
    private fun logFrame(
        score: Float, yDelta: Float, chromaDelta: Float,
        cb: Float, cr: Float,
        gx: Int, gy: Int,
        aboveThreshold: Boolean, passNeighbor: Boolean, passColor: Boolean, isShot: Boolean,
        elapsedMs: Long, frameTsNs: Long,
    ) {
        val lw = logWriter ?: return
        lw.println(
            """{"type":"frame","session_id":"$sessionId",""" +
            """"elapsed_ms":$elapsedMs,"wall_ms":${System.currentTimeMillis()},""" +
            """"frame_ts_ns":$frameTsNs,""" +
            """"peak_score":${"%.2f".format(score)},""" +
            """"y_delta":${"%.2f".format(yDelta)},"chroma_delta":${"%.2f".format(chromaDelta)},""" +
            """"cb":${"%.0f".format(cb)},"cr":${"%.0f".format(cr)},""" +
            """"peak_cell_x":$gx,"peak_cell_y":$gy,""" +
            """"above_threshold":$aboveThreshold,"pass_neighbor":$passNeighbor,""" +
            """"pass_color":$passColor,"is_shot":$isShot,"frame":$frameCount}"""
        )
        lw.flush()
    }
}
