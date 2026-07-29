package com.sirtverse.shottimer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sirtverse.shottimer.databinding.ActivitySettingsBinding
import com.sirtverse.shottimer.storage.SettingsStore
import java.io.File

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
        b.checkLabMode.isChecked = settings.labModeEnabled
        b.btnExportLogs.visibility = if (settings.labModeEnabled) View.VISIBLE else View.GONE
        b.btnExportLogs.setOnClickListener { exportLogs() }
        when (settings.laserColor) {
            SettingsStore.LaserColor.RED -> b.colorRed.isChecked = true
            SettingsStore.LaserColor.GREEN -> b.colorGreen.isChecked = true
            SettingsStore.LaserColor.BOTH -> b.colorBoth.isChecked = true
        }

        b.btnSaveSettings.setOnClickListener { save() }
    }

    private fun exportLogs() {
        val dir = getExternalFilesDir("detections")
        val files = dir?.listFiles { f -> f.extension == "jsonl" }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "No detection logs found.", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            uris.add(FileProvider.getUriForFile(this, "com.sirtverse.shottimer.fileprovider", file))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export detection logs"))
    }

    private fun save() {
        val min = b.editDelayMin.text.toString().toIntOrNull() ?: 3000
        val max = b.editDelayMax.text.toString().toIntOrNull() ?: 5000
        settings.startDelayMinMs = min.coerceAtLeast(0)
        settings.startDelayMaxMs = max.coerceAtLeast(min)
        settings.cooldownMs = b.editCooldown.text.toString().toIntOrNull() ?: 120
        settings.soundEnabled = b.checkSound.isChecked
        settings.labModeEnabled = b.checkLabMode.isChecked
        settings.laserColor = when {
            b.colorRed.isChecked -> SettingsStore.LaserColor.RED
            b.colorBoth.isChecked -> SettingsStore.LaserColor.BOTH
            else -> SettingsStore.LaserColor.GREEN
        }
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
