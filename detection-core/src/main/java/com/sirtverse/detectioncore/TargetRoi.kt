package com.sirtverse.detectioncore

/**
 * Target region of interest, in DISPLAY-normalized coords (0..1, top-left origin) — the same
 * space as [Detection.normX]/[Detection.normY] and the app's `TargetZone`.
 *
 * Used by [CameraLaserDetector] to meter exposure on the paper target
 * (CC-SIRT-EXPOSURE-CONTROL-001 / Feature 22). The detector maps this rectangle back into its
 * transposed+mirrored analysis-buffer grid to sample luma; see the mapping note in
 * [CameraLaserDetector.processFrame].
 *
 * [halfSize] is the half-extent of the square zone (0.125 => a 25%×25% box), matching the app's
 * `TargetZone.HALF_SIZE`. When the detector's ROI is null it falls back to a centered box of this
 * size, which is the un-tapped / bench case (the rig is aimed at frame center).
 */
data class TargetRoi(
    val cx: Float,
    val cy: Float,
    val halfSize: Float = 0.125f,
)
