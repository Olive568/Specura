package com.example.specuraprototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back -> Menu (not just finish)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            startActivity(Intent(this, MenuActivity::class.java))
            finish()
        }

        // Bottom nav
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            // already here (no-op)
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadHistoryIntoUI()
    }

    override fun onResume() {
        super.onResume()
        // Refresh history when returning to this screen
        loadHistoryIntoUI()
    }

    private fun loadHistoryIntoUI() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        container.removeAllViews()

        val prefs = getSharedPreferences("specura_history", MODE_PRIVATE)
        val json = prefs.getString("items", null)

        if (json.isNullOrBlank()) {
            val empty = TextView(this).apply {
                text = "No history yet. Take a photo first."
                textSize = 14f
            }
            container.addView(empty)
            return
        }

        val type = object : TypeToken<List<HistoryItem>>() {}.type
        val list: List<HistoryItem> = Gson().fromJson(json, type)

        for (item in list) {
            val entry = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 24)
            }

            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    420
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(Uri.parse(item.imageUri))
            }

            val text = TextView(this).apply {
                text = item.detectedMaterial
                textSize = 14f
                setPadding(0, 10, 0, 0)
            }

            entry.addView(image)
            entry.addView(text)
            container.addView(entry)
        }
    }
}
