package com.example.specuraprototype

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()
    private val expandedLocationKeys = mutableSetOf<String>()
    private val selectedFolders = mutableSetOf<String>()
    private val scanDataType = object : TypeToken<Map<String, Any>>() {}.type
    private var focusScanId = NO_FOCUS_SCAN_ID
    private var shouldScrollToFocus = false

    private var pendingCsvData: String? = null

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { saveCsvToUri(it) }
    }

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
            if (selectedFolders.isNotEmpty()) {
                clearSelection()
            } else {
                finish()
            }
        }

        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener { }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }

        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnBatchExport).setOnClickListener {
            exportSelectedFolders()
        }

        findViewById<Button>(R.id.btnBatchDelete).setOnClickListener {
            confirmDeleteSelected()
        }

        loadHistoryFromDatabase()
    }

    override fun onResume() {
        super.onResume()
        loadHistoryFromDatabase()
    }

    private fun loadHistoryFromDatabase() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val scans = db.scanDao().getAllScans()
            
            withContext(Dispatchers.Main) {
                container.removeAllViews()
                if (scans.isEmpty()) {
                    container.addView(createEmptyView())
                    updateSelectionBar()
                    return@withContext
                }

                val scansByLocation = scans.groupBy { scan ->
                    LocationHelper.normalizeLocationTag(scan.location)
                }

                for ((locationKey, locationScans) in scansByLocation) {
                    container.addView(createFolderView(locationKey, locationScans))

                    if (expandedLocationKeys.contains(locationKey)) {
                        // 1. Add Folder Insights card
                        container.addView(createOverviewCard(locationScans))

                        // 2. Add individual scan entries
                        locationScans.forEach { scan ->
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
                updateSelectionBar()
            }
        }
    }

    private fun updateSelectionBar() {
        val bar = findViewById<LinearLayout>(R.id.selectionActions)
        bar.visibility = if (selectedFolders.isNotEmpty()) View.VISIBLE else View.GONE
        
        val title = findViewById<TextView>(R.id.tvTitle)
        if (selectedFolders.isNotEmpty()) {
            title.text = getString(R.string.selected_count, selectedFolders.size)
        } else {
            title.text = getString(R.string.history_title)
        }
    }

    private fun clearSelection() {
        selectedFolders.clear()
        loadHistoryFromDatabase()
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
        val isSelected = selectedFolders.contains(locationKey)

        val folder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AppCompatResources.getDrawable(
                this@HistoryActivity,
                if (isSelected) R.drawable.history_focus_background else R.drawable.history_folder_bg
            )
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(4), dp(4), dp(4), dp(8))
            }
            
            setOnClickListener {
                if (isExpanded) expandedLocationKeys.remove(locationKey)
                else expandedLocationKeys.add(locationKey)
                loadHistoryFromDatabase()
            }
            
            setOnLongClickListener {
                toggleFolderSelection(locationKey)
                true
            }
        }

        val checkbox = CheckBox(this).apply {
            isChecked = isSelected
            setOnClickListener {
                toggleFolderSelection(locationKey)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
        }

        val folderIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(12)
            }
        }

        val labelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = locationKey
            textSize = 17f
            setTextColor(android.graphics.Color.BLACK)
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

        labelContainer.addView(title)
        labelContainer.addView(summary)

        val expandIndicator = ImageView(this).apply {
            setImageResource(if (isExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            alpha = 0.6f
        }

        folder.addView(checkbox)
        folder.addView(folderIcon)
        folder.addView(labelContainer)
        folder.addView(expandIndicator)

        return folder
    }

    private fun createOverviewCard(scans: List<ScanEntity>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = AppCompatResources.getDrawable(
                this@HistoryActivity,
                R.drawable.result_popup_background
            )
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(10)
            }
        }

        val title = TextView(this).apply {
            text = getString(R.string.history_folder_overview_title)
            textSize = 17f
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(title)

        val sorted = scans.sortedBy { it.timestamp }
        val firstDate = formatTimestamp(sorted.first().timestamp)
        val lastDate = formatTimestamp(sorted.last().timestamp)

        val timelineText = TextView(this).apply {
            text = "${getString(R.string.history_first_capture, firstDate)}\n${getString(R.string.history_latest_capture, lastDate)}"
            textSize = 13f
            setTextColor(android.graphics.Color.DKGRAY)
            setLineSpacing(0f, 1.2f)
        }
        card.addView(timelineText)

        val overviewText = generateFolderOverview(scans)
        if (overviewText != null) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(0, dp(10), 0, dp(10))
                }
                setBackgroundColor(android.graphics.Color.LTGRAY)
            }
            card.addView(divider)

            val overviewView = TextView(this).apply {
                text = overviewText
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#B00020"))
                setTypeface(null, android.graphics.Typeface.ITALIC)
            }
            card.addView(overviewView)
        }

        val btnGhostTrack = Button(this).apply {
            text = "Track Progression (Ghost Mode)"
            setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
            setTextColor(android.graphics.Color.WHITE)
            isAllCaps = false
            setOnClickListener {
                val latestScan = scans.sortedByDescending { it.timestamp }.first()
                val data: Map<String, Any> = gson.fromJson(latestScan.jsonData, scanDataType)
                val uri = data["imageUri"] as? String

                val intent = Intent(this@HistoryActivity, CameraActivity::class.java).apply {
                    putExtra(CameraActivity.EXTRA_LOCATION_TAG, latestScan.location)
                    putExtra(CameraActivity.EXTRA_PREVIOUS_IMAGE_PATH, uri)
                }
                startActivity(intent)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        card.addView(btnGhostTrack)

        return card
    }

    private fun createScanEntry(scan: ScanEntity, isFocused: Boolean): LinearLayout {
        val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
        val material = data["material"] as? String ?: "Unknown"
        val damage = data["damage"] as? String ?: "Unknown"
        val severity = data["severity"] as? String ?: "Unknown"
        val imageUri = data["imageUri"] as? String

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            background = AppCompatResources.getDrawable(
                this@HistoryActivity,
                if (isFocused) R.drawable.history_focus_background else android.R.drawable.list_selector_background
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(2)
            }

            setOnClickListener {
                showScanDetailsPopup(scan, material, damage, severity, imageUri, data)
            }

            if (!imageUri.isNullOrEmpty()) {
                val thumb = ImageView(this@HistoryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    load(Uri.parse(imageUri)) {
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        marginEnd = dp(12)
                    }
                }
                addView(thumb)
            }

            val textContainer = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val line1 = TextView(this@HistoryActivity).apply {
                text = getString(R.string.history_damage_format, damage, severity)
                textSize = 14f
                setTextColor(android.graphics.Color.BLACK)
            }

            val line2 = TextView(this@HistoryActivity).apply {
                text = "${formatTimestamp(scan.timestamp)} | $material"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }

            textContainer.addView(line1)
            textContainer.addView(line2)
            addView(textContainer)

            val arrow = ImageView(this@HistoryActivity).apply {
                setImageResource(android.R.drawable.ic_media_play)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                alpha = 0.5f
            }
            addView(arrow)
        }
    }

    private fun showScanDetailsPopup(
        scan: ScanEntity,
        material: String,
        damage: String,
        severity: String,
        imageUri: String?,
        data: Map<String, Any>
    ) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(android.graphics.Color.parseColor("#F8F9FA"))
        }

        if (!imageUri.isNullOrEmpty()) {
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(220)
                ).apply { bottomMargin = dp(16) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(Uri.parse(imageUri)) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_report_image)
                }
            }
            dialogView.addView(imageView)
        }

        val badge = TextView(this).apply {
            text = severity.uppercase()
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val color = when (severity.lowercase()) {
                "high" -> android.graphics.Color.parseColor("#D32F2F")
                "medium" -> android.graphics.Color.parseColor("#FBC02D")
                "low" -> android.graphics.Color.parseColor("#388E3C")
                else -> android.graphics.Color.GRAY
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        dialogView.addView(badge)

        fun addDetail(label: String, value: String, isTechnical: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val labelTv = TextView(this).apply {
                text = "$label: "
                setTypeface(null, Typeface.BOLD)
                setTextColor(android.graphics.Color.BLACK)
                textSize = 14f
            }
            val valueTv = TextView(this).apply {
                text = value
                if (isTechnical) {
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                } else {
                    textSize = 14f
                }
                setTextColor(android.graphics.Color.parseColor("#424242"))
            }
            row.addView(labelTv)
            row.addView(valueTv)
            dialogView.addView(row)
        }

        addDetail("Location Tag", scan.location)
        addDetail("Material", material)
        addDetail("Damage Type", damage)
        addDetail("Detection Confidence", String.format("%.1f%%", (data["confidence"] as? Number)?.toDouble() ?: 0.0))
        addDetail("Timestamp", formatTimestamp(scan.timestamp))
        
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(10), 0, dp(10))
            }
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }
        dialogView.addView(divider)

        addDetail("H-Score (Severity)", String.format("%.4f", (data["H"] as? Number)?.toDouble() ?: 0.0), true)
        addDetail("E-Score (Abnormality)", String.format("%.4f", (data["E"] as? Number)?.toDouble() ?: 0.0), true)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setNegativeButton("Delete") { _, _ -> confirmDeleteSingle(scan) }
            .setCancelable(true)
            .show()
    }

    private fun confirmDeleteSingle(scan: ScanEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage("Are you sure you want to delete this scan entry? This action cannot be undone.")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.scanDao().deleteScan(scan)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HistoryActivity, R.string.deleted_successfully, Toast.LENGTH_SHORT).show()
                        loadHistoryFromDatabase()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateFolderOverview(scans: List<ScanEntity>): String? {
        if (scans.size < 2) return null
        
        val sortedScans = scans.sortedBy { it.timestamp }
        val oldest = sortedScans.first()
        val newest = sortedScans.last()

        val oldestData: Map<String, Any> = gson.fromJson(oldest.jsonData, scanDataType)
        val newestData: Map<String, Any> = gson.fromJson(newest.jsonData, scanDataType)

        val oldestH = (oldestData["H"] as? Number)?.toDouble() ?: 0.0
        val newestH = (newestData["H"] as? Number)?.toDouble() ?: 0.0
        
        val oldestE = (oldestData["E"] as? Number)?.toDouble() ?: 0.0
        val newestE = (newestData["E"] as? Number)?.toDouble() ?: 0.0

        val material = (newestData["material"] as? String) ?: ""
        
        val messages = mutableListOf<String>()

        if (newestH > oldestH) {
            val date1 = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(oldest.timestamp))
            val date2 = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(newest.timestamp))
            messages.add(getString(R.string.history_overview_damage_increase, date1, date2))
            messages.add(getString(R.string.history_overview_cracks))
        }

        if (newestE > oldestE) {
            val overviewStr = when {
                material.contains("Concrete", ignoreCase = true) || material.contains("Brick", ignoreCase = true) -> 
                    getString(R.string.history_overview_abnormalities_concrete)
                material.contains("Metal", ignoreCase = true) || material.contains("Steel", ignoreCase = true) -> 
                    getString(R.string.history_overview_abnormalities_metal)
                material.contains("Wood", ignoreCase = true) -> 
                    getString(R.string.history_overview_abnormalities_wood)
                else -> null
            }
            if (overviewStr != null) messages.add(overviewStr)
        }

        return if (messages.isNotEmpty()) messages.joinToString("\n\n") else null
    }

    private fun toggleFolderSelection(locationKey: String) {
        if (selectedFolders.contains(locationKey)) {
            selectedFolders.remove(locationKey)
        } else {
            selectedFolders.add(locationKey)
        }
        loadHistoryFromDatabase()
    }

    private fun exportSelectedFolders() {
        if (selectedFolders.isEmpty()) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            val allScans = db.scanDao().getAllScans()
            val filteredScans = allScans.filter { 
                selectedFolders.contains(LocationHelper.normalizeLocationTag(it.location)) 
            }

            if (filteredScans.isEmpty()) return@launch

            val csvBuilder = StringBuilder()
            csvBuilder.append("Location,Timestamp,Date,Material,Condition,SeverityLabel,SeverityScore(H),DamageSignal(E),ImageUri\n")
            
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (scan in filteredScans) {
                val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
                val dateStr = sdf.format(Date(scan.timestamp))
                
                csvBuilder.append("\"${scan.location}\",")
                csvBuilder.append("${scan.timestamp},")
                csvBuilder.append("\"$dateStr\",")
                csvBuilder.append("\"${data["material"] ?: "Unknown"}\",")
                csvBuilder.append("\"${data["damage"] ?: "Unknown"}\",")
                csvBuilder.append("\"${data["severity"] ?: "Unknown"}\",")
                csvBuilder.append("${data["H"] ?: 0.0},")
                csvBuilder.append("${data["E"] ?: 0.0},")
                csvBuilder.append("\"${data["imageUri"] ?: ""}\"\n")
            }

            withContext(Dispatchers.Main) {
                pendingCsvData = csvBuilder.toString()
                val fileName = "Specura_Export_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
                createDocumentLauncher.launch(fileName)
            }
        }
    }

    private fun saveCsvToUri(uri: Uri) {
        val csvData = pendingCsvData ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(csvData.toByteArray())
                Toast.makeText(this, "Export saved successfully", Toast.LENGTH_LONG).show()
                clearSelection()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save export: ${e.message}", Toast.LENGTH_LONG).show()
        }
        pendingCsvData = null
    }

    private fun confirmDeleteSelected() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(getString(R.string.delete_selected_message, selectedFolders.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteFolders()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteFolders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allScans = db.scanDao().getAllScans()
            val toDelete = allScans.filter { 
                selectedFolders.contains(LocationHelper.normalizeLocationTag(it.location)) 
            }
            
            toDelete.forEach { db.scanDao().deleteScan(it) }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@HistoryActivity, R.string.deleted_successfully, Toast.LENGTH_SHORT).show()
                clearSelection()
            }
        }
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FOCUS_LOCATION = "focus_location"
        const val EXTRA_FOCUS_SCAN_ID = "focus_scan_id"
        private const val NO_FOCUS_SCAN_ID = -1L
    }
}
