package com.sirtverse.shottimer.airframe

/**
 * Maps between Box pixel coordinates (the camera preview Box in the Compose layout) and
 * display-normalized frame coordinates (0..1, top-left origin) — the space that
 * [com.sirtverse.detectioncore.Detection.normX]/[normY] live in.
 *
 * Pure Kotlin, zero Android imports — unit-testable on the JVM.
 *
 * W1a B2 (single coordinate space):
 * The detection pipeline already emits display-oriented normalized coords (fixed at 4a0900d).
 * With FILL_CENTER (default PreviewView scale type), the preview fills the Box by cropping the
 * camera frame symmetrically. For a frame that is exactly the same aspect as the Box, the
 * mapping is identity. For differing aspects (common — phone camera is 4:3, Box is landscape),
 * there is a crop offset that this mapper accounts for.
 *
 * When [frameAspect] is null (unknown), identity mapping is used — the safe default when the
 * camera hasn't reported a frame yet.
 *
 * @param boxWidthPx   Width of the Compose camera Box in pixels.
 * @param boxHeightPx  Height of the Compose camera Box in pixels.
 * @param frameAspect  Camera frame width/height ratio (e.g. 1.333 for 4:3 landscape frames),
 *                     or null if unknown.
 */
class PreviewSpaceMapper(
    val boxWidthPx: Float,
    val boxHeightPx: Float,
    val frameAspect: Float? = null,
) {
    private val boxAspect: Float = if (boxHeightPx > 0f) boxWidthPx / boxHeightPx else 1f

    // FILL_CENTER crop offsets as fractions of the box dimension (0..1).
    // xCropFrac = fraction of box width that is outside the frame (each side).
    // yCropFrac = fraction of box height that is outside the frame (each side).
    private val xCropFrac: Float
    private val yCropFrac: Float

    init {
        val fa = frameAspect
        if (fa == null || fa <= 0f) {
            xCropFrac = 0f
            yCropFrac = 0f
        } else {
            if (fa > boxAspect) {
                // Frame is wider than box → scale to fit height → left/right crop.
                // Scale factor = boxH / frameH in display pixels.
                // Rendered frame width = fa * boxH; crop per side = (fa*boxH - boxW) / 2
                // As a fraction of boxW: xCropFrac = (fa/boxAspect - 1) / 2
                xCropFrac = (fa / boxAspect - 1f) / 2f
                yCropFrac = 0f
            } else {
                // Frame is taller than box → scale to fit width → top/bottom crop.
                // Rendered frame height = boxW / fa; crop per side = (boxW/fa - boxH) / 2
                // As fraction of boxH: yCropFrac = (boxAspect/fa - 1) / 2
                xCropFrac = 0f
                yCropFrac = (boxAspect / fa - 1f) / 2f
            }
        }
    }

    /**
     * Converts a Box-pixel tap position to display-normalized frame coordinates.
     * Returns a pair (normX, normY) clamped to [0, 1].
     */
    fun boxToNorm(px: Float, py: Float): Pair<Float, Float> {
        if (boxWidthPx <= 0f || boxHeightPx <= 0f) return Pair(0.5f, 0.5f)
        val nx = ((px / boxWidthPx) * (1f + 2f * xCropFrac) - xCropFrac).coerceIn(0f, 1f)
        val ny = ((py / boxHeightPx) * (1f + 2f * yCropFrac) - yCropFrac).coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

    /**
     * Converts display-normalized frame coordinates to Box-pixel coordinates.
     * The result may be outside [0, boxW/H] if the frame coord is in the cropped region.
     */
    fun normToBox(nx: Float, ny: Float): Pair<Float, Float> {
        if (boxWidthPx <= 0f || boxHeightPx <= 0f) return Pair(0f, 0f)
        val px = ((nx + xCropFrac) / (1f + 2f * xCropFrac)) * boxWidthPx
        val py = ((ny + yCropFrac) / (1f + 2f * yCropFrac)) * boxHeightPx
        return Pair(px, py)
    }
}
