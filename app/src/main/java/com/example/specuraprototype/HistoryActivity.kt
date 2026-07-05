package com.example.specuraprototype

import android.content.Intent
import android.content.DialogInterface
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
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

    private var pendingCsvReport: String? = null
    private var pendingPdfScans: List<ScanEntity> = emptyList()

    private val createCsvDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { saveCsvReportToUri(it) }
    }

    private val createPdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { savePdfReportToUri(it) }
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
            showGenerateReportDialog()
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
            setTypeface(null, Typeface.BOLD)
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
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(title)

        val sorted = scans.sortedBy { it.timestamp }
        val firstDate = formatTimestamp(sorted.first().timestamp)
        val lastDate = formatTimestamp(sorted.last().timestamp)

        val timelineText = TextView(this).apply {
            val dateRange = "${getString(R.string.history_first_capture, firstDate)}\n${getString(R.string.history_latest_capture, lastDate)}"
            text = dateRange
            textSize = 13f
            setTextColor(android.graphics.Color.DKGRAY)
            setLineSpacing(0f, 1.2f)
        }
        card.addView(timelineText)

        val overview = generateFolderTrendOverview(scans)
        if (overview != null) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(0, dp(10), 0, dp(10))
                }
                setBackgroundColor(android.graphics.Color.LTGRAY)
            }
            card.addView(divider)

            val overviewView = TextView(this).apply {
                text = overview.text
                textSize = 14f
                setTextColor(
                    when (overview.trendType) {
                        ConditionTrendType.WORSENING -> android.graphics.Color.parseColor("#B00020")
                        ConditionTrendType.STABLE -> android.graphics.Color.parseColor("#2E7D32")
                        ConditionTrendType.NEW -> android.graphics.Color.parseColor("#546E7A")
                    }
                )
                setTypeface(null, Typeface.ITALIC)
            }
            card.addView(overviewView)
        }

        return card
    }

    private fun createScanEntry(scan: ScanEntity, isFocused: Boolean): LinearLayout {
        val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
        val material = data["material"] as? String ?: "Unknown"
        val damage = data["damage"] as? String ?: "Unknown"
        val severity = data["severity"] as? String ?: "Unknown"
        val imageUri = data["imageUri"] as? String
        val isTechnicalLedger = hasTechnicalLedgerFields(data)
        val clipVerified = extractBoolean(data["clip_verified"])
        val defectSummary = formatVerifiedDefects(data["verified_defects"])
        val roiCount = extractRoiCount(data["rois"])

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
                text = if (isTechnicalLedger) {
                    val summary = if (clipVerified) {
                        if (defectSummary.isBlank()) "Verified defect" else defectSummary
                    } else {
                        "Normal surface"
                    }
                    "$material - $summary"
                } else {
                    getString(R.string.history_damage_format, damage, severity)
                }
                textSize = 14f
                setTextColor(android.graphics.Color.BLACK)
            }

            val line2 = TextView(this@HistoryActivity).apply {
                text = if (isTechnicalLedger) {
                    "${formatTimestamp(scan.timestamp)} | ROIs: $roiCount"
                } else {
                    getString(R.string.history_entry_subtitle, formatTimestamp(scan.timestamp), material)
                }
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
        val isTechnicalLedger = hasTechnicalLedgerFields(data)
        val dialogContent = LinearLayout(this).apply {
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
            dialogContent.addView(imageView)
        }

        val badge = TextView(this).apply {
            text = severity.uppercase(Locale.getDefault())
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val color = when (severity.lowercase(Locale.getDefault())) {
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
        dialogContent.addView(badge)

        fun addDetail(label: String, value: String, isTechnical: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val labelTv = TextView(this).apply {
                text = getString(R.string.detail_label_format, label)
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
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(6)
                }
                setTextColor(android.graphics.Color.parseColor("#424242"))
                setLineSpacing(0f, 1.1f)
            }
            row.addView(labelTv)
            row.addView(valueTv)
            dialogContent.addView(row)
        }

        val locationTag = data["actvLocationTag"] as? String ?: scan.location
        val clipVerified = extractBoolean(data["clip_verified"])
        val roiCount = extractRoiCount(data["rois"])
        val verifiedDefectSummary = formatVerifiedDefects(data["verified_defects"])
        val hScore = extractScore(data, "h_score", "H")
        val eScore = extractScore(data, "e_score", "E")
        val pixelCount = extractInt(data["pixel_count"])

        addDetail("Location Tag", locationTag)
        addDetail("Material", material)

        if (isTechnicalLedger) {
            addDetail("Ledger Status", if (clipVerified) "DEFECT VERIFIED" else "NORMAL SURFACE")
            if (verifiedDefectSummary.isNotBlank()) {
                addDetail("Verified Defects", verifiedDefectSummary)
            }
            addDetail("ROI Count", roiCount.toString())
            addDetail("Pixel Count", pixelCount.toString(), true)
            addDetail("H-Score (Severity)", String.format(Locale.getDefault(), "%.4f", hScore), true)
            addDetail("E-Score (Complexity)", String.format(Locale.getDefault(), "%.4f", eScore), true)
        } else {
            addDetail("Damage Type", damage)

            addDetail("Timestamp", formatTimestamp(scan.timestamp))

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(0, dp(10), 0, dp(10))
                }
                setBackgroundColor(android.graphics.Color.LTGRAY)
            }
            dialogContent.addView(divider)

            addDetail("H-Score (Severity)", String.format(Locale.getDefault(), "%.4f", hScore), true)
            addDetail("E-Score (Abnormality)", String.format(Locale.getDefault(), "%.4f", eScore), true)
        }

        if (isTechnicalLedger) {
            addDetail("Timestamp", formatTimestamp(scan.timestamp))

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(0, dp(10), 0, dp(10))
                }
                setBackgroundColor(android.graphics.Color.LTGRAY)
            }
            dialogContent.addView(divider)
        }

        if (!imageUri.isNullOrBlank()) {
            val defectButton = Button(this).apply {
                text = getString(R.string.view_defects)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#607D8B"))
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    DefectOverlayDialogHelper.showDefectOverlayDialog(
                        this@HistoryActivity,
                        imageUri,
                        parseVerifiedDefects(data["verified_defects"])
                    )
                }
            }
            dialogContent.addView(defectButton)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
            dialogContent.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                dialogContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setView(scrollView)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setNegativeButton("Delete") { _, _ -> confirmDeleteSingle(scan) }
            .setCancelable(true)
            .show()
    }

    private fun confirmDeleteSingle(scan: ScanEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(R.string.delete_single_message)
            .setPositiveButton(R.string.delete) { _: DialogInterface, _: Int ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.scanDao().deleteScan(scan)
                    withContext(Dispatchers.Main) {
                        AppSettings.setLastActivity(this@HistoryActivity, "Deleted a file")
                        Toast.makeText(this@HistoryActivity, R.string.deleted_successfully, Toast.LENGTH_SHORT).show()
                        loadHistoryFromDatabase()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleFolderSelection(locationKey: String) {
        if (selectedFolders.contains(locationKey)) {
            selectedFolders.remove(locationKey)
        } else {
            selectedFolders.add(locationKey)
        }
        loadHistoryFromDatabase()
    }

    private fun showGenerateReportDialog() {
        if (selectedFolders.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_options_title)
            .setItems(
                arrayOf(
                    getString(R.string.csv_report),
                    getString(R.string.pdf_report)
                )
            ) { _, which ->
                when (which) {
                    0 -> prepareCsvReport()
                    1 -> preparePdfReport()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun prepareCsvReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            val filteredScans = loadSelectedScans()
            if (filteredScans.isEmpty()) return@launch

            val csvReport = InspectionReportHelper.buildCsvReport(filteredScans, gson, scanDataType)
            withContext(Dispatchers.Main) {
                pendingCsvReport = csvReport
                val fileName = "Specura_Report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
                createCsvDocumentLauncher.launch(fileName)
            }
        }
    }

    private fun preparePdfReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            val filteredScans = loadSelectedScans()
            if (filteredScans.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                pendingPdfScans = filteredScans
                val fileName = "Specura_Report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.pdf"
                createPdfDocumentLauncher.launch(fileName)
            }
        }
    }

    private fun saveCsvReportToUri(uri: Uri) {
        val csvData = pendingCsvReport ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(csvData.toByteArray())
                AppSettings.setLastActivity(this, "CSV report generated")
                Toast.makeText(this, R.string.report_saved_successfully, Toast.LENGTH_LONG).show()
                clearSelection()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.report_generation_failed), Toast.LENGTH_LONG).show()
        }
        pendingCsvReport = null
    }

    private fun savePdfReportToUri(uri: Uri) {
        val scans = pendingPdfScans
        if (scans.isEmpty()) return

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val success = InspectionReportHelper.writePdfReport(
                    context = this,
                    outputStream = outputStream,
                    scans = scans,
                    gson = gson,
                    scanDataType = scanDataType
                )
                if (success) {
                    AppSettings.setLastActivity(this, "PDF report generated")
                    Toast.makeText(this, R.string.report_saved_successfully, Toast.LENGTH_LONG).show()
                    clearSelection()
                } else {
                    Toast.makeText(this, R.string.report_generation_failed, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.report_generation_failed), Toast.LENGTH_LONG).show()
        }
        pendingPdfScans = emptyList()
    }

    private fun loadSelectedScans(): List<ScanEntity> {
        val allScans = db.scanDao().getAllScans()
        return allScans.filter {
            selectedFolders.contains(LocationHelper.normalizeLocationTag(it.location))
        }
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
                AppSettings.setLastActivity(this@HistoryActivity, "Deleted files")
                Toast.makeText(this@HistoryActivity, R.string.deleted_successfully, Toast.LENGTH_SHORT).show()
                clearSelection()
            }
        }
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    private fun hasTechnicalLedgerFields(data: Map<String, Any>): Boolean =
        data.containsKey("clip_verified") || data.containsKey("rois") || data.containsKey("h_score")

    private fun extractBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun extractScore(data: Map<String, Any>, primaryKey: String, fallbackKey: String): Double {
        val primary = (data[primaryKey] as? Number)?.toDouble()
        if (primary != null) return primary
        return (data[fallbackKey] as? Number)?.toDouble() ?: 0.0
    }

    private fun extractInt(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

    private fun extractRoiCount(value: Any?): Int = when (value) {
        is List<*> -> value.size
        else -> 0
    }

    private fun formatVerifiedDefects(value: Any?): String {
        val labels = when (value) {
            is List<*> -> value.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> (item["label"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    is String -> item.trim().takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            is String -> value.split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }

        if (labels.isEmpty()) return ""

        val counts = linkedMapOf<String, Int>()
        val displayLabels = linkedMapOf<String, String>()
        labels.forEach { label ->
            val key = label.lowercase(Locale.getDefault())
            counts[key] = (counts[key] ?: 0) + 1
            if (!displayLabels.containsKey(key)) {
                displayLabels[key] = label.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
            }
        }

        return counts.entries.joinToString("\n") { entry ->
            val label = displayLabels[entry.key].orEmpty()
            if (entry.value > 1) "$label (${entry.value})" else label
        }
    }

    private fun parseVerifiedDefects(value: Any?): List<VerifiedDefectRecord> {
        return when (value) {
            is List<*> -> value.mapNotNull { item ->
                val defect = item as? Map<*, *> ?: return@mapNotNull null
                val label = defect["label"] as? String ?: return@mapNotNull null
                val roiMap = defect["roi"] as? Map<*, *>
                val roi = RoiBounds(
                    x = extractInt(roiMap?.get("x")),
                    y = extractInt(roiMap?.get("y")),
                    width = extractInt(roiMap?.get("width")),
                    height = extractInt(roiMap?.get("height")),
                    area = (roiMap?.get("area") as? Number)?.toDouble() ?: 0.0
                )
                VerifiedDefectRecord(label = label, roi = roi)
            }
            else -> emptyList()
        }
    }

    private fun generateFolderTrendOverview(scans: List<ScanEntity>): ConditionTrendOverview? {
        if (scans.isEmpty()) return null

        val sortedScans = scans.sortedBy { it.timestamp }
        val current = sortedScans.last()
        val currentData: Map<String, Any> = gson.fromJson(current.jsonData, scanDataType)
        val currentSeverity = extractScore(currentData, "h_score", "H")
        val currentDefects = extractDefectLabels(currentData)

        val previousSameLocation = sortedScans.dropLast(1)
        val lastInspection = previousSameLocation.lastOrNull()
        val lastInspectionDate = lastInspection?.let { formatDateOnly(it.timestamp) } ?: "N/A"
        val daysSinceLastInspection = lastInspection?.let {
            elapsedDays(current.timestamp, it.timestamp).toString()
        } ?: "N/A"

        val matchedPrevious = previousSameLocation
            .asReversed()
            .firstOrNull { previous ->
                val previousData: Map<String, Any> = gson.fromJson(previous.jsonData, scanDataType)
                val previousDefects = extractDefectLabels(previousData)
                currentDefects.isNotEmpty() && previousDefects.intersect(currentDefects).isNotEmpty()
            }

        val trendType = when {
            currentDefects.isEmpty() -> ConditionTrendType.STABLE
            matchedPrevious == null -> ConditionTrendType.NEW
            else -> {
                val previousData: Map<String, Any> = gson.fromJson(matchedPrevious.jsonData, scanDataType)
                val previousSeverity = extractScore(previousData, "h_score", "H")
                val recurrenceCount = previousSameLocation.count { previous ->
                    val previousMap: Map<String, Any> = gson.fromJson(previous.jsonData, scanDataType)
                    val previousLabels = extractDefectLabels(previousMap)
                    previousLabels.intersect(currentDefects).isNotEmpty()
                } + 1

                classifyConditionTrend(
                    currentSeverity = currentSeverity,
                    previousSeverity = previousSeverity,
                    currentTimestamp = current.timestamp,
                    previousTimestamp = matchedPrevious.timestamp,
                    recurrenceCount = recurrenceCount
                )
            }
        }

        val summary = buildString {
            append(getString(R.string.condition_trend_label))
            append(": ")
            append(getString(trendLabelRes(trendType)))
            append('\n')
            append(getString(R.string.last_inspection_date_label))
            append(": ")
            append(lastInspectionDate)
            append('\n')
            append(getString(R.string.days_since_last_inspection_label))
            append(": ")
            append(daysSinceLastInspection)
        }

        return ConditionTrendOverview(trendType = trendType, text = summary)
    }

    private fun extractDefectLabels(data: Map<String, Any>): Set<String> {
        val labels = linkedSetOf<String>()

        val verifiedDefects = data["verified_defects"]
        if (verifiedDefects is List<*>) {
            verifiedDefects.forEach { item ->
                when (item) {
                    is Map<*, *> -> {
                        val label = (item["label"] as? String)?.trim().orEmpty()
                        if (label.isNotBlank()) labels.add(normalizeDefectLabel(label))
                    }
                    is String -> {
                        val label = item.trim()
                        if (label.isNotBlank()) labels.add(normalizeDefectLabel(label))
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            val damage = (data["damage"] as? String).orEmpty().trim()
            if (damage.isNotBlank() && !damage.equals("Normal Surface", ignoreCase = true) && !damage.equals("Intact", ignoreCase = true)) {
                damage.split(',', '/', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { labels.add(normalizeDefectLabel(it)) }
            }
        }

        return labels
    }

    private fun normalizeDefectLabel(value: String): String =
        value.trim().lowercase(Locale.getDefault())

    private fun classifyConditionTrend(
        currentSeverity: Double,
        previousSeverity: Double,
        currentTimestamp: Long,
        previousTimestamp: Long,
        recurrenceCount: Int
    ): ConditionTrendType {
        val severityDelta = currentSeverity - previousSeverity
        val daysElapsed = elapsedDays(currentTimestamp, previousTimestamp).toDouble()
        val severityVelocity = severityDelta / daysElapsed
        val timeWeight = minOf(daysElapsed / 365.0, 1.0)
        val recurrenceWeight = recurrenceWeight(recurrenceCount)
        val trendScore = (severityVelocity * 0.7) + (recurrenceWeight * 0.1) - (timeWeight * 0.2)

        return when {
            severityVelocity > 0.015 -> ConditionTrendType.WORSENING
            severityDelta == 0.0 && daysElapsed > 90.0 -> ConditionTrendType.STABLE
            trendScore > 0.02 -> ConditionTrendType.WORSENING
            else -> ConditionTrendType.STABLE
        }
    }

    private fun recurrenceWeight(recurrenceCount: Int): Double {
        return when {
            recurrenceCount >= 5 -> 1.0
            recurrenceCount >= 3 -> 0.5
            else -> 0.2
        }
    }

    private fun elapsedDays(currentTimestamp: Long, previousTimestamp: Long): Long {
        val delta = (currentTimestamp - previousTimestamp).coerceAtLeast(0L)
        return (delta / 86400000L).coerceAtLeast(1L)
    }

    private fun formatDateOnly(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun trendLabelRes(trendType: ConditionTrendType): Int {
        return when (trendType) {
            ConditionTrendType.NEW -> R.string.trend_new
            ConditionTrendType.STABLE -> R.string.trend_stable
            ConditionTrendType.WORSENING -> R.string.trend_worsening
        }
    }

    private data class ConditionTrendOverview(
        val trendType: ConditionTrendType,
        val text: String
    )

    private enum class ConditionTrendType {
        NEW,
        STABLE,
        WORSENING
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FOCUS_LOCATION = "focus_location"
        const val EXTRA_FOCUS_SCAN_ID = "focus_scan_id"
        private const val NO_FOCUS_SCAN_ID = -1L
    }
}
