package com.sirtverse.shottimer

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.databinding.ActivityHistoryBinding
import com.sirtverse.shottimer.domain.shottimer.TimeFmt
import com.sirtverse.shottimer.storage.SessionStorage

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        val sessions = SessionStorage(this).loadAll()
        b.txtEmpty.visibility = if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        sessions.forEach { s ->
            val card = TextView(this).apply {
                text = buildString {
                    appendLine(TimeFmt.dateTime(s.startedAtEpochMs))
                    append("${s.shotCount} shots · first ${TimeFmt.seconds(s.firstShotMs)} · avg split ${TimeFmt.seconds(s.averageSplitMs)}")
                    if (s.notes.isNotBlank()) append("\n\u201C${s.notes}\u201D")
                }
                textSize = 15f
                setTextColor(getColor(R.color.on_surface))
                setBackgroundColor(getColor(R.color.surface))
                setPadding(28, 28, 28, 28)
            }
            b.listSessions.addView(card)
            b.listSessions.addView(android.view.View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 16
                )
            })
        }
    }
}
