package com.sirtverse.detectlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.sirtverse.detectioncore.Detection

/**
 * Canvas overlay drawn on top of the camera preview.
 *
 * Draws:
 *  - 3×3 grid with TL…BR labels (matches rig stations.json)
 *  - Detected-dot crosshair at (normX, normY): green on isShot, amber on candidate-rejected
 *  - Last-shot cell flash (green fill, fades over ~300 ms)
 *
 * Call [updateDetection] on every incoming [Detection]; the view self-fades via invalidate().
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val FADE_MS = 300L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 100
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        alpha = 140
    }

    private val shotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")  // amber
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val labels = arrayOf("TL", "TC", "TR", "ML", "MC", "MR", "BL", "BC", "BR")

    // Detection state (updated on every onDetection callback, main thread)
    private var normX = 0.0
    private var normY = 0.0
    private var isShot = false
    private var hasCandidate = false    // above threshold (any gate result)
    private var lastMarkerMs = -10_000L

    private var lastShotCell = -1
    private var lastShotMs = -10_000L

    fun updateDetection(d: Detection) {
        normX = d.normX
        normY = d.normY
        isShot = d.isShot
        hasCandidate = d.passNeighbor || (d.peakScore >= com.sirtverse.detectioncore.CameraLaserDetector.SCORE_THRESHOLD)
        lastMarkerMs = SystemClock.uptimeMillis()

        if (d.isShot) {
            lastShotCell = d.gridCell
            lastShotMs = SystemClock.uptimeMillis()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val now = SystemClock.uptimeMillis()
        val cw = w / 3f
        val ch = h / 3f

        // ── 3×3 grid ─────────────────────────────────────────────────────────
        for (col in 1..2) canvas.drawLine(col * cw, 0f, col * cw, h, gridPaint)
        for (row in 1..2) canvas.drawLine(0f, row * ch, w, row * ch, gridPaint)

        val labelMid = -(labelPaint.descent() + labelPaint.ascent()) / 2f
        for (cell in 0..8) {
            val col = cell % 3
            val row = cell / 3
            canvas.drawText(labels[cell], (col + 0.5f) * cw, (row + 0.5f) * ch + labelMid, labelPaint)
        }

        // ── Shot cell flash ───────────────────────────────────────────────────
        val flashFrac = ((FADE_MS - (now - lastShotMs)).coerceAtLeast(0) / FADE_MS.toFloat())
        if (lastShotCell >= 0 && flashFrac > 0f) {
            val col = lastShotCell % 3
            val row = lastShotCell / 3
            flashPaint.alpha = (flashFrac * 90).toInt()
            canvas.drawRect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch, flashPaint)
        }

        // ── Dot marker (crosshair + ring) ─────────────────────────────────────
        val markerFrac = ((FADE_MS - (now - lastMarkerMs)).coerceAtLeast(0) / FADE_MS.toFloat())
        if (markerFrac > 0f && hasCandidate) {
            val px = (normX * w).toFloat()
            val py = (normY * h).toFloat()
            val paint = if (isShot) shotPaint else candidatePaint
            paint.alpha = (markerFrac * 255).toInt()
            val r = 28f
            val arm = r + 12f
            canvas.drawCircle(px, py, r, paint)
            canvas.drawLine(px - arm, py, px + arm, py, paint)
            canvas.drawLine(px, py - arm, px, py + arm, paint)
        }

        // Keep redrawing while any fade is still active
        if (markerFrac > 0f || flashFrac > 0f) invalidate()
    }
}
