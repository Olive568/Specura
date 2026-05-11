package com.example.specuraprototype

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

        // Switches (mock behavior for now)
        findViewById<SwitchMaterial>(R.id.swAutoSave).setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Auto-save: $isChecked", Toast.LENGTH_SHORT).show()
        }

        findViewById<SwitchMaterial>(R.id.swShutterSound).setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Shutter sound: $isChecked", Toast.LENGTH_SHORT).show()
        }

        findViewById<SwitchMaterial>(R.id.swSaveHistory).setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Save to history: $isChecked", Toast.LENGTH_SHORT).show()
        }

        findViewById<SwitchMaterial>(R.id.swShowConfidence).setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, "Show confidence: $isChecked", Toast.LENGTH_SHORT).show()
        }

        // Buttons (mock actions)
        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            Toast.makeText(this, "About SPECURA (mock)", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnHelp).setOnClickListener {
            Toast.makeText(this, "Help / How to Use (mock)", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            Toast.makeText(this, "History cleared (mock)", Toast.LENGTH_SHORT).show()
        }
    }
}
