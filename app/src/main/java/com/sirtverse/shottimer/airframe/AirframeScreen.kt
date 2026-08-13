package com.sirtverse.shottimer.airframe

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.CameraXController
import com.sirtverse.detectioncore.Detection
import com.sirtverse.shottimer.SettingsStoreDetectionConfig
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
 * The airframe spine: start/stop, par-time cue, shot list w/ splits — driven by
 * [CameraLaserDetector] behind the `LaserDetector` seam (CC-SIRT-AIRFRAME-REALDET-001).
 *
 * Camera lifecycle: [CameraXController] is created here and bound to the Compose
 * [LocalLifecycleOwner] via a [PreviewView] surface; [CameraXController.shutdown] is called
 * on composition disposal. Permission is requested on screen entry, mirroring
 * ShotTimerActivity's pattern.
 *
 * Detection parameters (threshold, gates, EMA, cooldown) are owned by :detection-core and
 * unchanged here — RULE-ARSENAL-001.
 *
 * Hit markers: on each isShot, normX/normY from [Detection] are captured into [hitMarkers]
 * and forwarded to [TargetSelectionPanel] for persistent numbered display (B1). Cleared on
 * START/reset. Splits list below the panel shows a large last-split display + running history
 * (B2). Par-time field moved to the Panel bottom sheet to remove camera-bleed overlap (B3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirframeScreen(settings: SettingsStore) {
    // ── Camera plumbing (CC-SIRT-AIRFRAME-REALDET-001) ──────────────────────────
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraXController = remember { CameraXController(context) }

    val scope = rememberCoroutineScope()
    val detector = remember { CameraLaserDetector(cameraXController, context, SettingsStoreDetectionConfig(settings)) }
    val engine = remember { ShotTimerEngine() }

    var sessionState by remember { mutableStateOf(engine.state) }
    var statusText by remember { mutableStateOf("Tap START — real detector live") }
    val shots = remember { mutableStateListOf<Shot>() }
    var liveDot by remember { mutableStateOf<Detection?>(null) }

    // Hit markers: position captured at shot time, persisted for session (B1).
    val hitMarkers = remember { mutableStateListOf<HitMarker>() }
    // Derive an immutable snapshot list — new reference every time hitMarkers changes.
    // Passing this (not hitMarkers directly) to TargetSelectionPanel guarantees
    // Compose sees a parameter change and recomposes the panel + Canvas.
    val hitList by remember { derivedStateOf { hitMarkers.toList() } }

    var targetMode by remember { mutableStateOf(TargetMode.AUTO) }
    var targetZone by remember { mutableStateOf<TargetZone?>(null) }

    // Par time — owned here, displayed in the Panel bottom sheet (B3 overlap fix).
    var parSecondsText by remember { mutableStateOf("") }
    var parFiredThisRun by remember { mutableStateOf(false) }

    var lockedExposureEnabled by remember { mutableStateOf(settings.lockedExposureEnabled) }
    var panelOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Camera permission — mirrors ShotTimerActivity: check first, request if absent.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Shutdown camera controller when this composable leaves the composition.
    DisposableEffect(lifecycleOwner) {
        onDispose { cameraXController.shutdown() }
    }

    fun beep(tone: Int, durationMs: Int) {
        if (!settings.soundEnabled) return
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(tone, durationMs) }
    }

    // Wire the detection seam ONCE. Everything below this block is unchanged vs mock.
    DisposableEffect(Unit) {
        detector.onDetection = { d ->
            liveDot = d
            if (d.isShot) {
                engine.recordHit()?.let { shot ->
                    shots.add(shot)
                    // Capture position at shot time for persistent numbered marker (B1).
                    hitMarkers.add(HitMarker(shot.number, d.normX.toFloat(), d.normY.toFloat()))
                }
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
        hitMarkers.clear()   // reset markers on new session (B1)
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
                title = { Text("Airframe", color = OnSurfaceLight) },
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
            // ── Target mode chips ─────────────────────────────────────────────
            // Zone placement now handled by tap gesture on the camera Box below.
            TargetSelectionPanel(
                mode = targetMode,
                onModeChange = { targetMode = it },
                zone = targetZone,
                onZoneChange = { targetZone = it },
            )

            Spacer(Modifier.height(4.dp))

            // ── Camera + Hit Markers overlay (B1) ────────────────────────────
            // Architecture: Box(clipToBounds) contains:
            //   1. AndroidView(PreviewView, COMPATIBLE/TextureView) — camera at bottom z-order.
            //      TextureView renders in the normal View layer, allowing Compose composables
            //      above it in the same Box to draw on top.
            //   2. Canvas — zone rectangle + live dot (Compose layer, above TextureView).
            //   3. BoxWithConstraints — hit markers as Compose Box composables (topmost layer).
            //      Using Compose composables (not Canvas drawCircle) guarantees they are always
            //      above the TextureView regardless of FILL_CENTER transform overflow.
            // clipToBounds() prevents the TextureView's FILL_CENTER overflow from bleeding
            // above/below this Box into adjacent composables.
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clipToBounds()
                        .pointerInput(targetMode) {
                            if (targetMode == TargetMode.TAP) {
                                detectTapGestures { offset ->
                                    val nx = (offset.x / size.width).coerceIn(0f, 1f)
                                    val ny = (offset.y / size.height).coerceIn(0f, 1f)
                                    targetZone = TargetZone(nx, ny)
                                }
                            }
                        },
                ) {
                    // Layer 1: Camera (TextureView via COMPATIBLE — renders in View layer)
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                // COMPATIBLE = TextureView: renders in normal View/Compose layer.
                                // Compose composables in this Box draw ABOVE the TextureView.
                                // FILL_CENTER (default) overflow is clipped by clipToBounds().
                                pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                cameraXController.bind(lifecycleOwner, pv.surfaceProvider)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Layer 2: Zone rect + live dot on Canvas (above TextureView)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        targetZone?.let { z ->
                            val cx = z.cx * size.width
                            val cy = z.cy * size.height
                            val w = 2 * TargetZone.HALF_SIZE * size.width
                            val h = 2 * TargetZone.HALF_SIZE * size.height
                            drawRect(
                                color = Color(0xFF2EA043),
                                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                                size = Size(w, h),
                                style = Stroke(width = 3f),
                            )
                        }
                        liveDot?.let { d ->
                            drawCircle(
                                color = if (d.isShot) Color(0xFFDA3633) else Color(0xFFD29922),
                                radius = if (d.isShot) 10f else 4f,
                                center = Offset(
                                    (d.normX * size.width).toFloat(),
                                    (d.normY * size.height).toFloat(),
                                ),
                            )
                        }
                    }

                    // Layer 3: Numbered hit markers as Compose composables (B1).
                    // These are always above the TextureView in z-order; no Canvas drawing
                    // needed. hitList is derivedStateOf so a new reference arrives on each
                    // addition — BoxWithConstraints recomposes and places updated markers.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val panelW = maxWidth.value   // dp
                        val panelH = maxHeight.value  // dp
                        hitList.forEach { hit ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .offset(
                                        x = (hit.normX * panelW - 14f).dp,
                                        y = (hit.normY * panelH - 14f).dp,
                                    )
                                    .background(Color(0xFFDA3633), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${hit.number}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Camera permission required",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(4.dp))
            DetectorDiagOverlay(liveDot)
            Spacer(Modifier.height(8.dp))

            // ── Status line ──────────────────────────────────────────────────
            Text(statusText, color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))

            // ── Large last-split display (B2) ────────────────────────────────
            // Shows time-from-GO for shot #1; inter-shot split for later shots.
            // Blank when no shots yet. Updates live as each shot registers.
            if (shots.isNotEmpty()) {
                val last = shots.last()
                val splitLabel = if (last.number == 1)
                    TimeFmt.seconds(last.timeMs)
                else
                    "+${TimeFmt.secondsBare(last.splitMs)}s"
                Text(
                    text = splitLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = AccentGreen,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E1116))
                        .padding(vertical = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            // ── Running shot list (shot #, elapsed, split) ───────────────────
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
                    onClick = {
                        // Mirrors ShotTimerActivity: synthesise an isShot Detection manually.
                        detector.onDetection?.invoke(
                            Detection(
                                peakCellX    = 0, peakCellY = 0,
                                normX        = 0.5, normY = 0.5,
                                peakScore    = 100f,
                                passNeighbor = true, passColor = true,
                                isShot       = true,
                                gridCell     = 4,
                                timestampNs  = SystemClock.elapsedRealtimeNanos(),
                            )
                        )
                    },
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

    // ── Panel settings sheet ──────────────────────────────────────────────────
    // Par-time field moved here to avoid camera-preview bleed-through (B3).
    if (panelOpen) {
        ModalBottomSheet(onDismissRequest = { panelOpen = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Session Panel", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                // Par time (moved from main layout — B3 overlap fix)
                OutlinedTextField(
                    value = parSecondsText,
                    onValueChange = { parSecondsText = it },
                    label = { Text("Par time (s, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = sessionState != ShotTimerEngine.State.RUNNING,
                    modifier = Modifier.fillMaxWidth(),
                )

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
 * Works with both [com.sirtverse.detectioncore.MockLaserDetector] (score=100 on shot,
 * 0 otherwise) and the real [CameraLaserDetector] (per-frame sub-threshold scores visible).
 * When the real detector is wired in, this overlay shows actual ambient-light behavior
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
