package com.sirtverse.shottimer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sirtverse.shottimer.databinding.ActivitySettingsBinding
import com.sirtverse.shottimer.storage.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = SettingsStore(this)

        // Load current values
        b.editDelayMin.setText(settings.startDelayMinMs.toString())
        b.editDelayMax.setText(settings.startDelayMaxMs.toString())
        b.editCooldown.setText(settings.cooldownMs.toString())
        b.checkSound.isChecked = settings.soundEnabled
        when (settings.laserColor) {
            SettingsStore.LaserColor.RED -> b.colorRed.isChecked = true
            SettingsStore.LaserColor.GREEN -> b.colorGreen.isChecked = true
            SettingsStore.LaserColor.BOTH -> b.colorBoth.isChecked = true
        }

        b.btnSaveSettings.setOnClickListener { save() }
    }

    private fun save() {
        val min = b.editDelayMin.text.toString().toIntOrNull() ?: 3000
        val max = b.editDelayMax.text.toString().toIntOrNull() ?: 5000
        settings.startDelayMinMs = min.coerceAtLeast(0)
        settings.startDelayMaxMs = max.coerceAtLeast(min)
        settings.cooldownMs = b.editCooldown.text.toString().toIntOrNull() ?: 120
        settings.soundEnabled = b.checkSound.isChecked
        settings.laserColor = when {
            b.colorRed.isChecked -> SettingsStore.LaserColor.RED
            b.colorBoth.isChecked -> SettingsStore.LaserColor.BOTH
            else -> SettingsStore.LaserColor.GREEN
        }
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
