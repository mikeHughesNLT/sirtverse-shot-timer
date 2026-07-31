package com.sirtverse.shottimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sirtverse.detectioncore.CameraLaserDetector
import com.sirtverse.detectioncore.CameraXController
import com.sirtverse.detectioncore.Detection
import com.sirtverse.detectioncore.LaserDetector
import com.sirtverse.shottimer.databinding.ActivityShotTimerBinding
import com.sirtverse.shottimer.domain.shottimer.Shot
import com.sirtverse.shottimer.domain.shottimer.ShotTimerEngine
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionJson
import com.sirtverse.shottimer.storage.SettingsStore

/**
 * Milestone 3+ — [CameraLaserDetector] from :detection-core behind the [LaserDetector] seam.
 * Subscribes to the [Detection] stream via [LaserDetector.onDetection]; acts on
 * [Detection.isShot] for timer events; feeds the debug overlay from [Detection.peakScore].
 *
 * M1 regression: Start → hits → splits → save → history all pass.
 */
class ShotTimerActivity : AppCompatActivity() {

    private lateinit var b: ActivityShotTimerBinding
    private val engine = ShotTimerEngine()
    private lateinit var settings: SettingsStore

    private lateinit var cameraController: CameraXController
    private lateinit var cameraDetector: CameraLaserDetector
    private val detector: LaserDetector get() = cameraDetector

    private val handler = Handler(Looper.getMainLooper())
    private var pendingGo: Runnable? = null
    private var tone: ToneGenerator? = null

    // Detection stream state (main thread only — onDetection dispatched via mainHandler)
    private var lastDetection: Detection? = null
    private var detectionFrames = 0L

    // Debug overlay tracking
    private var overlayWindowStartMs = 0L
    private var overlayLastFrames    = 0L

    private val debugOverlayUpdater = object : Runnable {
        override fun run() {
            if (settings.labModeEnabled) {
                val now     = System.currentTimeMillis()
                val elapsed = (now - overlayWindowStartMs).coerceAtLeast(1)
                val frames  = detectionFrames - overlayLastFrames
                val fps     = frames * 1000.0 / elapsed
                overlayLastFrames    = detectionFrames
                overlayWindowStartMs = now
                val score = lastDetection?.peakScore ?: 0f
                b.txtDebugOverlay.text = "%.1f fps  score=%.1f".format(fps, score)
                b.txtDebugOverlay.visibility = View.VISIBLE
                Log.d("FrameTap", "%.1f fps  score=%.1f".format(fps, score))
            } else {
                b.txtDebugOverlay.visibility = View.GONE
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraPreview() else showCameraOff()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityShotTimerBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = SettingsStore(this)

        cameraController = CameraXController(this)
        cameraDetector   = CameraLaserDetector(cameraController, this, SettingsStoreDetectionConfig(settings))

        detector.onDetection = { d ->
            lastDetection = d
            detectionFrames++
            if (d.isShot) onShotRegistered()
        }

        b.btnStart.setOnClickListener { beginCountdown() }

        // Simulate button: synthesise an isShot Detection on the main thread.
        b.btnSimulateHit.setOnClickListener {
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
        }

        b.btnEnd.setOnClickListener { endSession() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        overlayWindowStartMs = System.currentTimeMillis()
        handler.post(debugOverlayUpdater)
    }

    private fun startCameraPreview() {
        b.cameraScrim.visibility  = View.VISIBLE
        b.txtCameraOff.visibility = View.GONE
        cameraController.bind(this, b.previewView.surfaceProvider)
    }

    private fun showCameraOff() {
        b.cameraScrim.visibility  = View.GONE
        b.txtCameraOff.visibility = View.VISIBLE
    }

    private fun beginCountdown() {
        engine.reset()
        engine.beginCountdown()
        b.listShots.removeAllViews()
        b.txtFirstShot.text = "—"
        b.txtStatus.text = getString(R.string.get_ready)
        b.btnStart.isEnabled       = false
        b.btnSimulateHit.isEnabled = false
        b.btnEnd.isEnabled         = true

        val delay = settings.randomStartDelayMs()
        pendingGo = Runnable { go() }.also { handler.postDelayed(it, delay) }
    }

    private fun go() {
        engine.go()
        detector.start()
        beep()
        b.txtStatus.text = getString(R.string.go)
        b.btnSimulateHit.isEnabled = true
    }

    private fun onShotRegistered() {
        val shot = engine.recordHit() ?: return
        if (shot.number == 1) {
            b.txtFirstShot.text = TimeFmt.seconds(shot.timeMs)
        }
        addShotRow(shot)
        b.scrollShots.post { b.scrollShots.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun endSession() {
        pendingGo?.let { handler.removeCallbacks(it) }
        pendingGo = null
        detector.stop()
        b.txtStatus.text = getString(R.string.standby)
        b.btnStart.isEnabled       = true
        b.btnSimulateHit.isEnabled = false
        b.btnEnd.isEnabled         = false

        val session = engine.end()
        startActivity(
            Intent(this, SessionResultsActivity::class.java)
                .putExtra(SessionResultsActivity.EXTRA_SESSION, SessionJson.encode(session))
        )
    }

    private fun addShotRow(shot: Shot) {
        val label = if (shot.number == 1)
            "#1   ${TimeFmt.seconds(shot.timeMs)}   (first shot)"
        else
            "#${shot.number}   ${TimeFmt.seconds(shot.timeMs)}   +${TimeFmt.secondsBare(shot.splitMs)}s"
        b.listShots.addView(TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(getColor(R.color.on_surface))
            setPadding(0, 8, 0, 8)
        })
    }

    private fun beep() {
        if (!settings.soundEnabled) return
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 200)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingGo?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(debugOverlayUpdater)
        tone?.release()
        tone = null
        cameraController.shutdown()
    }
}
