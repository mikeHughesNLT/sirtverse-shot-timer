package com.sirtverse.shottimer.airframe

import com.sirtverse.detectioncore.TargetRoi
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-drawn target rectangle in display-normalized space (0..1, top-left origin).
 *
 * W1a r2 (2026-09-07): replaces the fixed-size [TargetZone] point+square. Finger-drawn,
 * free aspect, up to 3 per session. Serialized to JSON so W1b can embed it per-session.
 *
 * All coordinate arithmetic routes through [PreviewSpaceMapper] — do not add raw pixel
 * math here.
 */
data class TargetRect(
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
) {
    fun left()   = cx - halfW
    fun top()    = cy - halfH
    fun right()  = cx + halfW
    fun bottom() = cy + halfH

    fun contains(nx: Float, ny: Float): Boolean =
        nx >= left() && nx <= right() && ny >= top() && ny <= bottom()

    fun toTargetRoi(): TargetRoi = TargetRoi(cx, cy, maxOf(halfW, halfH))

    fun toJson(): JSONObject = JSONObject()
        .put("cx", cx.toDouble())
        .put("cy", cy.toDouble())
        .put("halfW", halfW.toDouble())
        .put("halfH", halfH.toDouble())

    companion object {
        const val MIN_HALF = 0.02f  // 4% minimum half-extent on each axis
        const val MAX_RECTS = 3

        fun fromJson(obj: JSONObject): TargetRect = TargetRect(
            cx    = obj.getDouble("cx").toFloat(),
            cy    = obj.getDouble("cy").toFloat(),
            halfW = obj.getDouble("halfW").toFloat(),
            halfH = obj.getDouble("halfH").toFloat(),
        )

        fun listToJson(rects: List<TargetRect>): String =
            JSONArray().also { arr -> rects.forEach { arr.put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<TargetRect> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}
