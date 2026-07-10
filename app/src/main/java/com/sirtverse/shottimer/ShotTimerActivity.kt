package com.sirtverse.shottimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.sirtverse.shottimer.databinding.ActivityShotTimerBinding
import com.sirtverse.shottimer.domain.detection.MockLaserDetector
import com.sirtverse.shottimer.domain.shottimer.Shot
import com.sirtverse.shottimer.domain.shottimer.ShotTimerEngine
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionJson
import com.sirtverse.shottimer.storage.SettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Milestone 2 — camera preview behind the timer UI + ImageAnalysis frame tap.
 *
 * M1 engine wiring, session save, and all widget IDs are unchanged.
 * At M3 the only further change here is swapping MockLaserDetector for
 * CameraLaserDetector — everything else (engine, session, camera lifecycle) stays.
 *
 * Camera path:
 *   Permission granted → startCameraPreview() → PreviewView + ImageAnalysis bound
 *   Permission denied  → showCameraOff()      → M1 dark background + notice label
 */
class ShotTimerActivity : AppCompatActivity() {

    private lateinit var b: ActivityShotTimerBinding
    private val engine = ShotTimerEngine()
    private val detector = MockLaserDetector()
    private lateinit var settings: SettingsStore

    private val handler = Handler(Looper.getMainLooper())
    private var pendingGo: Runnable? = null
    private var tone: ToneGenerator? = null

    // ── Camera / frame tap (M2) ─────────────────────────────────────────────

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val frameCount = AtomicLong(0L)
    private var fpsWindowStartMs = 0L

    /** Logs sustained fps every 2 s to Logcat tag "FrameTap". */
    private val fpsLogger = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val elapsedMs = (now - fpsWindowStartMs).coerceAtLeast(1L)
            val count = frameCount.getAndSet(0L)
            val fps = count * 1000.0 / elapsedMs
            Log.d("FrameTap", "%.1f fps".format(fps))
            fpsWindowStartMs = now
            handler.postDelayed(this, 2000L)
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

        // Every registered hit flows through here — mock now, camera-backed at M3.
        detector.onHit = { onShotRegistered() }

        b.btnStart.setOnClickListener { beginCountdown() }
        b.btnSimulateHit.setOnClickListener { detector.simulateHit() }
        b.btnEnd.setOnClickListener { endSession() }

        // Camera: check permission → start or request (launcher registered above, before onResume).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Camera setup (M2) ───────────────────────────────────────────────────

    private fun startCameraPreview() {
        b.cameraScrim.visibility = View.VISIBLE
        b.txtCameraOff.visibility = View.GONE

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = b.previewView.surfaceProvider
            }

            // Frame tap: counts frames, logs fps every 2 s. No image processing — M3 will add that.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        frameCount.incrementAndGet()
                        image.close()  // close immediately — no processing yet
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )

            fpsWindowStartMs = System.currentTimeMillis()
            handler.post(fpsLogger)

        }, ContextCompat.getMainExecutor(this))
    }

    /** Called when camera permission is denied. M1 behavior: dark bg, timer fully functional. */
    private fun showCameraOff() {
        b.cameraScrim.visibility = View.GONE
        b.txtCameraOff.visibility = View.VISIBLE
    }

    // ── Lifecycle of one run (M1 — unchanged) ───────────────────────────────

    private fun beginCountdown() {
        engine.reset()
        engine.beginCountdown()
        b.listShots.removeAllViews()
        b.txtFirstShot.text = "—"
        b.txtStatus.text = getString(R.string.get_ready)
        b.btnStart.isEnabled = false
        b.btnSimulateHit.isEnabled = false
        b.btnEnd.isEnabled = true

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
        b.btnStart.isEnabled = true
        b.btnSimulateHit.isEnabled = false
        b.btnEnd.isEnabled = false

        val session = engine.end()
        startActivity(
            Intent(this, SessionResultsActivity::class.java)
                .putExtra(SessionResultsActivity.EXTRA_SESSION, SessionJson.encode(session))
        )
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

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
        handler.removeCallbacks(fpsLogger)
        tone?.release()
        tone = null
        cameraExecutor.shutdown()
    }
}
