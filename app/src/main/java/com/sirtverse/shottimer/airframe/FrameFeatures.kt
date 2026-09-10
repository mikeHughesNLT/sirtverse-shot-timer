package com.sirtverse.shottimer.airframe

/**
 * Per-frame snapshot captured into the [FrameRing] for every analysis frame.
 *
 * CC-SIRT-TRUTH-MODE-001 B2: schema covers every feature + threshold the detector uses to
 * decide isShot, plus app-side zone verdict and exposure/lighting context. The [toJsonLine]
 * output is one row in the dump's features.jsonl — a flat JSON object so truth_report.py
 * can print "value vs threshold" tables without any schema archaeology.
 */
data class FrameFeatures(
    val tsMs: Long,
    // Detection output (from Detection data class)
    val score: Float,
    val yDelta: Float,
    val chromaDelta: Float,
    val normX: Double,
    val normY: Double,
    val isShot: Boolean,
    // Per-gate booleans
    val aboveThreshold: Boolean,   // score >= scoreThreshold
    val passNeighbor: Boolean,     // 4-connected compactness gate
    val passColor: Boolean,        // Cb < cbMax AND Cr < crMax
    // Detector thresholds (companion-object constants, read via CameraLaserDetector.X)
    val scoreThreshold: Float,
    val neighborFactor: Float,
    val cbMax: Int,
    val crMax: Int,
    val chromaWeight: Float,
    val emaAlpha: Float,
    // App-side zone verdict (B1)
    val rectIdx: Int,              // index of rect hit, or -1 if none / not applicable
    val counted: Boolean,          // true = this isShot event incremented the shot count
    val reason: String,            // "" | "OUT_OF_ZONE" | "START_IGNORE"
    // Exposure (from Detection.iso / shutterNs / roiLuma)
    val iso: Int,
    val shutterNs: Long,
    val targetLuma: Float,         // roiLuma: median luma inside the metered ROI this frame
    val exposureLocked: Boolean,
    // Context
    val lightingLabel: String,     // "DIM" | "ROOM" | "BRIGHT"
) {
    fun toJsonLine(): String = buildString {
        append("""{"ts_ms":$tsMs,""")
        append(""""score":${"%.2f".format(score)},""")
        append(""""y_delta":${"%.2f".format(yDelta)},""")
        append(""""chroma_delta":${"%.2f".format(chromaDelta)},""")
        append(""""nx":${"%.4f".format(normX)},"ny":${"%.4f".format(normY)},""")
        append(""""is_shot":$isShot,""")
        append(""""above_threshold":$aboveThreshold,"pass_neighbor":$passNeighbor,"pass_color":$passColor,""")
        append(""""score_threshold":$scoreThreshold,"neighbor_factor":$neighborFactor,""")
        append(""""cb_max":$cbMax,"cr_max":$crMax,""")
        append(""""chroma_weight":$chromaWeight,"ema_alpha":$emaAlpha,""")
        append(""""rect_idx":$rectIdx,"counted":$counted,"reason":"$reason",""")
        append(""""iso":$iso,"shutter_ns":$shutterNs,""")
        append(""""target_luma":${"%.1f".format(targetLuma)},""")
        append(""""exposure_locked":$exposureLocked,""")
        append(""""lighting":"$lightingLabel"}""")
    }
}
