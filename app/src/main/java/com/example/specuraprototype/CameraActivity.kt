package com.example.specuraprototype

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashSet
import java.util.Locale

class CameraActivity : AppCompatActivity() {

    private companion object {
        private const val AUTO_MATERIAL_CONFIDENCE_THRESHOLD = 0.10f
        private const val AUTO_MATERIAL_MARGIN_THRESHOLD = 0.005f
        private const val AUTO_MATERIAL_FALLBACK = "Concrete"
        const val EXTRA_LOCATION_TAG = "extra_location_tag"
    }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isTorchEnabled = false
    private val shutterSound = MediaActionSound()
    private var classifier: ClipClassifier? = null
    private var structuralAnalyzer: OpenCvStructuralAnalyzer? = null
    private var defectValidator: MobileClipDefectValidator? = null
    private var db: AppDatabase? = null
    private lateinit var actvLocationTag: MaterialAutoCompleteTextView
    private lateinit var btnCreateLocation: MaterialButton
    private lateinit var btnTorch: MaterialButton
    
    // Material Selection UI
    private lateinit var toggleMaterialGroup: MaterialButtonToggleGroup
    private lateinit var tvManualModeHint: TextView
    private var selectedMaterial: String = "Auto"
    private val debugShowAllRois = false
    
