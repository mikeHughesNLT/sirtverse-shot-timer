package com.sirtverse.shottimer

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.databinding.ActivityShotTimerBinding
import com.sirtverse.shottimer.domain.detection.MockLaserDetector
import com.sirtverse.shottimer.domain.shottimer.Shot
import com.sirtverse.shottimer.domain.shottimer.ShotTimerEngine
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionJson
import com.sirtverse.shottimer.storage.SettingsStore

/**
 * Milestone 1 core screen. Drives the [ShotTimerEngine] through its lifecycle and
 * feeds it hits from the [MockLaserDetector] ("Simulate Laser Hit" button).
 *
 * At Milestone 3 the only change here is swapping MockLaserDetector for a
 * camera-backed LaserDetector — the engine wiring and UI stay identical.
 */
class ShotTimerActivity : AppCompatActivity() {

    private lateinit var b: ActivityShotTimerBinding
    private val engine = ShotTimerEngine()
    private val detector = MockLaserDetector()
    private lateinit var settings: SettingsStore

    private val handler = Handler(Looper.getMainLooper())
    private var pendingGo: Runnable? = null
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityShotTimerBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = SettingsStore(this)

        // Every registered hit flows through here — mock now, camera later.
        detector.onHit = { onShotRegistered() }

        b.btnStart.setOnClickListener { beginCountdown() }
        b.btnSimulateHit.setOnClickListener { detector.simulateHit() }
        b.btnEnd.setOnClickListener { endSession() }
    }

    // ── Lifecycle of one run ────────────────────────────────────────────────

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
        tone?.release()
        tone = null
    }
}
