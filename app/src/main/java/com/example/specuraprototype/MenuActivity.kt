package com.example.specuraprototype

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.opencv.android.OpenCVLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()
    private val scanDataType = object : TypeToken<Map<String, Any>>() {}.type
    private lateinit var tvAiSystemStatus: TextView
    private lateinit var vAiSystemStatusDot: View
    private lateinit var aiSystemStatusContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_menu)

        db = AppDatabase.getDatabase(this)
        tvAiSystemStatus = findViewById(R.id.tvAiSystemStatus)
        vAiSystemStatusDot = findViewById(R.id.vAiSystemStatusDot)
        aiSystemStatusContainer = findViewById(R.id.layoutAiSystemStatus)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Back button: Go to Main (Splash)
        findViewById<ImageButton>(R.id.btnBackToMain).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // Bottom nav: History
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Bottom nav: Camera
        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            openCameraCapture()
        }

        // Bottom nav: Settings
        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadDashboardData()
    }

    override fun onResume() {
        super.onResume()
        loadDashboardData()
    }

    private fun loadDashboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allScans = db.scanDao().getAllScans()
            val latestScans = allScans.take(6)

            // Calculate Summary
            val totalScans = allScans.size
            var moderateCount = 0
            var severeCount = 0
            var lastScanResult = "None"
            var lastActivityTimeStr = "--"
            var lastActivityLabel = ""

            if (allScans.isNotEmpty()) {
                val latest = allScans.first()
                try {
                    val latestData: Map<String, Any> = gson.fromJson(latest.jsonData, scanDataType)
                    val material = latestData["material"] as? String ?: "Unknown"
                    val damage = latestData["damage"] as? String ?: "No Damage"
                    lastScanResult = "$material - $damage"
                } catch (e: Exception) {}

                for (scan in allScans) {
                    try {
                        val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
                        val severity = (data["severity"] as? String)?.lowercase()
                        if (severity == "medium" || severity == "moderate") moderateCount++
                        if (severity == "high" || severity == "severe") severeCount++
                    } catch (e: Exception) {}
                }
            }

            val activityLabel = AppSettings.getLastActivityLabel(this@MenuActivity)
            val activityTimestamp = AppSettings.getLastActivityTimestamp(this@MenuActivity)
            val aiStatus = resolveAiSystemStatus(allScans)

            if (!activityLabel.isNullOrBlank() && activityTimestamp > 0L) {
                lastActivityTimeStr = formatTimeAgo(activityTimestamp)
                lastActivityLabel = activityLabel
            } else if (allScans.isNotEmpty()) {
                lastActivityTimeStr = formatTimeAgo(allScans.first().timestamp)
                lastActivityLabel = "Taken a picture"
            }

            withContext(Dispatchers.Main) {
                // Update Dashboard Card
                findViewById<TextView>(R.id.tvTotalScans).text = totalScans.toString()
                findViewById<TextView>(R.id.tvLastActivity).text = lastActivityTimeStr
                findViewById<TextView>(R.id.tvLastActivitySubtitle).text = lastActivityLabel
                findViewById<TextView>(R.id.tvModerateCount).text = moderateCount.toString()
                findViewById<TextView>(R.id.tvSevereCount).text = severeCount.toString()
                findViewById<TextView>(R.id.tvLastScanResult).text = lastScanResult
                tvAiSystemStatus.text = getString(aiStatus.textResId)
                tvAiSystemStatus.setTextColor(aiStatus.textColor)
                ViewCompat.setBackgroundTintList(
                    vAiSystemStatusDot,
                    ColorStateList.valueOf(aiStatus.textColor)
                )
                ViewCompat.setBackgroundTintList(
                    aiSystemStatusContainer,
                    ColorStateList.valueOf(aiStatus.containerTint)
                )

                // Update Recent Photos Grid
                updateRecentPhotosGrid(latestScans)
            }
        }
    }

    private fun updateRecentPhotosGrid(latestScans: List<ScanEntity>) {
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

        // Fill with images using Coil
        for (i in latestScans.indices) {
            val scan = latestScans[i]
            try {
                val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
                val imageUriStr = data["imageUri"] as? String

                if (imageUriStr != null) {
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
                slots[i].setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ago"
            hours > 0 -> "${hours}h ago"
            minutes > 0 -> "${minutes}m ago"
            seconds > 30 -> "${seconds}s ago"
            else -> "Just now"
        }
    }

    private fun resolveAiSystemStatus(scans: List<ScanEntity>): AiSystemStatus {
        val openCvReady = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        val modelReady = listOf(
            "mobileclip_visual.onnx",
            "text_features.json",
            "mobileclip_defect_validator.onnx",
            "defect_validation_features.json"
        ).all { assetExists(it) }

        val optimized = openCvReady &&
            modelReady &&
            scans.groupBy { LocationHelper.normalizeLocationTag(it.location) }
                .any { (_, locationScans) -> locationScans.size > 1 }

        return when {
            !openCvReady || !modelReady -> AiSystemStatus(
                textResId = R.string.ai_system_limited_offline,
                textColor = Color.parseColor("#B00020"),
                containerTint = Color.parseColor("#FFEBEE")
            )
            optimized -> AiSystemStatus(
                textResId = R.string.ai_system_online_optimized,
                textColor = Color.parseColor("#2E7D32"),
                containerTint = Color.parseColor("#E8F5E9")
            )
            else -> AiSystemStatus(
                textResId = R.string.ai_system_online_standard,
                textColor = Color.parseColor("#1565C0"),
                containerTint = Color.parseColor("#E3F2FD")
            )
        }
    }

    private fun assetExists(assetName: String): Boolean {
        return runCatching {
            assets.open(assetName).use { }
        }.isSuccess
    }

    private fun openCameraCapture() {
        runCatching {
            startActivity(
                Intent(this, CameraActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            finish()
        }.onFailure {
            android.widget.Toast.makeText(
                this,
                "Unable to open capture screen.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private data class AiSystemStatus(
        val textResId: Int,
        val textColor: Int,
        val containerTint: Int
    )
}
