package com.sirtverse.shottimer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.airframe.AirframeActivity
import com.sirtverse.shottimer.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStartTimer.setOnClickListener {
            startActivity(Intent(this, ShotTimerActivity::class.java))
        }
        b.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnAirframeDemo.setOnClickListener {
            startActivity(Intent(this, AirframeActivity::class.java))
        }
    }
}
