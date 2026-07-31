package com.sirtverse.detectioncore

/**
 * Static capabilities snapshot for one back-facing camera lens, populated after
 * [CameraController.bind] returns.
 */
data class CameraCaps(
    val lensId: String,
    val exposureRangeNs: LongRange?,
    val isoRange: IntRange?,
    val maxFps: Int,
)
