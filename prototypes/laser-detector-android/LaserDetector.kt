package com.sirtverse.engine

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import kotlin.math.PI
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Data model — mirrors LaserDetection / FrameReport from the Python reference
// implementation at SIRTverse/src/ports/laser_port.py
// ─────────────────────────────────────────────────────────────────────────────

enum class LaserChannel { GREEN, RED }

/**
 * One laser-dot candidate that survived all detection gates on a single frame.
 *
 * confidence: composite of circularity (0.5) and scaled contrast ratio (0.5).
 * contrastRatio: blob_mean / (ring_mean + 1) — Feature 4 from laser_discriminator.py.
 * channel: distinguishes shot-eligible GREEN from cursor-only RED.
 */
data class LaserDetection(
    val channel:       LaserChannel,
    val centroidX:     Int,
    val centroidY:     Int,
    val areaPx:        Double,
    val confidence:    Double,
    val contrastRatio: Double,
    val timestampNs:   Long,
    val frameNumber:   Long,
)

/**
 * Complete snapshot of one processed frame — the FrameReport data bus.
 *
 * shotNumber / splitMs are non-null only when a new deduplicated shot is
 * registered this frame (green channel, absent ≥ MIN_ABSENT_FRAMES prior).
 */
data class FrameReport(
    val frameNumber: Long,
    val timestampNs: Long,
    val detections:  List<LaserDetection>,
    val shotNumber:  Int?,
    val splitMs:     Double?,
)

// ─────────────────────────────────────────────────────────────────────────────
// Detector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Native Android laser detector — direct port of the SIRTverse Python pipeline.
 *
 * Milestone coverage (Android):
 *   M1    — HSV thresholding + contour centroid (findContours + moments)
 *   M1.5  — MOG2 background subtraction (green channel only)
 *   M1.5b — Circularity + local-contrast hard-reject gates
 *   M2    — Red channel trace, shot deduplication, split timing
 *
 * All HSV ranges, hard-reject thresholds, and dedup params match
 * SIRTverse/src/config.yaml exactly.
 *
 * Thread safety: detect() is called from a single background thread
 * (Camera2 bgHandler). frameReports getter is synchronised for cross-thread reads.
 *
 * OpenCV must be initialised before first call:
 *   OpenCVLoader.initLocal()  // called in Activity.onCreate()
 */
class LaserDetector {

    companion object {
        private const val TAG = "LaserDetector"

        private const val MAX_REPORTS = 1_000

        // ── HSV ranges — verbatim from config.yaml laser_detection ────────────
        // OpenCV HSV scale: H [0,180]  S [0,255]  V [0,255]

        // Green SIRT laser 532 nm  [H 35-85, S 100-255, V 200-255]
        private val GREEN_LOW  = Scalar(35.0, 100.0, 200.0)
        private val GREEN_HIGH = Scalar(85.0, 255.0, 255.0)
        private const val GREEN_MIN_AREA = 10.0
        private const val GREEN_MAX_AREA = 5000.0

        // Red cursor 635-670 nm — two ranges for hue-wheel wraparound at 0/180
        // config.yaml: hsv_lower_1=[0,100,200]  hsv_upper_1=[10,255,255]
        //              hsv_lower_2=[160,100,200] hsv_upper_2=[180,255,255]
        private val RED_LOW_1  = Scalar(0.0,   100.0, 200.0)
        private val RED_HIGH_1 = Scalar(10.0,  255.0, 255.0)
        private val RED_LOW_2  = Scalar(160.0, 100.0, 200.0)
        private val RED_HIGH_2 = Scalar(180.0, 255.0, 255.0)
        private const val RED_MIN_AREA = 10.0
        private const val RED_MAX_AREA = 5000.0

        // ── Hard-reject gates — config.yaml discrimination.hard_reject ────────
        private const val MIN_CIRCULARITY    = 0.09  // 4π·area/perimeter²
        private const val MIN_CONTRAST_RATIO = 0.9   // blob_mean / (ring_mean + 1)

        // ── Shot deduplication — config.yaml hit_detection ────────────────────
        // min_absent_frames: 2  — green must vanish for ≥2 frames before a new
        //   shot counts. Prevents a held trigger from scoring 30 times per second.
        // spatial_dedup_radius_px: 8  — same-spot re-trigger guard.
        private const val MIN_ABSENT_FRAMES       = 2
        private const val SPATIAL_DEDUP_RADIUS_PX = 8.0

        // ── MOG2 — config.yaml laser_detection.background_subtractor ──────────
        // history: 120   (~2 s at 60 fps; was incorrectly 500)
        // var_threshold: 40  (was incorrectly 16.0)
        // detect_shadows: false  — binary 0/255 output, no 127 shadow pixels
        private const val MOG2_HISTORY       = 120
        private const val MOG2_VAR_THRESHOLD = 40.0

        // Heartbeat interval — confirms frames are flowing in logcat even when
        // no laser is visible (every 60 frames ≈ 1 s at 60 fps or 2 s at 30 fps)
        private const val HEARTBEAT_FRAMES = 60L

        // Diagnostic interval — DIAG log showing frame-wide HSV maxima + bright-pixel count.
        // Every 30th frame ≈ 1 s at 30 fps. Tells us if the laser registers in HSV at all.
        private const val DIAG_INTERVAL_FRAMES = 30L

        // Bright-pixel threshold for DIAG brightPixels count.
        // With exposure lock (1/1000 s, ISO 100), background V ≈ 0–30.
        // A laser dot should spike V to 200+. V > 150 isolates anything unusually bright.
        private const val DIAG_BRIGHT_THRESHOLD = 150.0
    }

