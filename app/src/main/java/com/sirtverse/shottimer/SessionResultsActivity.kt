package com.sirtverse.shottimer

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.databinding.ActivityResultsBinding
import com.sirtverse.shottimer.domain.shottimer.SplitCalculator
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionJson
import com.sirtverse.shottimer.storage.SessionStorage

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

        b.btnSave.setOnClickListener {
            val saved = session.copy(notes = b.editNotes.text.toString().trim())
            storage.save(saved)
            finish()
        }
        b.btnDiscard.setOnClickListener { finish() }
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
