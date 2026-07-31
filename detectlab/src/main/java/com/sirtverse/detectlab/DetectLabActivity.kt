package com.sirtverse.detectlab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.CameraXController
import com.sirtverse.detectioncore.Detection
import com.sirtverse.detectlab.databinding.ActivityDetectLabBinding

/**
 * DetectLab — detection-only harness for rig scoring and live dot visualisation.
 *
 * Screen: full-bleed camera preview + [DetectionOverlayView] + monospace HUD.
 * No timer, no splits, no save — pure detection visibility.
 *
 * Detection start/stop is implicit on screen appear/disappear (onResume/onPause).
 * B-5: every isShot event is logged to logcat tag "DetectLab" for detectlab_referee.py.
 * B-2: live telemetry broadcast on UDP :9876; MARK frame-bundle receiver on UDP :9877.
 */
class DetectLabActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetectLabBinding
    private lateinit var cameraController: CameraXController
    private lateinit var detector: CameraLaserDetector
    private lateinit var labBroadcaster: LabBroadcaster
    private lateinit var markManager: MarkManager

    private var exposurePinned = true

    // HUD state (all on main thread)
    private var shotCount = 0
    private var frameCount = 0L
    private var lastHudFrames = 0L
    private var lastHudMs = 0L
    private var lastDetection: Detection? = null

    private val handler = Handler(Looper.getMainLooper())

    private val hudUpdater = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, 500L)
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else b.txtHud.text = "CAMERA PERMISSION DENIED"
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetectLabBinding.inflate(layoutInflater)
        setContentView(b.root)

        cameraController = CameraXController(this)
        detector = CameraLaserDetector(cameraController, this, InMemoryDetectionConfig())
        labBroadcaster = LabBroadcaster()
        markManager = MarkManager(this)

        detector.onDetection = { d -> onDetection(d) }

        b.btnExposureToggle.setOnClickListener { toggleExposure() }
        b.btnThresholdDown.setOnClickListener { nudgeThreshold(-1f) }
        b.btnThresholdUp.setOnClickListener { nudgeThreshold(+1f) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        markManager.start()
        lastHudMs = System.currentTimeMillis()
        handler.post(hudUpdater)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            detector.start()
            // Wrap frame listener: captureFrame before detector processes, so image is still open
            val detectorFrameListener = cameraController.frameListener
            cameraController.frameListener = { image ->
                markManager.captureFrame(image)
                detectorFrameListener?.invoke(image)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        detector.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(hudUpdater)
        cameraController.shutdown()
        labBroadcaster.close()
        markManager.stop()
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun startCamera() {
        cameraController.bind(this, b.previewView.surfaceProvider)
    }

    // ── Detection stream ──────────────────────────────────────────────────────

    private fun onDetection(d: Detection) {
        lastDetection = d
        frameCount++
        b.overlayView.updateDetection(d)
        markManager.annotate(d)
        labBroadcaster.send(d)

        if (d.isShot) {
            shotCount++
            // B-5: logcat line for detectlab_referee.py (adb logcat -s DetectLab)
            Log.i("DetectLab", "SHOT cell=${d.gridCell} score=${"%.1f".format(d.peakScore)} hit=1")
        }
    }

    private fun updateHud() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastHudMs).coerceAtLeast(1)
        val frames = frameCount - lastHudFrames
        val fps = frames * 1000.0 / elapsed
        lastHudFrames = frameCount
        lastHudMs = now

        val d = lastDetection
        val score = d?.peakScore ?: 0f
        val thr = CameraLaserDetector.SCORE_THRESHOLD
        val gate = when {
            d == null -> ""
            d.isShot  -> "OK"
            d.passColor -> "OK"
            d.passNeighbor -> "color-fail"
            d.peakScore >= thr -> "neighbor-fail"
            else -> ""
        }
        val expLabel = if (exposurePinned) "📌" else "AE"
        b.txtHud.text = "${"%.1f".format(fps)} fps  score=${"%.1f".format(score)}  " +
                "shots=$shotCount  thr=${"%.0f".format(thr)}  $gate  $expLabel"
        b.txtThreshold.text = "thr=${"%.0f".format(thr)}"
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun toggleExposure() {
        if (exposurePinned) {
            cameraController.setAutoExposure()
            exposurePinned = false
            b.btnExposureToggle.text = getString(R.string.btn_exposure_auto)
        } else {
            cameraController.setExposure(shutterNs = 16_000_000, iso = 800)
            exposurePinned = true
            b.btnExposureToggle.text = getString(R.string.btn_exposure_pinned)
        }
    }

    private fun nudgeThreshold(delta: Float) {
        CameraLaserDetector.SCORE_THRESHOLD =
            (CameraLaserDetector.SCORE_THRESHOLD + delta).coerceAtLeast(1f)
        b.txtThreshold.text = "thr=${"%.0f".format(CameraLaserDetector.SCORE_THRESHOLD)}"
        Log.i("DetectLab", "threshold nudged to ${CameraLaserDetector.SCORE_THRESHOLD}")
    }
}