    // MOG2 background subtractor — green channel only.
    // Why not red: the SIRT red laser is constant-on (cursor mode). After
    // ~history frames of the dot sitting on a target, MOG2 classifies it as
    // background and suppresses it — exactly wrong for position tracking.
    // Red gets HSV-only detection with no motion gate.
    private val mog2: BackgroundSubtractorMOG2 =
        Video.createBackgroundSubtractorMOG2(MOG2_HISTORY, MOG2_VAR_THRESHOLD, false)

    private var frameCounter = 0L

    // ── Shot deduplication state — mirrors ShotAccumulator from shot_accumulator.py ──
    // Initialise to MIN_ABSENT_FRAMES so the very first green flash immediately
    // registers as shot #1 (system starts "armed").
    private var greenAbsentCount      = MIN_ABSENT_FRAMES
    private var lastGreenCentroid: Pair<Int, Int>? = null
    private var shotCounter           = 0
    private var lastShotTimestampNs: Long? = null

    // FrameReport ring buffer — bounded at MAX_REPORTS to prevent unbounded growth
    private val _reports = ArrayDeque<FrameReport>()

    /** Thread-safe snapshot of stored FrameReports for external consumers. */
    val frameReports: List<FrameReport>
        get() = synchronized(_reports) { _reports.toList() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process one JPEG frame from Camera 0's ImageReader.
     *
     * Pipeline (mirrors motion_filtered_detector.py + shot_accumulator.py):
     *   1. Decode JPEG → BGR Mat
     *   2. BGR → HSV + BGR → Gray (once per frame, used by both channels)
     *   3. MOG2 foreground mask (green channel only)
     *   4. Green: HSV inRange AND fgMask + circularity + contrast gates
     *   5. Red:   HSV inRange (two ranges OR'd) — NO motion gate
     *   6. Shot dedup: absent-count + spatial-radius guard → shot counter
     *   7. Red trace logged every frame (not registered as shots)
     *   8. Log format: ts_ms, shot#, x, y, split_ms, confidence
     *   9. Store FrameReport in ring buffer
     */
    fun detect(jpegBytes: ByteArray): List<LaserDetection> {
        frameCounter++
        val ts = System.nanoTime()

        // Heartbeat — positive confirmation that frames are flowing in logcat.
        // Visible even when no laser is present; level=D so it's filterable.
        if (frameCounter % HEARTBEAT_FRAMES == 0L) {
            Log.d(TAG, "alive  frame=$frameCounter  shots=$shotCounter  absent=$greenAbsentCount")
        }

        val matBytes = MatOfByte(*jpegBytes)
        val bgr = Imgcodecs.imdecode(matBytes, Imgcodecs.IMREAD_COLOR)
        matBytes.release()

        if (bgr.empty()) {
            Log.w(TAG, "frame=$frameCounter  decode failed (empty mat)")
            return emptyList()
        }

        try {
            return runPipeline(bgr, ts)
        } finally {
            bgr.release()
        }
    }

    // ── Private pipeline ──────────────────────────────────────────────────────

    private fun runPipeline(bgr: Mat, ts: Long): List<LaserDetection> {
        // Convert once — both channels share the same HSV and gray mats
        val hsv  = Mat()
        val gray = Mat()
        Imgproc.cvtColor(bgr, hsv,  Imgproc.COLOR_BGR2HSV)
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

        // DIAG — every 30th frame log full-frame HSV maxima + bright-pixel count.
        // This confirms whether the laser is registering in HSV space at all,
        // independent of all filtering. If maxV stays near 0, the laser isn't
        // reaching the sensor (exposure too dark, wrong camera, JPEG decode issue).
        if (frameCounter % DIAG_INTERVAL_FRAMES == 0L) {
            logDiag(hsv)
        }

        // MOG2 foreground mask — detectShadows=false → output is 0 or 255 only
        val fgMask = Mat()
        mog2.apply(bgr, fgMask)
        Imgproc.threshold(fgMask, fgMask, 127.0, 255.0, Imgproc.THRESH_BINARY)

        try {
            // Green: motion-gated (AND fgMask) + all hard-reject gates
            val green = findLaserDots(
                hsv     = hsv,
                gray    = gray,
                fgMask  = fgMask,
                channel = LaserChannel.GREEN,
                lowA    = GREEN_LOW,
                highA   = GREEN_HIGH,
                minArea = GREEN_MIN_AREA,
                maxArea = GREEN_MAX_AREA,
                ts      = ts,
            )

            // Red: two ranges OR'd; fgMask=null skips the motion gate entirely
            val red = findLaserDots(
                hsv     = hsv,
                gray    = gray,
                fgMask  = null,
                channel = LaserChannel.RED,
                lowA    = RED_LOW_1,
                highA   = RED_HIGH_1,
                lowB    = RED_LOW_2,
                highB   = RED_HIGH_2,
                minArea = RED_MIN_AREA,
                maxArea = RED_MAX_AREA,
                ts      = ts,
            )

            // ── Shot deduplication (mirrors ShotAccumulator.process_frame_detections) ──
            var shotNumber: Int?   = null
            var splitMs:    Double? = null

            if (green.isEmpty()) {
                // No green this frame — advance the absence counter
                greenAbsentCount++
            } else {
                // Green IS present — pick the largest dot as the primary detection
                val primary = green.maxByOrNull { it.areaPx }!!

                if (greenAbsentCount >= MIN_ABSENT_FRAMES) {
                    // Green was absent long enough: candidate new shot
                    if (!isSpatialDuplicate(primary.centroidX, primary.centroidY)) {
                        shotCounter++
                        val prevTs = lastShotTimestampNs
                        splitMs = if (prevTs != null) (ts - prevTs) / 1_000_000.0 else null
                        lastShotTimestampNs = ts
                        shotNumber = shotCounter

                        val tMs = ts / 1_000_000.0
                        Log.i(TAG, "SHOT  ts_ms=%.1f  shot=%d  x=%d  y=%d  split_ms=%s  conf=%.3f"
                            .format(
                                tMs,
                                shotCounter,
                                primary.centroidX,
                                primary.centroidY,
                                splitMs?.let { "%.0f".format(it) } ?: "—",
                                primary.confidence,
                            )
                        )
                    }
                }

                greenAbsentCount = 0
                lastGreenCentroid = Pair(primary.centroidX, primary.centroidY)
            }

            // ── Red trace — log every frame; never registered as a shot ───────
            // Red is a constant-on cursor — ShotAccumulator ignores it for scoring.
            // Log it so the Python side can correlate pre-shot aiming position.
            for (det in red) {
                Log.i(TAG, "RED_TRACE  frame=%d  x=%d  y=%d  area=%.1f  conf=%.3f  ts_ms=%.1f"
                    .format(
                        frameCounter,
                        det.centroidX,
                        det.centroidY,
                        det.areaPx,
                        det.confidence,
                        ts / 1_000_000.0,
                    )
                )
            }

            val all = green + red

            // Store FrameReport in bounded ring buffer
            val report = FrameReport(frameCounter, ts, all, shotNumber, splitMs)
            synchronized(_reports) {
                _reports.addLast(report)
                while (_reports.size > MAX_REPORTS) _reports.removeFirst()
            }

            return all

        } finally {
            hsv.release()
            gray.release()
            fgMask.release()
        }
    }

    /** True if (x, y) is within SPATIAL_DEDUP_RADIUS_PX of the last registered centroid. */
    private fun isSpatialDuplicate(x: Int, y: Int): Boolean {
        val last = lastGreenCentroid ?: return false
        val dx = (x - last.first).toDouble()
        val dy = (y - last.second).toDouble()
        return sqrt(dx * dx + dy * dy) < SPATIAL_DEDUP_RADIUS_PX
    }

    /**
     * Run the full blob-detection pipeline for one HSV channel.
     *
     * [fgMask] null → skip motion gate (used for red channel).
     * [lowB]/[highB] optional second HSV range for red hue wraparound.
     *
     * Why contours+moments over HoughCircles (ported from Python comment):
     * Laser dots are rarely perfect circles — surface texture, camera angle,
     * and wall reflectivity all distort the blob. Contour area + centroid
     * is more robust AND faster than circle fitting on sub-640×480 masks.
     */
    private fun findLaserDots(
        hsv:     Mat,
        gray:    Mat,
        fgMask:  Mat?,          // null = no motion gate
        channel: LaserChannel,
        lowA:    Scalar,
        highA:   Scalar,
        lowB:    Scalar? = null,
        highB:   Scalar? = null,
        minArea: Double,
        maxArea: Double,
        ts:      Long,
    ): List<LaserDetection> {

        // HSV inRange — binary mask
        val mask = Mat()
        Core.inRange(hsv, lowA, highA, mask)

        // Second range OR'd in (red hue wraparound)
        if (lowB != null && highB != null) {
            val mask2 = Mat()
            Core.inRange(hsv, lowB, highB, mask2)
            Core.bitwise_or(mask, mask2, mask)
            mask2.release()
        }

        // Motion gate — green only; null fgMask = red cursor path = skip
        if (fgMask != null) {
            Core.bitwise_and(mask, fgMask, mask)
        }

        // Blur to merge adjacent bright pixels, then re-threshold for clean binary blobs
        // Mirrors Python _find_laser_dots GaussianBlur(5,5) + threshold(127)
        Imgproc.GaussianBlur(mask, mask, Size(5.0, 5.0), 0.0)
        Imgproc.threshold(mask, mask, 127.0, 255.0, Imgproc.THRESH_BINARY)

        val contours  = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        mask.release()
        hierarchy.release()

        val results = mutableListOf<LaserDetection>()

        for (contour in contours) {
            try {
                // ── Compute all values first, gate second ──────────────────────
                // This lets CANDIDATE logging show the actual measured values for
                // every contour regardless of which gate rejects it.

                val area = Imgproc.contourArea(contour)

                // Degenerate contour — no centroid possible, skip silently
                val m = Imgproc.moments(contour)
                if (m.m00 == 0.0) continue
                val cx = (m.m10 / m.m00).toInt()
                val cy = (m.m01 / m.m00).toInt()

                // Circularity: 4π·area/perimeter²  →  1.0 = perfect circle
                val c2f       = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(c2f, true)
                c2f.release()
                val circularity = if (perimeter > 0.0)
                    (4.0 * PI * area) / (perimeter * perimeter) else 0.0

                // HSV at centroid — clamp to frame bounds
                val hsvPx = hsv.get(
                    cy.coerceIn(0, hsv.rows() - 1),
                    cx.coerceIn(0, hsv.cols() - 1),
                ) ?: doubleArrayOf(0.0, 0.0, 0.0)
                val pH = hsvPx[0].toInt()
                val pS = hsvPx[1].toInt()
                val pV = hsvPx[2].toInt()

                // Local contrast ratio
                val contrast = computeContrastRatio(gray, cx, cy, area)

                // Determine the first gate this contour would fail (or PASS)
                val reason = when {
                    area < minArea            -> "AREA_LO"
                    area > maxArea            -> "AREA_HI"
                    circularity < MIN_CIRCULARITY -> "CIRC"
                    contrast < MIN_CONTRAST_RATIO -> "CONTRAST"
                    else                      -> "PASS"
                }

                // CANDIDATE log — emitted for every contour with a valid centroid,
                // before any gate is applied. Use Log.d so it's filterable via
                // logcat tag=LaserDetector level=DEBUG.
                Log.d(TAG,
                    "CANDIDATE frame=%d ch=%s x=%d y=%d area=%.1f circ=%.3f" +
                    " H=%d S=%d V=%d contrast=%.2f [%s]"
                        .format(
                            frameCounter, channel,
                            cx, cy, area, circularity,
                            pH, pS, pV, contrast,
                            reason,
                        )
                )

                // ── Apply gates ───────────────────────────────────────────────
                if (area < minArea || area > maxArea) continue
                if (circularity < MIN_CIRCULARITY) continue
                if (contrast < MIN_CONTRAST_RATIO) continue

                // Composite confidence: equal weight of circularity and scaled contrast
                val confidence = (
                    circularity.coerceIn(0.0, 1.0) * 0.5 +
                    (contrast / 10.0).coerceIn(0.0, 1.0) * 0.5
                )

                results += LaserDetection(
                    channel       = channel,
                    centroidX     = cx,
                    centroidY     = cy,
                    areaPx        = area,
                    confidence    = confidence,
                    contrastRatio = contrast,
                    timestampNs   = ts,
                    frameNumber   = frameCounter,
                )
            } finally {
                contour.release()
            }
        }

        return results
    }

    /**
     * Frame-wide HSV diagnostic — logged every DIAG_INTERVAL_FRAMES frames.
     *
     * Uses Core.split() + Core.minMaxLoc() for max channel values — O(1) OpenCV
     * operations, no manual pixel loops. brightPixels uses threshold + countNonZero.
     *
     * What to look for:
     *   maxV near 0       → laser not registering; check exposure / camera selection
     *   maxV 200–255      → laser bright enough; check H range (green ≈ H 35-85)
     *   maxH outside 35-85 → HSV colour is outside green range; calibrate laser
     *   brightPixels >> 0  → something bright is in frame; CANDIDATE log will show it
     */
    private fun logDiag(hsv: Mat) {
        val channels = ArrayList<Mat>(3)
        Core.split(hsv, channels)
        try {
            val maxH = Core.minMaxLoc(channels[0]).maxVal.toInt()
            val maxS = Core.minMaxLoc(channels[1]).maxVal.toInt()
            val maxV = Core.minMaxLoc(channels[2]).maxVal.toInt()

            // Count pixels brighter than DIAG_BRIGHT_THRESHOLD in the V channel
            val brightMask = Mat()
            Imgproc.threshold(
                channels[2], brightMask,
                DIAG_BRIGHT_THRESHOLD, 255.0, Imgproc.THRESH_BINARY,
            )
            val brightPixels = Core.countNonZero(brightMask)
            brightMask.release()

            Log.i(TAG,
                "DIAG frame=%d  maxH=%d  maxS=%d  maxV=%d  brightPixels=%d"
                    .format(frameCounter, maxH, maxS, maxV, brightPixels)
            )
        } finally {
            channels.forEach { it.release() }
        }
    }

    /**
     * Local contrast ratio: blob_mean / (ring_mean + 1.0)
     *
     * Mirrors Feature 4 in LaserDiscriminator.stage_one (laser_discriminator.py):
     *   r_blob = sqrt(area / π)
     *   inner disk: dist ≤ r_blob              — the blob itself
     *   outer ring: r_blob+2 < dist ≤ r_blob+22 — background reference annulus
     *
     * Returns high values (>1) for a bright dot on a dark background — the
     * expected signature with manual exposure lock active.
     * Low ratio = blob is not brighter than its surround = not a laser = reject.
     */
    private fun computeContrastRatio(gray: Mat, cx: Int, cy: Int, area: Double): Double {
        val rows   = gray.rows()
        val cols   = gray.cols()
        val r      = sqrt(area / PI).toInt().coerceAtLeast(1)
        val rRingI = (r + 2).toDouble()
        val rRingO = (r + 22).toDouble()

        var blobSum  = 0.0;  var blobCount  = 0
        var ringSum  = 0.0;  var ringCount  = 0

        val y1 = (cy - r - 22).coerceAtLeast(0)
        val y2 = (cy + r + 22).coerceAtMost(rows - 1)
        val x1 = (cx - r - 22).coerceAtLeast(0)
        val x2 = (cx + r + 22).coerceAtMost(cols - 1)

        for (py in y1..y2) {
            for (px in x1..x2) {
                val dx   = (px - cx).toDouble()
                val dy   = (py - cy).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                val v    = gray.get(py, px)[0]
                when {
                    dist <= r.toDouble()              -> { blobSum += v; blobCount++ }
                    dist > rRingI && dist <= rRingO   -> { ringSum += v; ringCount++ }
                }
            }
        }

        val blobMean = if (blobCount > 0) blobSum / blobCount else 0.0
        val ringMean = if (ringCount > 0) ringSum / ringCount else 0.0
        return blobMean / (ringMean + 1.0)
    }
}
