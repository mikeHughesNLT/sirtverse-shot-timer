package com.sirtverse.shottimer.airframe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One recorded laser hit — carries position for numbered marker overlay on the camera view.
 * Rendered as Compose composables in AirframeScreen's camera Box, not on the panel Canvas.
 *
 * @param number  1-based shot index within the session (from Shot.number).
 * @param normX   Normalized X position (0..1, left=0) at detection time.
 * @param normY   Normalized Y position (0..1, top=0) at detection time.
 */
data class HitMarker(val number: Int, val normX: Float, val normY: Float)

/**
 * Target-selection mode. DRAW is the active mode (finger-drawn rects); AUTO is a placeholder.
 */
enum class TargetMode { AUTO, DRAW }

private val Placeholder = Color(0xFF8B949E)

/**
 * Target mode chip row + status hint (W1a r2).
 *
 * Hit markers and the camera overlay are rendered by AirframeScreen's camera Box layer —
 * not here — so they always appear above the TextureView in z-order.
 */
@Composable
fun TargetSelectionPanel(
    mode: TargetMode,
    onModeChange: (TargetMode) -> Unit,
    rectCount: Int,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == TargetMode.AUTO,
                onClick = { onModeChange(TargetMode.AUTO) },
                label = { Text("Auto-Detect") },
            )
            FilterChip(
                selected = mode == TargetMode.DRAW,
                onClick = { onModeChange(TargetMode.DRAW) },
                label = { Text("Draw") },
            )
            if (rectCount > 0) {
                AssistChip(onClick = onClearAll, label = { Text("Clear All") })
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            mode == TargetMode.AUTO -> Text(
                "Auto-Detect coming soon",
                color = Placeholder,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            rectCount == 0 -> Text(
                "Drag on the camera view to draw a target rectangle (up to ${TargetRect.MAX_RECTS})",
                color = Placeholder,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            rectCount >= TargetRect.MAX_RECTS -> Text(
                "$rectCount rect${if (rectCount > 1) "s" else ""} drawn — long-press to delete",
                color = Placeholder,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Text(
                "$rectCount rect${if (rectCount > 1) "s" else ""} drawn — drag to add more, long-press to delete",
                color = Placeholder,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
