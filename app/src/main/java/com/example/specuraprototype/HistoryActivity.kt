package com.example.specuraprototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()
    private val expandedLocationKeys = mutableSetOf<String>()
    private val scanDataType = object : TypeToken<Map<String, Any>>() {}.type
    private var focusScanId = NO_FOCUS_SCAN_ID
    private var shouldScrollToFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)

        db = AppDatabase.getDatabase(this)
        applyFocusIntent(intent)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

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

        val scans = db.scanDao().getAllScans()

        if (scans.isEmpty()) {
            container.addView(createEmptyView())
            return
        }

        val scansByLocation = scans.groupBy { scan ->
            LocationHelper.normalizeLocationTag(scan.location)
        }

        for ((locationKey, locationScans) in scansByLocation) {
            container.addView(createFolderView(locationKey, locationScans))

            if (expandedLocationKeys.contains(locationKey)) {
                for (scan in locationScans) {
                    val isFocused = scan.id == focusScanId
                    val entry = createScanEntry(scan, isFocused)
                    container.addView(entry)

                    if (isFocused && shouldScrollToFocus) {
                        entry.post {
                            findViewById<ScrollView>(R.id.historyScroll).smoothScrollTo(0, entry.top)
                            shouldScrollToFocus = false
                        }
                    }
                }
            }
        }
    }

    private fun applyFocusIntent(intent: Intent) {
        val focusLocation = intent.getStringExtra(EXTRA_FOCUS_LOCATION)
        focusScanId = intent.getLongExtra(EXTRA_FOCUS_SCAN_ID, NO_FOCUS_SCAN_ID)

        if (!focusLocation.isNullOrBlank()) {
            expandedLocationKeys.add(LocationHelper.normalizeLocationTag(focusLocation))
        }

        shouldScrollToFocus = focusScanId != NO_FOCUS_SCAN_ID
    }

    private fun createEmptyView(): TextView =
        TextView(this).apply {
            text = getString(R.string.history_empty)
            textSize = 16f
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER
        }

    private fun createFolderView(locationKey: String, scans: List<ScanEntity>): LinearLayout {
        val isExpanded = expandedLocationKeys.contains(locationKey)

        val folder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppCompatResources.getDrawable(
                this@HistoryActivity,
                android.R.drawable.dialog_holo_light_frame
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.history_folder_description, locationKey)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                if (isExpanded) {
                    expandedLocationKeys.remove(locationKey)
                } else {
                    expandedLocationKeys.add(locationKey)
                }
                loadHistoryFromDatabase()
            }
        }

        val folderIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            contentDescription = getString(R.string.history_folder)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            }
        }

        val labelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = locationKey
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val summary = TextView(this).apply {
            text = getString(
                R.string.history_folder_count_format,
                scans.size,
                formatTimestamp(scans.first().timestamp)
            )
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }

        val expandIndicator = TextView(this).apply {
            text = if (isExpanded) "-" else "+"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }

        labelContainer.addView(title)
        labelContainer.addView(summary)
        folder.addView(folderIcon)
        folder.addView(labelContainer)
        folder.addView(expandIndicator)

        return folder
    }

    private fun createScanEntry(scan: ScanEntity, isFocused: Boolean = false): LinearLayout {
        val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)

        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            background = AppCompatResources.getDrawable(
                this@HistoryActivity,
                if (isFocused) R.drawable.history_focus_background else android.R.drawable.dialog_holo_light_frame
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(20)
                bottomMargin = dp(12)
            }
        }

        val imageUri = data["imageUri"] as? String
        if (imageUri != null) {
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(220)
                )
                contentDescription = getString(R.string.history_scan_image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(Uri.parse(imageUri))
                setPadding(0, 0, 0, dp(8))
            }
            entry.addView(image)
        }

        val locationText = TextView(this).apply {
            text = getString(R.string.history_location_format, scan.location)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val materialText = TextView(this).apply {
            text = getString(
                R.string.history_material_format,
                data["material"] ?: getString(R.string.unknown)
            )
            textSize = 16f
        }

        val damageText = TextView(this).apply {
            text = getString(
                R.string.history_damage_format,
                data["damage"] ?: getString(R.string.unknown),
                data["severity"] ?: ""
            )
            textSize = 14f
        }

        val dateText = TextView(this).apply {
            text = formatTimestamp(scan.timestamp)
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }

        entry.addView(locationText)
        entry.addView(materialText)
        entry.addView(damageText)
        entry.addView(dateText)

        return entry
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FOCUS_LOCATION = "focusLocation"
        const val EXTRA_FOCUS_SCAN_ID = "focusScanId"
        private const val NO_FOCUS_SCAN_ID = -1L
    }
}
