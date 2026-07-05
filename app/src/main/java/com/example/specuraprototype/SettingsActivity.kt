package com.example.specuraprototype

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        // Edge-to-edge padding (your XML has android:id="@+id/main")
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back button (top-left)
        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener {
            finish()
        }

        // Bottom nav wiring
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // If you're already in Settings, do nothing (or you can refresh)
        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            // no-op
        }

        // Capture and AI controls
        val shutterSoundSwitch = findViewById<SwitchMaterial>(R.id.swShutterSound)
        shutterSoundSwitch.isChecked = AppSettings.isShutterSoundEnabled(this)
        shutterSoundSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setShutterSoundEnabled(this, isChecked)
        }

        val defectThresholdGroup = findViewById<MaterialButtonToggleGroup>(R.id.tgDefectThreshold)
        defectThresholdGroup.check(
            when (AppSettings.getDefectThresholdPreset(this)) {
                AppSettings.PRESET_LOW -> R.id.btnDefectLow
                AppSettings.PRESET_HIGH -> R.id.btnDefectHigh
                else -> R.id.btnDefectMedium
            }
        )
        defectThresholdGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            AppSettings.setDefectThresholdPreset(
                this,
                when (checkedId) {
                    R.id.btnDefectLow -> AppSettings.PRESET_LOW
                    R.id.btnDefectHigh -> AppSettings.PRESET_HIGH
                    else -> AppSettings.PRESET_MEDIUM
                }
            )
        }

        val lightingRobustnessGroup = findViewById<MaterialButtonToggleGroup>(R.id.tgLightingRobustness)
        lightingRobustnessGroup.check(
            when (AppSettings.getLightingRobustnessPreset(this)) {
                AppSettings.PRESET_LOW -> R.id.btnLightingLow
                AppSettings.PRESET_HIGH -> R.id.btnLightingHigh
                else -> R.id.btnLightingMedium
            }
        )
        lightingRobustnessGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            AppSettings.setLightingRobustnessPreset(
                this,
                when (checkedId) {
                    R.id.btnLightingLow -> AppSettings.PRESET_LOW
                    R.id.btnLightingHigh -> AppSettings.PRESET_HIGH
                    else -> AppSettings.PRESET_MEDIUM
                }
            )
        }

        // About popup
        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            showAboutSpecuraPopup()
        }
    }

    private fun showAboutSpecuraPopup() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about_specura, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.close) { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()

        dialog.setCanceledOnTouchOutside(true)
    }
}
