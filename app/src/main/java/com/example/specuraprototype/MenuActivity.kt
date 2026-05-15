package com.example.specuraprototype

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_menu)

        db = AppDatabase.getDatabase(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Help button: Quick Tutorial
        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            showQuickTutorial()
        }

        // Bottom nav: History
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Bottom nav: Camera
        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // Bottom nav: Settings
        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadRecentPhotosFromDatabase()
    }

    private fun showQuickTutorial() {
        // Using androidx.appcompat.app.AlertDialog to avoid Material theme crash issues
        AlertDialog.Builder(this)
            .setTitle(R.string.tutorial_title)
            .setMessage(R.string.tutorial_content)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadRecentPhotosFromDatabase()
    }

    private fun loadRecentPhotosFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@MenuActivity)
            val allScans = db.scanDao().getAllScans() // Get data on Background Thread

            val latestScans = allScans.take(6)

            withContext(Dispatchers.Main) {
                val slots = listOf(
                    findViewById<ImageView>(R.id.imgRecent1),
                    findViewById<ImageView>(R.id.imgRecent2),
                    findViewById<ImageView>(R.id.imgRecent3),
                    findViewById<ImageView>(R.id.imgRecent4),
                    findViewById<ImageView>(R.id.imgRecent5),
                    findViewById<ImageView>(R.id.imgRecent6),
                )

                // Clear all slots first
                for (img in slots) {
                    img.setImageDrawable(null)
                    img.setOnClickListener(null)
                }

                // 2. Fill with images using Coil for better memory management
                for (i in latestScans.indices) {
                    val scan = latestScans[i]
                    val type = object : TypeToken<Map<String, Any>>() {}.type

                    try {
                        val data: Map<String, Any> = gson.fromJson(scan.jsonData, type)
                        val imageUriStr = data["imageUri"] as? String

                        if (imageUriStr != null) {
                            // Using Coil (.load) instead of .setImageURI for safer memory management
                            slots[i].load(imageUriStr) {
                                crossfade(true)
                                placeholder(android.R.drawable.ic_menu_gallery)
                                error(android.R.drawable.ic_menu_report_image)
                            }

                            slots[i].setOnClickListener {
                                val intent = Intent(this@MenuActivity, HistoryActivity::class.java).apply {
                                    putExtra(HistoryActivity.EXTRA_FOCUS_LOCATION, scan.location)
                                    putExtra(HistoryActivity.EXTRA_FOCUS_SCAN_ID, scan.id)
                                }
                                startActivity(intent)
                            }
                        }
                    } catch (e: Exception) {
                        // Safety net for missing or corrupt data
                        slots[i].setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                }
            }
        }
    }
}
