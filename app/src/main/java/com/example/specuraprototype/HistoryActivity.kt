package com.example.specuraprototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

    private lateinit var db: AppDatabase
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)

        db = AppDatabase.getDatabase(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back -> Menu
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Bottom nav
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            // Already here
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }

        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadHistoryFromDatabase()
    }

    override fun onResume() {
        super.onResume()
        loadHistoryFromDatabase()
    }

    private fun loadHistoryFromDatabase() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        container.removeAllViews()

        // Fetch all scans from Room database
        val scans = db.scanDao().getAllScans()

        if (scans.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No history yet. Take a photo first."
                textSize = 16f
                setPadding(0, 24, 0, 0)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(empty)
            return
        }

        for (scan in scans) {
            // Parse JSON data stored in Room
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(scan.jsonData, type)

            val entry = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 32)
                background = getDrawable(android.R.drawable.dialog_holo_light_frame)
            }

            // Image
            val imageUri = data["imageUri"] as? String
            if (imageUri != null) {
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        600
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(imageUri))
                    setPadding(0, 0, 0, 8)
                }
                entry.addView(image)
            }

            val locationText = TextView(this).apply {
                text = "Location: ${scan.location}"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val materialText = TextView(this).apply {
                text = "Material: ${data["material"] ?: "Unknown"}"
                textSize = 16f
            }

            val damageText = TextView(this).apply {
                text = "Damage: ${data["damage"] ?: "Unknown"} (${data["severity"] ?: ""})"
                textSize = 14f
            }

            val dateText = TextView(this).apply {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                text = sdf.format(java.util.Date(scan.timestamp))
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }

            entry.addView(locationText)
            entry.addView(materialText)
            entry.addView(damageText)
            entry.addView(dateText)
            container.addView(entry)
        }
    }
}
