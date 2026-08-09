package com.sirtverse.shottimer.airframe

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.Detection
import com.sirtverse.detectioncore.MockLaserDetector
import com.sirtverse.shottimer.domain.shottimer.Shot
import com.sirtverse.shottimer.domain.shottimer.ShotTimerEngine
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val BgDark = Color(0xFF0E1116)
private val SurfaceDark = Color(0xFF161B22)
private val OnSurfaceLight = Color(0xFFE6EDF3)
private val Muted = Color(0xFF8B949E)
private val AccentGreen = Color(0xFF2EA043)
private val Amber = Color(0xFFD29922)
private val DangerRed = Color(0xFFDA3633)

@Composable
fun AirframeApp(settings: SettingsStore) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceLight,
            onBackground = OnSurfaceLight,
            primary = AccentGreen,
            secondary = Amber,
            error = DangerRed,
        ),
    ) {
        AirframeScreen(settings)
    }
}

/**
 * The airframe spine: start/stop, par-time cue, shot list w/ splits — all driven by
 * [MockLaserDetector] behind the `LaserDetector` seam. No camera API of any kind is
 * referenced here; the only detector this file knows about is the mock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirframeScreen(settings: SettingsStore) {
    val scope = rememberCoroutineScope()
    val detector = remember { MockLaserDetector() }
    val engine = remember { ShotTimerEngine() }

    var sessionState by remember { mutableStateOf(engine.state) }
    var statusText by remember { mutableStateOf("Tap START — mock airframe, zero hardware") }
    val shots = remember { mutableStateListOf<Shot>() }
    var liveDot by remember { mutableStateOf<Detection?>(null) }

    var targetMode by remember { mutableStateOf(TargetMode.AUTO) }
    var targetZone by remember { mutableStateOf<TargetZone?>(null) }

    var parSecondsText by remember { mutableStateOf("") }
    var parFiredThisRun by remember { mutableStateOf(false) }

    var lockedExposureEnabled by remember { mutableStateOf(settings.lockedExposureEnabled) }
    var panelOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    fun beep(tone: Int, durationMs: Int) {
        if (!settings.soundEnabled) return
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(tone, durationMs) }
    }

    // Wire the seam ONCE. This file depends only on LaserDetector + Detection — no camera
    // API of any kind. Swap point for the real detector: replace `MockLaserDetector()`
    // above with `CameraLaserDetector(...)` — everything below is unchanged.
    DisposableEffect(Unit) {
        detector.onDetection = { d ->
            liveDot = d
            if (d.isShot) {
                engine.recordHit()?.let { shot -> shots.add(shot) }
            }
        }
        onDispose { detector.stop() }
    }

    // Par-time watchdog: one beep per run when elapsed crosses the configured par time.
    LaunchedEffect(sessionState) {
        val parMs = parSecondsText.toDoubleOrNull()?.times(1000.0)
        while (sessionState == ShotTimerEngine.State.RUNNING) {
            val elapsed = engine.elapsedMsOrNull()
            if (!parFiredThisRun && parMs != null && elapsed != null && elapsed >= parMs) {
                parFiredThisRun = true
                beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            }
            delay(50)
        }
    }

    fun beginCountdown() {
        engine.reset()
        engine.beginCountdown()
        sessionState = engine.state
        shots.clear()
        liveDot = null
        parFiredThisRun = false
        statusText = "Get ready…"
        scope.launch {
            delay(settings.randomStartDelayMs())
            engine.go()
            sessionState = engine.state
            detector.start()
            beep(ToneGenerator.TONE_CDMA_HIGH_L, 200)
            statusText = "GO!"
        }
    }

    fun endSession() {
        detector.stop()
        engine.end()
        sessionState = engine.state
        statusText = "Session ended — ${shots.size} shot(s)"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Airframe (Mock)", color = OnSurfaceLight) },
                actions = {
                    TextButton(onClick = { panelOpen = true }) { Text("Panel ▤") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            TargetSelectionPanel(
                mode = targetMode,
                onModeChange = { targetMode = it },
                zone = targetZone,
                onZoneChange = { targetZone = it },
                liveDot = liveDot,
            )

            Spacer(Modifier.height(4.dp))
            DetectorDiagOverlay(liveDot)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = parSecondsText,
                onValueChange = { parSecondsText = it },
                label = { Text("Par time (s, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = sessionState != ShotTimerEngine.State.RUNNING,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(statusText, color = Muted)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shots) { shot -> ShotRow(shot) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Button(
                    onClick = { beginCountdown() },
                    enabled = sessionState == ShotTimerEngine.State.IDLE || sessionState == ShotTimerEngine.State.ENDED,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.weight(1f),
                ) { Text("START") }

                Button(
                    onClick = { detector.simulateHit() },
                    enabled = sessionState == ShotTimerEngine.State.RUNNING,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    modifier = Modifier.weight(1f),
                ) { Text("Simulate Hit") }

                Button(
                    onClick = { endSession() },
                    enabled = sessionState == ShotTimerEngine.State.RUNNING || sessionState == ShotTimerEngine.State.COUNTDOWN,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.weight(1f),
                ) { Text("STOP") }
            }
        }
    }

    if (panelOpen) {
        ModalBottomSheet(onDismissRequest = { panelOpen = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Session Panel", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = lockedExposureEnabled,
                        onCheckedChange = {
                            lockedExposureEnabled = it
                            settings.lockedExposureEnabled = it
                        },
                    )
                    Column {
                        Text("Locked Exposure (Feature 22)")
                        Text(
                            "Wired control — no CV yet (Detection-Arsenal.md #22)",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { panelOpen = false }) { Text("Close") }
            }
        }
    }
}

/**
 * Per-frame detection diagnostic bar (CC-SIRT-F22-VISIBILITY-001 §B3).
 *
 * Read-only display of [Detection] pipeline state — no new detection logic.
 * Gates shown:
 *  - V (brightness): peakScore ≥ [CameraLaserDetector.SCORE_THRESHOLD]
 *  - H (hue/color): [Detection.passColor] (Cb/Cr green gate)
 *  - CMPCT (compactness): [Detection.passNeighbor] (4-connected neighbor gate)
 *  - SHOT: 600 ms flash on [Detection.isShot]
 *
 * Works with both the [com.sirtverse.detectioncore.MockLaserDetector] (score=100 on shot,
 * 0 otherwise) and the real [CameraLaserDetector] (per-frame sub-threshold scores visible).
 * When the real detector is swapped in, this overlay shows actual ambient-light behavior
 * Mike can use to verify the locked-exposure effect (Feature 22) is working.
 */
