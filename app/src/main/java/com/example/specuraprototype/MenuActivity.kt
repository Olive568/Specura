package com.example.specuraprototype

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
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

    override fun onResume() {
        super.onResume()
        loadRecentPhotosFromDatabase()
    }

    private fun loadRecentPhotosFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Fetch all scans from Room off the Main Thread
            val allScans = try {
                db.scanDao().getAllScans()
            } catch (e: Exception) {
                emptyList()
            }
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
                                val intent = Intent(this@MenuActivity, ResultActivity::class.java).apply {
                                    putExtra("imageUri", imageUriStr)
                                    putExtra("locationTag", scan.location)
                                    putExtra("material", data["material"] as? String)
                                    putExtra("damage", data["damage"] as? String)
                                    putExtra("confidence", (data["confidence"] as? Double)?.toFloat() ?: 0f)
                                    putExtra("prompt", data["prompt"] as? String ?: "")
                                    putExtra("damageSignal", (data["E"] as? Double)?.toFloat() ?: 0f)
                                    putExtra("severityScore", (data["H"] as? Double)?.toFloat() ?: 0f)
                                    putExtra("severityLabel", data["severity"] as? String)
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
