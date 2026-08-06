package com.sirtverse.shottimer.airframe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sirtverse.detectioncore.Detection

/**
 * Target-selection scaffold (CC-SIRT-APPSHELL-AIRFRAME-001 §B-3). Two stubbed modes,
 * per CC-SIRT-TARGET-SPEC-BOTH-PLATFORMS-001 §1:
 *  - [TargetMode.AUTO] — placeholder, no CV yet. Real auto-detect is a separate brief
 *    (CC-SIRT-TARGET-AUTODETECT-ANDROID-001) — do not build it here.
 *  - [TargetMode.TAP] — live: tap places a 25%x25% zone centered at the tap, mirroring the
 *    iOS `235a733` interaction (reused, not re-derived).
 * The live mock dot ([Detection.normX]/[Detection.normY]) always overlays against whatever
 * is placed, regardless of mode — this is the whole point of the seam.
 */
enum class TargetMode { AUTO, TAP }

/** Normalized (0..1, top-left origin) target zone — a 25%x25% box centered at ([cx],[cy]). */
data class TargetZone(val cx: Float, val cy: Float) {
    companion object {
        const val HALF_SIZE = 0.125f // 25% zone width/height => 12.5% half-extent
    }
}

private val PanelSurface = Color(0xFF161B22)
private val Placeholder = Color(0xFF8B949E)
private val TargetGreen = Color(0xFF2EA043)
private val ShotRed = Color(0xFFDA3633)
private val IdleAmber = Color(0xFFD29922)

@Composable
fun TargetSelectionPanel(
    mode: TargetMode,
    onModeChange: (TargetMode) -> Unit,
    zone: TargetZone?,
    onZoneChange: (TargetZone?) -> Unit,
    liveDot: Detection?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == TargetMode.AUTO,
                onClick = { onModeChange(TargetMode.AUTO) },
                label = { Text("Auto-Detect") },
            )
            FilterChip(
                selected = mode == TargetMode.TAP,
                onClick = { onModeChange(TargetMode.TAP) },
                label = { Text("Tap to Place") },
            )
            if (zone != null) {
                AssistChip(onClick = { onZoneChange(null) }, label = { Text("Clear Zone") })
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(PanelSurface)
                .pointerInput(mode) {
                    if (mode == TargetMode.TAP) {
                        detectTapGestures { offset ->
                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ny = (offset.y / size.height).coerceIn(0f, 1f)
                            onZoneChange(TargetZone(nx, ny))
                        }
                    }
                },
        ) {
            when {
                mode == TargetMode.AUTO -> Text(
                    "AUTO-DETECT — placeholder, no CV yet\n(see CC-SIRT-TARGET-AUTODETECT-ANDROID-001)",
                    color = Placeholder,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                zone == null -> Text(
                    "Tap anywhere to place a target zone",
                    color = Placeholder,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                zone?.let { z ->
                    val cx = z.cx * size.width
                    val cy = z.cy * size.height
                    val w = 2 * TargetZone.HALF_SIZE * size.width
                    val h = 2 * TargetZone.HALF_SIZE * size.height
                    drawRect(
                        color = TargetGreen,
                        topLeft = Offset(cx - w / 2f, cy - h / 2f),
                        size = Size(w, h),
                        style = Stroke(width = 3f),
                    )
                }
                liveDot?.let { d ->
                    drawCircle(
                        color = if (d.isShot) ShotRed else IdleAmber,
                        radius = if (d.isShot) 10f else 4f,
                        center = Offset((d.normX * size.width).toFloat(), (d.normY * size.height).toFloat()),
                    )
                }
            }
        }
    }
}
