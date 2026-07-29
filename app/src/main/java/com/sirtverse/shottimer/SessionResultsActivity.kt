package com.sirtverse.shottimer

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.databinding.ActivityResultsBinding
import com.sirtverse.shottimer.domain.shottimer.SplitCalculator
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionJson
import com.sirtverse.shottimer.storage.SessionStorage
import com.sirtverse.shottimer.storage.SettingsStore

class SessionResultsActivity : AppCompatActivity() {

    private lateinit var b: ActivityResultsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val json = intent.getStringExtra(EXTRA_SESSION)
        if (json == null) { finish(); return }
        val session = SessionJson.decode(json)
        val storage = SessionStorage(this)
        val settings = SettingsStore(this)

        b.txtSummary.text = buildString {
            appendLine("First shot:   ${TimeFmt.seconds(session.firstShotMs)}")
            appendLine("Total shots:  ${session.shotCount}")
            appendLine("Avg split:    ${TimeFmt.seconds(session.averageSplitMs)}")
            appendLine("Best split:   ${TimeFmt.seconds(session.bestSplitMs)}")
            append("Duration:     ${TimeFmt.seconds(session.durationMs)}")
        }

        val splits = SplitCalculator.splits(session.shots)
        if (splits.isEmpty()) {
            addSplitRow("No splits — fewer than two shots.")
        } else {
            session.shots.drop(1).forEach { shot ->
                addSplitRow("#${shot.number - 1}→#${shot.number}   +${TimeFmt.secondsBare(shot.splitMs)}s")
            }
        }

        if (settings.labModeEnabled) {
            b.txtLabSummary.visibility = View.VISIBLE
            b.txtLabSummary.text = buildLabSummary(session.startedAtEpochMs, session.durationMs)
        }

        b.btnSave.setOnClickListener {
            val saved = session.copy(notes = b.editNotes.text.toString().trim())
            storage.save(saved)
            finish()
        }
        b.btnDiscard.setOnClickListener { finish() }
    }

    private fun buildLabSummary(endEpochMs: Long, durationMs: Double): String {
        val dir = getExternalFilesDir("detections") ?: return "[Lab] JSONL: not found"
        // JSONL filename epoch ≈ session go-time; endEpochMs ≈ go-time + durationMs
        val startApprox = endEpochMs - durationMs.toLong()
        val file = dir.listFiles { f -> f.extension == "jsonl" }
            ?.filter { f ->
                val epoch = f.nameWithoutExtension.removePrefix("M3-").toLongOrNull()
                    ?: return@filter false
                kotlin.math.abs(epoch - startApprox) < 10_000L
            }
            ?.maxByOrNull { it.lastModified() }
            ?: return "[Lab] JSONL: not found"
        var hitCount = 0
        var maxFrame = 0L
        file.forEachLine { line ->
            if (line.contains(""""type":"hit"""")) hitCount++
            Regex(""""frame":(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { if (it > maxFrame) maxFrame = it }
        }
        val fps = if (durationMs > 0) (maxFrame / (durationMs / 1000.0)).toInt() else 0
        return "[Lab] JSONL: $hitCount hits logged  (~$fps fps)"
    }

    private fun addSplitRow(text: String) {
        b.listSplits.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(getColor(R.color.on_surface))
            setPadding(0, 6, 0, 6)
        })
    }

    companion object {
        const val EXTRA_SESSION = "session_json"
    }
}
