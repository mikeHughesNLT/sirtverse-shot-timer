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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlin.math.abs

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
 * W1a r2 (CC-SIRT-TARGET-REGION-001 r2 addendum):
 * - Target zone replaced by a [List<TargetRect>] (up to 3, finger-drawn, free aspect).
 * - Drag-to-draw gesture on the camera Box; tap-inside selects; long-press deletes.
 * - Rects persisted via [SettingsStore.targetRectsJson].
 * - [PreviewSpaceMapper] is the single coordinate-space authority.
 * - B3.5: Camera2 AE metering region set to the selected rect when [meterToTargetEnabled].
 *
 * CC-SIRT-TRUTH-MODE-001 (P2):
 * - B1: zone-only gating — isShot events outside all rects are dropped (ignored tally +1).
 * - B1: 1 s start-ignore — first 1000 ms after GO are not counted.
 * - B2: FrameRing — every analysis frame (JPEG q80 + FrameFeatures) buffered for 2 s.
 * - B3: TruthWriter — events.jsonl per session; MISSED / PHANTOM dump buttons.
 * - B4: Lighting chip DIM / ROOM / BRIGHT in the panel, persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirframeScreen(settings: SettingsStore) {
    // ── Camera plumbing ───────────────────────────────────────────────────────
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

    val hitMarkers = remember { mutableStateListOf<HitMarker>() }
    val hitList by remember { derivedStateOf { hitMarkers.toList() } }

    // ── Target rects (W1a r2) ─────────────────────────────────────────────────
    var targetMode by remember { mutableStateOf(TargetMode.DRAW) }
    val targetRects = remember { mutableStateListOf<TargetRect>() }
    var selectedRectIdx by remember { mutableStateOf(-1) }

    // Live drag preview (null = no drag in progress)
    var liveDragStart by remember { mutableStateOf<Offset?>(null) }
    var liveDragEnd   by remember { mutableStateOf<Offset?>(null) }

    // B3.5 — Meter to target
    var meterToTargetEnabled by remember { mutableStateOf(settings.meterToTargetEnabled) }

    // Par time
    var parSecondsText by remember { mutableStateOf("") }
    var parFiredThisRun by remember { mutableStateOf(false) }

    var lockedExposureEnabled by remember { mutableStateOf(settings.lockedExposureEnabled) }
    var autoMeterEnabled by remember { mutableStateOf(settings.exposureAutoMeterEnabled) }
    var panelOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // ── CC-SIRT-TRUTH-MODE-001 state ─────────────────────────────────────────
    // B1 — zone-only gating + start-ignore
    var ignoredCount by remember { mutableStateOf(0) }
    var startIgnoreUntilMs by remember { mutableStateOf(0L) }
    // B4 — lighting chip (DIM / ROOM / BRIGHT), persisted
    var lightingLabel by remember { mutableStateOf(settings.lightingLabel) }
    // D3 — score threshold and cooldown — Panel sliders (runtime-tunable without rebuild)
    var scoreThresholdState by remember { mutableStateOf(settings.scoreThreshold) }
    var cooldownMsState by remember { mutableStateOf(settings.cooldownMs.toFloat()) }
    // B3 — TRUTH mode toggle (default ON for this sprint; controls MISSED/PHANTOM visibility)
    var truthModeEnabled by remember { mutableStateOf(true) }
    // B3 — dump feedback shown in overlay
    var dumpMessage by remember { mutableStateOf("") }
    // B2 — ring buffer + raw JPEG pipe
    val frameRing = remember { FrameRing(capacityFrames = 60) }
    val latestRawJpeg = remember { AtomicReference<ByteArray>(ByteArray(0)) }
    // B3 — TruthWriter (created once; close() called in DisposableEffect onDispose)
    val truthWriter = remember(context) {
        TruthWriter(context, frameRing) { msg -> dumpMessage = msg }
    }

    // Camera permission
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

    DisposableEffect(lifecycleOwner) {
        onDispose { cameraXController.shutdown() }
    }

    // Restore persisted rects on first composition
    LaunchedEffect(Unit) {
        val saved = settings.targetRectsJson
        if (saved != null && targetRects.isEmpty()) {
            runCatching { TargetRect.listFromJson(saved) }
                .onSuccess { targetRects.addAll(it) }
        }
    }

    fun persistRects() {
        settings.targetRectsJson = if (targetRects.isEmpty()) null
                                   else TargetRect.listToJson(targetRects)
    }

    // D10 — feed the selected (or first) rect's ROI to the detector for auto-meter.
    LaunchedEffect(targetRects.size, selectedRectIdx) {
        val roi = when {
            targetRects.isEmpty() -> null
            selectedRectIdx in targetRects.indices -> targetRects[selectedRectIdx].toTargetRoi()
            else -> targetRects[0].toTargetRoi()
        }
        detector.targetRoi = roi
    }

    // B3.5 — apply AE metering region whenever rect selection or toggle changes.
    LaunchedEffect(targetRects.size, selectedRectIdx, meterToTargetEnabled) {
        if (!meterToTargetEnabled || targetRects.isEmpty()) {
            cameraXController.clearAeMeteringRect()
        } else {
            val rect = if (selectedRectIdx in targetRects.indices) targetRects[selectedRectIdx]
                       else targetRects[0]
            cameraXController.setAeMeteringRect(rect.left(), rect.top(), rect.right(), rect.bottom())
        }
    }

    fun beep(tone: Int, durationMs: Int) {
        if (!settings.soundEnabled) return
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(tone, durationMs) }
    }

    // ── Detection seam (CC-SIRT-TRUTH-MODE-001 B1+B2+B3) ─────────────────────
    DisposableEffect(Unit) {
        // B2 — feed JPEG pipe from analysis thread
        detector.onRawFrame = { jpeg, _ -> latestRawJpeg.set(jpeg) }

        detector.onDetection = { d ->
            liveDot = d
            val nowMs = SystemClock.elapsedRealtime()
            val jpeg = latestRawJpeg.get()

            // B2 — build FrameFeatures and push to ring for EVERY frame
            val aboveThreshold = d.peakScore >= CameraLaserDetector.SCORE_THRESHOLD
            // Zone + start-ignore verdict (only meaningful for isShot; defaults for non-shot frames)
            val inStartIgnore = d.isShot && nowMs < startIgnoreUntilMs
            val zoneRectIdx = if (d.isShot && !inStartIgnore)
                targetRects.indexOfFirst { it.contains(d.normX.toFloat(), d.normY.toFloat()) }
            else -1
            val counted = d.isShot && !inStartIgnore && zoneRectIdx >= 0
            val reason = when {
                !d.isShot      -> ""
                inStartIgnore  -> "START_IGNORE"
                zoneRectIdx < 0 -> "OUT_OF_ZONE"
                else           -> ""
            }

            val features = FrameFeatures(
                tsMs           = nowMs,
                score          = d.peakScore,
                yDelta         = d.yDelta,
                chromaDelta    = d.chromaDelta,
                normX          = d.normX,
                normY          = d.normY,
                isShot         = d.isShot,
                aboveThreshold = aboveThreshold,
                passNeighbor   = d.passNeighbor,
                passColor      = d.passColor,
                // Detector threshold constants (read-only, no control-flow change)
                scoreThreshold = CameraLaserDetector.SCORE_THRESHOLD,
                neighborFactor = CameraLaserDetector.NEIGHBOR_FACTOR,
                cbMax          = CameraLaserDetector.CB_MAX,
                crMax          = CameraLaserDetector.CR_MAX,
                chromaWeight   = CameraLaserDetector.CHROMA_WEIGHT,
                emaAlpha       = CameraLaserDetector.EMA_ALPHA,
                rectIdx        = zoneRectIdx,
                counted        = counted,
                reason         = reason,
                iso            = d.iso,
                shutterNs      = d.shutterNs,
                targetLuma     = d.roiLuma,
                exposureLocked = lockedExposureEnabled,
                lightingLabel  = lightingLabel,
            )
            frameRing.push(jpeg, features)

            // B1 — zone-only gating: handle isShot events
            if (d.isShot) {
                if (!counted) {
                    // B1 — ignored: update tally
                    ignoredCount++
                }

                // B3 — log every isShot event to events.jsonl
                val gateStr = "V${if (aboveThreshold) "✓" else "✗"} " +
                    "H${if (d.passColor) "✓" else "✗"} " +
                    "CMPCT${if (d.passNeighbor) "✓" else "✗"}"
                truthWriter.logEvent(
                    tsMs    = nowMs,
                    nx      = d.normX,
                    ny      = d.normY,
                    rectIdx = zoneRectIdx,
                    counted = counted,
                    reason  = reason,
                    score   = d.peakScore,
                    gates   = gateStr,
                    lighting = lightingLabel,
                )

                // B1 — only count as a real hit if it passed all gates
                if (counted) {
                    engine.recordHit()?.let { shot ->
                        shots.add(shot)
                        hitMarkers.add(HitMarker(shot.number, d.normX.toFloat(), d.normY.toFloat()))
                    }
                }
            }
        }
        onDispose {
            detector.stop()
            truthWriter.close()
        }
    }

    // Par-time watchdog
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

    // Auto-clear dump message after 3 s
    LaunchedEffect(dumpMessage) {
        if (dumpMessage.isNotEmpty()) {
            delay(3000)
            dumpMessage = ""
        }
    }

    fun beginCountdown() {
        engine.reset()
        engine.beginCountdown()
        sessionState = engine.state
        shots.clear()
        hitMarkers.clear()
        liveDot = null
        parFiredThisRun = false
        ignoredCount = 0        // B1 — reset ignored tally for new session
        startIgnoreUntilMs = 0L // will be set after GO
        statusText = "Get ready…"
        scope.launch {
            delay(settings.randomStartDelayMs())
            engine.go()
            // B1 — 1 s start-ignore: reject detections for the first 1000 ms after GO
            startIgnoreUntilMs = SystemClock.elapsedRealtime() + 1000L
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

    // Helper: build a DumpMeta from current app state
    fun makeDumpMeta(): TruthWriter.DumpMeta {
        val d = liveDot
        return TruthWriter.DumpMeta(
            tsMs            = SystemClock.elapsedRealtime(),
            rects           = targetRects.toList(),
            iso             = d?.iso ?: 0,
            shutterNs       = d?.shutterNs ?: 0L,
            targetLuma      = d?.roiLuma ?: 0f,
            exposureLocked  = lockedExposureEnabled,
            lighting        = lightingLabel,
            appVersionName  = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?"),
            headHash        = "d47cf0c",  // HEAD at v0.3-truth install; updated per commit
        )
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
            TargetSelectionPanel(
                mode = targetMode,
                onModeChange = { targetMode = it },
                rectCount = targetRects.size,
                onClearAll = {
                    targetRects.clear()
                    selectedRectIdx = -1
                    persistRects()
                },
            )

            Spacer(Modifier.height(4.dp))

            // ── Camera + hit-marker overlay ───────────────────────────────────
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clipToBounds()
                        .pointerInput(targetMode, targetRects.size) {
                            if (targetMode != TargetMode.DRAW) return@pointerInput
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressMs = 500L

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startPos = down.position
                                down.consume()

                                var currentPos = startPos
                                var isDrag = false

                                // Race: long-press vs first significant move
                                val gestureKind = withTimeoutOrNull(longPressMs) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: return@withTimeoutOrNull "up"
                                        currentPos = change.position
                                        val d = change.position - startPos
                                        if (sqrt(d.x * d.x + d.y * d.y) > touchSlop) {
                                            change.consume()
                                            return@withTimeoutOrNull "drag"
                                        }
                                        if (!change.pressed) return@withTimeoutOrNull "tap"
                                    }
                                    "unreachable"
                                }

                                when {
                                    // Long press → delete rect under the touch point
                                    gestureKind == null -> {
                                        val nx = startPos.x / size.width.toFloat()
                                        val ny = startPos.y / size.height.toFloat()
                                        val idx = targetRects.indexOfFirst { it.contains(nx, ny) }
                                        if (idx >= 0) {
                                            targetRects.removeAt(idx)
                                            if (selectedRectIdx == idx) selectedRectIdx = -1
                                            else if (selectedRectIdx > idx) selectedRectIdx--
                                            persistRects()
                                        }
                                        // consume remaining events until finger up
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            if (ev.changes.all { !it.pressed }) break
                                        }
                                    }

                                    // Tap → select rect under touch, or deselect
                                    gestureKind == "tap" -> {
                                        val nx = startPos.x / size.width.toFloat()
                                        val ny = startPos.y / size.height.toFloat()
                                        val idx = targetRects.indexOfFirst { it.contains(nx, ny) }
                                        selectedRectIdx = idx
                                    }

                                    // Drag → draw new rect (if under limit)
                                    else -> {
                                        isDrag = true
                                        liveDragStart = startPos
                                        liveDragEnd = currentPos

                                        // Track drag until finger up
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: break
                                            change.consume()
                                            currentPos = change.position
                                            liveDragEnd = currentPos
                                            if (!change.pressed) break
                                        }

                                        liveDragStart = null
                                        liveDragEnd = null

                                        // Finalize: build TargetRect from drag extents
                                        val x1 = minOf(startPos.x, currentPos.x) / size.width.toFloat()
                                        val y1 = minOf(startPos.y, currentPos.y) / size.height.toFloat()
                                        val x2 = maxOf(startPos.x, currentPos.x) / size.width.toFloat()
                                        val y2 = maxOf(startPos.y, currentPos.y) / size.height.toFloat()
                                        val halfW = (x2 - x1) / 2f
                                        val halfH = (y2 - y1) / 2f
                                        if (halfW >= TargetRect.MIN_HALF && halfH >= TargetRect.MIN_HALF
                                            && targetRects.size < TargetRect.MAX_RECTS) {
                                            val newRect = TargetRect(
                                                cx = x1 + halfW,
                                                cy = y1 + halfH,
                                                halfW = halfW,
                                                halfH = halfH,
                                            )
                                            targetRects.add(newRect)
                                            selectedRectIdx = targetRects.size - 1
                                            persistRects()
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    // Layer 1: Camera (TextureView via COMPATIBLE)
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                cameraXController.bind(lifecycleOwner, pv.surfaceProvider)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Layer 2: Target rects + live drag preview + live dot (Canvas)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Drawn rects with numbers rendered in the BoxWithConstraints layer below.
                        targetRects.forEachIndexed { idx, rect ->
                            val isSelected = idx == selectedRectIdx
                            drawRect(
                                color = if (isSelected) Color(0xFF58A6FF) else AccentGreen,
                                topLeft = Offset(rect.left() * size.width, rect.top() * size.height),
                                size = Size(rect.halfW * 2 * size.width, rect.halfH * 2 * size.height),
                                style = Stroke(width = if (isSelected) 4f else 3f),
                            )
                        }

                        // Live drag preview
                        liveDragStart?.let { s ->
                            liveDragEnd?.let { e ->
                                drawRect(
                                    color = Color(0x88FFFFFF),
                                    topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                                    size = Size(abs(e.x - s.x), abs(e.y - s.y)),
                                    style = Stroke(width = 2f),
                                )
                            }
                        }

                        // Live dot
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

                    // Layer 3: Rect numbers + hit markers as Compose composables (above TextureView)
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val panelW = maxWidth.value
                        val panelH = maxHeight.value

                        // Rect number labels (top-left corner of each rect, above the border)
                        targetRects.forEachIndexed { idx, rect ->
                            Box(
                                modifier = Modifier.offset(
                                    x = (rect.left() * panelW + 2f).dp,
                                    y = (rect.top() * panelH - 18f).coerceAtLeast(0f).dp,
                                ),
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = if (idx == selectedRectIdx) Color(0xFF58A6FF) else AccentGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // Hit markers — small 10 dp dots; number as tiny superscript
                        hitList.forEach { hit ->
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .offset(
                                        x = (hit.normX * panelW - 5f).dp,
                                        y = (hit.normY * panelH - 5f).dp,
                                    )
                                    .background(Color(0xFFDA3633), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${hit.number}",
                                    color = Color.White,
                                    fontSize = 6.sp,
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
            // B1 — pass ignoredCount to overlay; B4 — pass lightingLabel
            DetectorDiagOverlay(
                liveDot            = liveDot,
                aeRegionsSupported = cameraXController.aeRegionsSupported,
                meterToTargetEnabled = meterToTargetEnabled,
                ignoredCount       = ignoredCount,
                lightingLabel      = lightingLabel,
                dumpMessage        = dumpMessage,
            )
            Spacer(Modifier.height(8.dp))

            // ── Status line ──────────────────────────────────────────────────
            Text(statusText, color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))

            // ── Large last-split display ─────────────────────────────────────
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

            // ── Running shot list ─────────────────────────────────────────────
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shots) { shot -> ShotRow(shot) }
            }

            // ── Control buttons ───────────────────────────────────────────────
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

            // B3 — MISSED / PHANTOM thumb buttons (visible only when RUNNING + TRUTH mode ON)
            if (truthModeEnabled && sessionState == ShotTimerEngine.State.RUNNING) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { truthWriter.dumpMissed(makeDumpMeta()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("MISSED", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { truthWriter.dumpPhantom(makeDumpMeta()) },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("PHANTOM", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── Panel settings sheet ──────────────────────────────────────────────────
    if (panelOpen) {
        ModalBottomSheet(onDismissRequest = { panelOpen = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Session Panel", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

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
                    Text("Locked Exposure (Feature 22)")
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = autoMeterEnabled,
                        enabled = lockedExposureEnabled,
                        onCheckedChange = {
                            autoMeterEnabled = it
                            settings.exposureAutoMeterEnabled = it
                        },
                    )
                    Column {
                        Text("Auto-meter target region (D10)")
                        Text(
                            "Holds the paper at ~luma ${settings.exposureTargetLuma} so the green dot isn't drowned",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // B3.5 — Meter to target toggle
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = meterToTargetEnabled,
                        onCheckedChange = {
                            meterToTargetEnabled = it
                            settings.meterToTargetEnabled = it
                        },
                    )
                    Column {
                        Text("Meter to target (B3.5)")
                        Text(
                            when (cameraXController.aeRegionsSupported) {
                                false -> "AE regions not supported on this phone — using D10 fallback"
                                true  -> "Camera AE regions active on drawn rect"
                                null  -> "Camera not yet bound"
                            },
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // CC-SIRT-TRUTH-MODE-001 B3 — TRUTH mode toggle
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = truthModeEnabled,
                        onCheckedChange = { truthModeEnabled = it },
                    )
                    Column {
                        Text("TRUTH mode")
                        Text(
                            "Shows MISSED/PHANTOM buttons; dumps frames + features on press",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // CC-SIRT-TRUTH-MODE-001 B4 — Lighting chip
                Spacer(Modifier.height(16.dp))
                Text("Lighting", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DIM", "ROOM", "BRIGHT").forEach { label ->
                        FilterChip(
                            selected = lightingLabel == label,
                            onClick = {
                                lightingLabel = label
                                settings.lightingLabel = label
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Embedded in every features row and dump meta for the truth_report.py verdict table",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )

                // ── D3 score threshold + cooldown sliders ────────────────────────
                Spacer(Modifier.height(16.dp))
                Text("Detection Dials", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                Text(
                    "D3 score threshold: ${String.format("%.1f", scoreThresholdState)}  " +
                    "(default 16 — lower = more sensitive)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = scoreThresholdState,
                    onValueChange = { scoreThresholdState = it },
                    onValueChangeFinished = { settings.scoreThreshold = scoreThresholdState },
                    valueRange = 6f..24f,
                    steps = 35,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Cooldown: ${cooldownMsState.toInt()} ms  " +
                    "(min gap between shots — lower = faster splits)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = cooldownMsState,
                    onValueChange = { cooldownMsState = it },
                    onValueChangeFinished = { settings.cooldownMs = cooldownMsState.toInt() },
                    valueRange = 50f..1000f,
                    steps = 18,
                )

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { panelOpen = false }) { Text("Close") }
            }
        }
    }
}

/**
 * Per-frame detection diagnostic bar.
 *
 * B3.5 addition: shows AE metering region status (AE● = active, AE∅ = unsupported/off).
 * CC-SIRT-TRUTH-MODE-001 B1: shows `ignored: N` tally (out-of-zone + start-ignore events).
 * CC-SIRT-TRUTH-MODE-001 B3: shows dump feedback (`dumped ✓ <name>`) for 3 s.
 * CC-SIRT-TRUTH-MODE-001 B4: shows current lighting label chip.
 */
@Composable
private fun DetectorDiagOverlay(
    liveDot: Detection?,
    aeRegionsSupported: Boolean?,
    meterToTargetEnabled: Boolean,
    ignoredCount: Int,
    lightingLabel: String,
    dumpMessage: String,
) {
    val threshold = CameraLaserDetector.SCORE_THRESHOLD

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
            .background(Color(0xCC0A0F14))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Column {
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
                    if (d.roiLuma > 0f) {
                        val onTarget = kotlin.math.abs(d.roiLuma - 150f) <= 15f
                        Text(
                            text = "luma=${"%.0f".format(d.roiLuma)} " +
                                "${"%.1f".format(d.shutterNs / 1_000_000.0)}ms/ISO${d.iso}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (onTarget) AccentGreen else Amber,
                        )
                    }
                    // B3.5 — AE region status indicator
                    if (meterToTargetEnabled) {
                        Text(
                            text = when (aeRegionsSupported) {
                                true  -> "AE●"
                                false -> "AE∅"
                                null  -> "AE?"
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (aeRegionsSupported) {
                                true  -> AccentGreen
                                false -> Amber
                                null  -> Muted
                            },
                        )
                    }
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

                // B1 — ignored tally (always visible once non-zero)
                if (ignoredCount > 0) {
                    Text(
                        text = "ignored:$ignoredCount",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }

                // B4 — lighting label
                Text(
                    text = lightingLabel,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            // B3 — dump feedback line
            if (dumpMessage.isNotEmpty()) {
                Text(
                    text = "dumped ✓ $dumpMessage",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen,
                )
            }
        }
    }
}

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