@Composable
private fun DetectorDiagOverlay(liveDot: Detection?) {
    val threshold = CameraLaserDetector.SCORE_THRESHOLD

    // SHOT flash: fires once per shot (unique timestampNs), clears after 600 ms.
    // When isShot=false, shotTs stays 0L across all idle frames — effect does not re-fire.
    val shotTs = liveDot?.takeIf { it.isShot }?.timestampNs ?: 0L
    var shotFlash by remember { mutableStateOf(false) }
    LaunchedEffect(shotTs) {
        if (shotTs > 0L) {
            shotFlash = true
            delay(600L)
            shotFlash = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC0A0F14))   // semi-transparent near-black
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val d = liveDot
            if (d != null) {
                val passV = d.peakScore >= threshold
                Text(
                    text = "score=${"%.1f".format(d.peakScore)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (passV) AccentGreen else Muted,
                )
                DiagGate("V", passV)
                DiagGate("H", d.passColor)
                DiagGate("CMPCT", d.passNeighbor)
                if (shotFlash) {
                    Text(
                        text = "🎯 SHOT",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen,
                    )
                }
            } else {
                Text(
                    text = "detector idle",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

/** Single gate indicator: name + ✓ (green) or ✗ (amber). */
@Composable
private fun DiagGate(name: String, pass: Boolean) {
    Text(
        text = if (pass) "$name✓" else "$name✗",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = if (pass) AccentGreen else Amber,
    )
}

@Composable
private fun ShotRow(shot: Shot) {
    val label = if (shot.number == 1)
        "#1   ${TimeFmt.seconds(shot.timeMs)}   (first shot)"
    else
        "#${shot.number}   ${TimeFmt.seconds(shot.timeMs)}   +${TimeFmt.secondsBare(shot.splitMs)}s"
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = OnSurfaceLight)
    }
}