    private val gson = Gson()

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            }
        }

    private val pickPhotoForDebug =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val input = actvLocationTag.text.toString()
                if (LocationHelper.isValid(input)) {
                    AppSettings.setLastActivity(this, "Uploaded a photo")
                    processCapturedImage(uri)
                } else {
                    Toast.makeText(this, "Please enter or select a location tag", Toast.LENGTH_SHORT).show()
                    actvLocationTag.requestFocus()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToMenu()
            }
        })

        db = runCatching { AppDatabase.getDatabase(this) }
            .getOrNull()
        if (db == null) {
            Log.e("CameraActivity", "Database initialization failed")
            Toast.makeText(this, "Some features may be unavailable on this device.", Toast.LENGTH_LONG).show()
        }

        initializeAnalysisStack()
        runCatching { shutterSound.load(MediaActionSound.SHUTTER_CLICK) }
            .onFailure { Log.e("CameraActivity", "Shutter sound init failed", it) }
        
        actvLocationTag = findViewById(R.id.actvLocationTag)
        btnCreateLocation = findViewById(R.id.btnCreateLocation)
        btnTorch = findViewById(R.id.btnTorch)
        val btnUploadPhoto = findViewById<MaterialButton>(R.id.btnUploadPhoto)
        actvLocationTag.keyListener = null
        actvLocationTag.showSoftInputOnFocus = false
        actvLocationTag.isCursorVisible = false
        actvLocationTag.isFocusable = false
        actvLocationTag.isFocusableInTouchMode = false
        actvLocationTag.setTextIsSelectable(false)
        actvLocationTag.setOnClickListener {
            actvLocationTag.showDropDown()
        }
        btnCreateLocation.setOnClickListener {
            showCreateLocationDialog()
        }
        btnTorch.setOnClickListener {
            toggleTorch()
        }
        btnUploadPhoto.setOnClickListener {
            pickPhotoForDebug.launch("image/*")
        }
        updateTorchUi()
        
        // Material Selector Setup
        toggleMaterialGroup = findViewById(R.id.toggleMaterialGroup)
        tvManualModeHint = findViewById(R.id.tvManualModeHint)
        setupMaterialSelector()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            returnToMenu()
        }

        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
            finish()
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            val input = actvLocationTag.text.toString()
            if (LocationHelper.isValid(input)) {
                takePhoto()
            } else {
                Toast.makeText(this, "Please enter or select a location tag", Toast.LENGTH_SHORT).show()
                actvLocationTag.requestFocus()
            }
        }

        setupLocationDropdown()
        checkPermissions()
        
        // Handle incoming Intent extras
        val initialLocation = intent.getStringExtra(EXTRA_LOCATION_TAG)
        if (!initialLocation.isNullOrBlank()) {
            setSelectedLocation(initialLocation, persistRecent = false)
        }
    }

    private fun setupMaterialSelector() {
        toggleMaterialGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                selectedMaterial = when (checkedId) {
                    R.id.btnModeAuto -> "Auto"
                    R.id.btnModeBrick -> "Brick"
                    R.id.btnModeConcrete -> "Concrete"
                    R.id.btnModeMetal -> "Metal"
                    R.id.btnModeWood -> "Wood"
                    else -> "Auto"
                }
                tvManualModeHint.visibility = if (selectedMaterial == "Auto") View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupLocationDropdown() {
        refreshLocationDropdown()
    }

    private fun refreshLocationDropdown(selectedTag: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localDb = db ?: return@launch
            val dbLocations = localDb.scanDao().getUniqueLocations()
            val recentCustomLocations = AppSettings.getRecentLocationTags(this@CameraActivity)
            val combined = LinkedHashSet<String>().apply {
                recentCustomLocations.forEach { add(LocationHelper.normalizeLocationTag(it)) }
                dbLocations.forEach { add(LocationHelper.normalizeLocationTag(it)) }
            }.toList()

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(
                    this@CameraActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    combined
                )
                actvLocationTag.setAdapter(adapter)
                actvLocationTag.setOnItemClickListener { _, _, position, _ ->
                    val chosen = adapter.getItem(position)
                    if (!chosen.isNullOrBlank()) {
                        setSelectedLocation(chosen, persistRecent = false)
                    }
                }

                if (!selectedTag.isNullOrBlank()) {
                    actvLocationTag.setText(LocationHelper.normalizeLocationTag(selectedTag), false)
                }
            }
        }
    }

    private fun setSelectedLocation(tag: String, persistRecent: Boolean) {
        val normalized = LocationHelper.normalizeLocationTag(tag)
        actvLocationTag.setText(normalized, false)
        if (persistRecent) {
            AppSettings.addRecentLocationTag(this, normalized)
            refreshLocationDropdown(normalized)
        }
    }

    private fun showCreateLocationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.create_location_hint)
            setText(actvLocationTag.text?.toString().orEmpty())
            setSelection(text?.length ?: 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_location_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                if (LocationHelper.isValid(raw)) {
                    setSelectedLocation(raw, persistRecent = true)
                } else {
                    Toast.makeText(this, "Location tag cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA) // Simplified for prototype
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                applyTorchState()
            } catch (exc: Exception) {
                Log.e("CameraActivity", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val currentCamera = camera
        if (currentCamera == null) {
            Toast.makeText(this, "Torch is not available yet.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!currentCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Torch is not supported on this device.", Toast.LENGTH_SHORT).show()
            btnTorch.isEnabled = false
            updateTorchUi()
            return
        }

        isTorchEnabled = !isTorchEnabled
        applyTorchState()
    }

    private fun applyTorchState() {
        val currentCamera = camera
        if (currentCamera == null) {
            updateTorchUi()
            return
        }

        if (!currentCamera.cameraInfo.hasFlashUnit()) {
            isTorchEnabled = false
            currentCamera.cameraControl.enableTorch(false)
            btnTorch.isEnabled = false
        } else {
            btnTorch.isEnabled = true
            currentCamera.cameraControl.enableTorch(isTorchEnabled)
        }

        updateTorchUi()
    }

    private fun updateTorchUi() {
        btnTorch.text = if (isTorchEnabled) {
            getString(R.string.torch_on)
        } else {
            getString(R.string.torch_off)
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        if (AppSettings.isShutterSoundEnabled(this)) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }
        
        val name = "SCAN_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Specura")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraActivity", "Photo capture failed", exc)
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let {
                    AppSettings.setLastActivity(this@CameraActivity, "Taken a picture")
                    processCapturedImage(it)
                }
            }
        })
    }

    private fun processCapturedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Default) {
            val bitmap = loadAnalysisBitmap(uri) ?: return@launch
            try {
                val normalizedLocation = LocationHelper.normalizeLocationTag(actvLocationTag.text.toString())
                val localDb = db
                var materialName = getString(R.string.unknown)
                var severityLabel = "Unknown"
                var adjustedHScore = 0f
                var eScore = 0f
                var pixelCount = 0
                var clipVerified = false
                var verifiedDefects = emptyList<VerifiedDefectRecord>()
                var rois = emptyList<RoiBounds>()
                var defectSummary = "Unknown"

                val materialResult = resolveMaterialForCapture(bitmap, normalizedLocation)
                materialName = materialResult.material
                val materialSummary = MaterialResultRecord(
                    material = materialName.lowercase(Locale.getDefault()),
                    confidence = materialResult.materialConfidence * 100f,
                    raw_similarity = materialResult.rawSimilarity,
                    margin = materialResult.margin,
                    top3 = materialResult.topMaterials.map { prediction ->
                        MaterialTopRecord(
                            material = prediction.label.lowercase(Locale.getDefault()),
                            confidence = prediction.confidence * 100f
                        )
                    },
                    all_scores = materialResult.allScores
                )

                val localStructuralAnalyzer = structuralAnalyzer
                val localDefectValidator = defectValidator
                val structuralAnalysis = localStructuralAnalyzer?.analyze(bitmap, materialName)

                val ledgerRecord = if (structuralAnalysis != null) {
                    val roiDebugDefects = structuralAnalysis.suspiciousRegions.map { region ->
                        VerifiedDefectRecord(
                            label = "?",
                            roi = region.bounds
                        )
                    }
                    val rawVerifiedDefects = if (debugShowAllRois) {
                        roiDebugDefects
                    } else {
                        localDefectValidator?.validate(
                            bitmap,
                            structuralAnalysis.suspiciousRegions,
                            materialName
                        ).orEmpty()
                    }
                    val filteredDefects = if (debugShowAllRois) {
                        rawVerifiedDefects
                    } else {
                        RoiSeverityScaler.filterForSeverity(
                            material = materialName,
                            defects = rawVerifiedDefects,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    }
                    structuralAnalysis.suspiciousRegions.forEach { region ->
                        if (!region.bitmap.isRecycled) {
                            region.bitmap.recycle()
                        }
                    }
                    val weightedHScore = if (debugShowAllRois) {
                        structuralAnalysis.hScore
                    } else if (filteredDefects.isNotEmpty()) {
                        RoiSeverityScaler.calculateWeightedHScore(
                            baseSeverity = structuralAnalysis.hScore,
                            material = materialName,
                            defects = filteredDefects,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    } else {
                        (structuralAnalysis.hScore * DamageMultiplier.calculate(materialName, emptyList())).coerceIn(0f, 1f)
                    }
                    val localClipVerified = rawVerifiedDefects.isNotEmpty()
                    val severityScore = (
                        (0.7f * weightedHScore) +
                                (0.3f * structuralAnalysis.eScore)
                    ).coerceIn(0f, 1f)
                    val localSeverityLabel = when {
                        severityScore < 0.30f -> "Minor"
                        severityScore < 0.60f -> "Moderate"
                        else -> "Severe"
                    }
                    val defectLabels = rawVerifiedDefects.map { it.label }.distinct()
                    val localDefectSummary = if (defectLabels.isNotEmpty()) {
                        defectLabels.joinToString(", ")
                    } else {
                        "Normal Surface"
                    }

                    adjustedHScore = weightedHScore
                    eScore = structuralAnalysis.eScore
                    pixelCount = structuralAnalysis.pixelCount
                    clipVerified = localClipVerified
                    verifiedDefects = rawVerifiedDefects
                    rois = structuralAnalysis.suspiciousRegions.map { it.bounds }
                    severityLabel = localSeverityLabel
                    defectSummary = localDefectSummary

                    TechnicalLedgerRecord(
                        imageUri = uri.toString(),
                        location = normalizedLocation,
                        actvLocationTag = normalizedLocation,
                        material = materialName,
                        material_confidence = materialResult.materialConfidence,
                        material_predictions = materialResult.topMaterials,
                        material_result = materialSummary,
                        h_score = adjustedHScore,
                        e_score = eScore,
                        pixel_count = pixelCount,
                        clip_verified = clipVerified,
                        verified_defects = verifiedDefects,
                        rois = rois,
                        severity_label = severityLabel,
                        severity_score = severityScore,
                        timestamp = System.currentTimeMillis(),
                        damage = defectSummary,
                        severity = severityLabel,
                        H = adjustedHScore,
                        E = eScore
                    )
                } else {
                    buildFallbackLedger(uri, normalizedLocation).also {
                        materialName = it.material
                        adjustedHScore = it.h_score
                        eScore = it.e_score
                        pixelCount = it.pixel_count
                        clipVerified = it.clip_verified
                        verifiedDefects = it.verified_defects
                        rois = it.rois
                        severityLabel = it.severity_label
                        defectSummary = it.damage
                    }
                }

                localDb?.scanDao()?.insert(
                    ScanEntity(
                        location = normalizedLocation,
                        jsonData = gson.toJson(ledgerRecord),
                        timestamp = ledgerRecord.timestamp
                    )
                )

                withContext(Dispatchers.Main) {
                    val toastMessage = if (ledgerRecord.clip_verified) {
                        "DEFECT VERIFIED - Record Added to Ledger"
                    } else {
                        "NORMAL SURFACE - Record Added to Ledger"
                    }
                    Toast.makeText(this@CameraActivity, toastMessage, Toast.LENGTH_SHORT).show()

                    startActivity(
                        Intent(this@CameraActivity, ResultActivity::class.java).apply {
                            putExtra(ResultActivity.EXTRA_IMAGE_URI, uri.toString())
                            putExtra(ResultActivity.EXTRA_LOCATION_TAG, normalizedLocation)
                            putExtra(ResultActivity.EXTRA_LEDGER_JSON, gson.toJson(ledgerRecord))
                            putExtra(ResultActivity.EXTRA_MATERIAL, materialName)
                            putExtra(ResultActivity.EXTRA_SEVERITY_LABEL, severityLabel)
                            putExtra(ResultActivity.EXTRA_SEVERITY_SCORE, ledgerRecord.severity_score)
                            putExtra(ResultActivity.EXTRA_H_SCORE, adjustedHScore)
                            putExtra(ResultActivity.EXTRA_E_SCORE, eScore)
                            putExtra(ResultActivity.EXTRA_CLIP_VERIFIED, clipVerified)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error processing image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CameraActivity, "Failed to add record to ledger", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun loadAnalysisBitmap(uri: Uri): Bitmap? {
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        val oriented = applyExifOrientation(uri, decoded)
        if (oriented !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return oriented
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            } ?: ExifInterface.ORIENTATION_UNDEFINED
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
        }

        if (matrix.isIdentity) {
            return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun returnToMenu() {
        startActivity(
            Intent(this, MenuActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun initializeAnalysisStack() {
        classifier = runCatching { ClipClassifier(this) }
            .onFailure { Log.e("CameraActivity", "Classifier init failed", it) }
            .getOrNull()

        structuralAnalyzer = runCatching { OpenCvStructuralAnalyzer(this) }
            .onFailure { Log.e("CameraActivity", "OpenCV analyzer init failed", it) }
            .getOrNull()

        defectValidator = runCatching { MobileClipDefectValidator(this) }
            .onFailure { Log.e("CameraActivity", "Defect validator init failed", it) }
            .getOrNull()
    }

    private fun resolveMaterialForCapture(
        bitmap: android.graphics.Bitmap,
        location: String
    ): ClassificationResult {
        if (selectedMaterial != "Auto") {
            val localClassifier = classifier
            return if (localClassifier != null) {
                localClassifier.classify(bitmap, location, selectedMaterial)
            } else {
                ClassificationResult(
                    material = selectedMaterial,
                    damage = getString(R.string.unknown),
                    prompt = "Manual material selection in use.",
                    damageSignal = 0f,
                    severityScore = 0f,
                    severityLabel = getString(R.string.unknown),
                    materialConfidence = 0f
                )
            }
        }

        val recentMaterial = getRecentMaterialForLocation(location)
        if (recentMaterial != null && isTrustedAutoMaterial(recentMaterial)) {
            return recentMaterial
        }

        val localClassifier = classifier
        return if (localClassifier != null) {
            stabilizeAutoMaterial(
                localClassifier.classify(bitmap, location, selectedMaterial)
            )
        } else {
            ClassificationResult(
                material = getString(R.string.unknown),
                damage = getString(R.string.unknown),
                prompt = "No classifier available and no historical material found.",
                damageSignal = 0f,
                severityScore = 0f,
                severityLabel = getString(R.string.unknown),
                materialConfidence = 0f
            )
        }
    }

    private fun stabilizeAutoMaterial(result: ClassificationResult): ClassificationResult {
        if (selectedMaterial != "Auto") return result
        if (isTrustedAutoMaterial(result)) return result

        Log.d(
            "CameraActivity",
            "Auto material fallback to $AUTO_MATERIAL_FALLBACK " +
                "(predicted=${result.material}, confidence=${String.format(Locale.US, "%.3f", result.materialConfidence)}, " +
                "margin=${String.format(Locale.US, "%.3f", result.margin)})"
        )

        return result.copy(
            material = AUTO_MATERIAL_FALLBACK,
            prompt = "Low-confidence Auto material fallback"
        )
    }

    private fun isTrustedAutoMaterial(result: ClassificationResult): Boolean {
        if (selectedMaterial != "Auto") return true
        return result.materialConfidence >= AUTO_MATERIAL_CONFIDENCE_THRESHOLD &&
            result.margin >= AUTO_MATERIAL_MARGIN_THRESHOLD
    }

    private fun getRecentMaterialForLocation(location: String): ClassificationResult? {
        val localDb = db ?: return null
        val latestScan = localDb.scanDao()
            .getLastThreeScansByLocation(location)
            .firstOrNull()
            ?: return null

        val ledger = runCatching {
            gson.fromJson(latestScan.jsonData, TechnicalLedgerRecord::class.java)
        }.getOrNull() ?: return null

        val material = ledger.material.trim()
        if (
            material.isBlank() ||
            material.equals(getString(R.string.unknown), ignoreCase = true) ||
            material.equals("Material not recognized", ignoreCase = true)
        ) {
            return null
        }

            return ClassificationResult(
                material = material,
                damage = ledger.severity.ifBlank { ledger.damage.ifBlank { getString(R.string.unknown) } },
                prompt = "Reused material from location history.",
                damageSignal = ledger.E,
                severityScore = ledger.severity_score,
                severityLabel = ledger.severity_label.ifBlank { ledger.severity.ifBlank { getString(R.string.unknown) } },
                materialConfidence = if (ledger.material_result.isMeaningful()) {
                    ledger.material_result.confidence / 100f
                } else {
                    0f
                },
                topMaterials = ledger.material_result.top3.map { prediction ->
                    MaterialPredictionRecord(
                        label = prediction.material.replaceFirstChar { char ->
                            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                        },
                        confidence = prediction.confidence / 100f
                    )
                },
                rawSimilarity = ledger.material_result.raw_similarity,
                margin = ledger.material_result.margin,
                allScores = ledger.material_result.all_scores
        )
    }

    private fun MaterialResultRecord.isMeaningful(): Boolean {
        return material.isNotBlank() ||
            top3.isNotEmpty() ||
            all_scores.isNotEmpty() ||
            raw_similarity != 0f ||
            margin != 0f ||
            confidence != 0f
    }

    private fun buildFallbackLedger(uri: Uri, normalizedLocation: String): TechnicalLedgerRecord {
        return TechnicalLedgerRecord(
            imageUri = uri.toString(),
            location = normalizedLocation,
            actvLocationTag = normalizedLocation,
            material = getString(R.string.unknown),
            material_confidence = 0f,
            material_predictions = emptyList(),
            material_result = MaterialResultRecord(),
            h_score = 0f,
            e_score = 0f,
            pixel_count = 0,
            clip_verified = false,
            verified_defects = emptyList(),
            rois = emptyList(),
            severity_label = "Unknown",
            severity_score = 0f,
            timestamp = System.currentTimeMillis(),
            damage = "Unknown",
            severity = "Unknown",
            H = 0f,
            E = 0f
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        camera?.cameraControl?.enableTorch(false)
        shutterSound.release()
        classifier?.close()
        defectValidator?.close()
    }
}
