package com.example.specuraprototype

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var classifier: ClipClassifier
    private lateinit var db: AppDatabase
    private lateinit var actvLocationTag: AutoCompleteTextView
    private lateinit var ghostOverlay: ImageView
    
    // Material Selection UI
    private lateinit var toggleMaterialGroup: MaterialButtonToggleGroup
    private lateinit var tvManualModeHint: TextView
    private var selectedMaterial: String = "Auto"
    
    private val gson = Gson()
    private val scanDataType = object : TypeToken<Map<String, Any>>() {}.type

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        classifier = ClipClassifier(this)
        db = AppDatabase.getDatabase(this)
        
        actvLocationTag = findViewById(R.id.actvLocationTag)
        ghostOverlay = findViewById(R.id.ghostOverlay)
        
        // Material Selector Setup
        toggleMaterialGroup = findViewById(R.id.toggleMaterialGroup)
        tvManualModeHint = findViewById(R.id.tvManualModeHint)
        setupMaterialSelector()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
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
        
        // Handle incoming Intent extras for Ghost Mode
        val initialLocation = intent.getStringExtra(EXTRA_LOCATION_TAG)
        val initialImagePath = intent.getStringExtra(EXTRA_PREVIOUS_IMAGE_PATH)
        
        if (!initialLocation.isNullOrBlank()) {
            actvLocationTag.setText(initialLocation)
        }
        if (!initialImagePath.isNullOrBlank()) {
            loadGhostImage(initialImagePath)
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
        // Populate dropdown with unique locations from DB
        lifecycleScope.launch(Dispatchers.IO) {
            val locations = db.scanDao().getUniqueLocations()
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@CameraActivity, android.R.layout.simple_dropdown_item_1line, locations)
                actvLocationTag.setAdapter(adapter)
            }
        }

        // Trigger ghost image fetch upon selection
        actvLocationTag.setOnItemClickListener { parent, _, position, _ ->
            val selectedTag = parent.getItemAtPosition(position) as String
            fetchAndShowGhostImage(selectedTag)
        }

        // Hide ghost overlay if user types a new name
        actvLocationTag.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ghostOverlay.visibility = View.GONE
            }
        })
    }

    private fun fetchAndShowGhostImage(locationTag: String) {
        val normalizedTag = LocationHelper.normalizeLocationTag(locationTag)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Search database for the latest ScanEntity for this location
            val scans = db.scanDao().getLastThreeScansByLocation(normalizedTag)
            val latestScan = scans.firstOrNull()
            
            withContext(Dispatchers.Main) {
                if (latestScan != null) {
                    val data: Map<String, Any> = gson.fromJson(latestScan.jsonData, scanDataType)
                    val uri = data["imageUri"] as? String
                    if (!uri.isNullOrBlank()) {
                        loadGhostImage(uri)
                    } else {
                        ghostOverlay.visibility = View.GONE
                    }
                } else {
                    ghostOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun loadGhostImage(uriString: String) {
        ghostOverlay.load(Uri.parse(uriString)) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    ghostOverlay.visibility = View.VISIBLE
                },
                onError = { _, _ ->
                    ghostOverlay.visibility = View.GONE
                }
            )
        }
    }

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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("CameraActivity", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        // Safety check: hide overlay immediately on capture
        ghostOverlay.visibility = View.GONE

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
                output.savedUri?.let { processCapturedImage(it) }
            }
        })
    }

    private fun processCapturedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@launch
                val result = classifier.classify(
                    bitmap, 
                    LocationHelper.normalizeLocationTag(actvLocationTag.text.toString()),
                    selectedMaterial
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(this@CameraActivity, ResultActivity::class.java).apply {
                        putExtra("imageUri", uri.toString())
                        putExtra("locationTag", LocationHelper.normalizeLocationTag(actvLocationTag.text.toString()))
                        putExtra("material", result.material)
                        putExtra("damage", result.damage)
                        putExtra("confidence", result.confidence)
                        putExtra("severityLabel", result.severityLabel)
                        putExtra("severityScore", result.severityScore)
                        putExtra("damageSignal", result.damageSignal)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error processing image", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }

    companion object {
        const val EXTRA_LOCATION_TAG = "extra_location_tag"
        const val EXTRA_PREVIOUS_IMAGE_PATH = "extra_previous_image_path"
    }
}
