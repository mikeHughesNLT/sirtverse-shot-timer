package com.sirtverse.detectioncore

/**
 * Widened detector output — emitted every processed frame.
 *
 * gridCell derivation: col = floor(normX * 3), row = floor(normY * 3),
 * gridCell = row*3 + col  (0=TL … 8=BR, top-left origin).
 */
data class Detection(
    val peakCellX: Int,
    val peakCellY: Int,
    val normX: Double,           // 0..1, top-left origin
    val normY: Double,
    val peakScore: Float,        // Y-plane brightness delta at peak
    val passNeighbor: Boolean,   // 4-connected compactness gate
    val passColor: Boolean,      // Cb/Cr green gate
    val isShot: Boolean,         // passed pulse state machine = a real shot
    val gridCell: Int,           // 0..8 (TL=0 … BR=8)
    val timestampNs: Long,       // imageInfo.timestamp nanoseconds
)
