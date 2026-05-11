package com.example.specuraprototype
import kotlinx.coroutines.launch
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Locale

class CameraActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var classifier: ClipClassifier
    private lateinit var db: AppDatabase
    private lateinit var etLocationTag: EditText
    private val gson = Gson()

    // 1. Improved Permission Handling
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to perform scans.", Toast.LENGTH_LONG).show()
                finish() // Exit activity if permission denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        classifier = ClipClassifier(this)
        db = AppDatabase.getDatabase(this)
        
        etLocationTag = findViewById(R.id.etLocationTag)

        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            val input = etLocationTag.text.toString()
            if (LocationHelper.isValid(input)) {
                takePhoto()
            } else {
                Toast.makeText(this, "Please enter a location tag first", Toast.LENGTH_SHORT).show()
                etLocationTag.requestFocus()
            }
        }

        // Initial permission check
        checkPermissions()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        
        // Using MediaStore avoids FileUriExposedException because it returns a content:// URI
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpecuraPrototype")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraActivity", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return

                    // Use lifecycleScope to run the heavy AI stuff in the background
                    lifecycleScope.launch(Dispatchers.Default) {
                        processCapturedImage(savedUri)
                    }
                }

            }
        )
    }

    private fun processCapturedImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                val rawInput = etLocationTag.text.toString()
                val normalizedTag = LocationHelper.normalizeLocationTag(rawInput)

                // 1. RUN AI IN BACKGROUND (This is safe here because of Dispatchers.Default)
                val result = classifier.classify(bitmap, normalizedTag)

                // 2. SAVE TO DB IN BACKGROUND
                saveScanToDatabase(normalizedTag, result, uri.toString())

                // 3. SWITCH TO MAIN THREAD ONLY FOR THE INTENT
                runOnUiThread {
                    val intent = Intent(this@CameraActivity, ResultActivity::class.java).apply {
                        putExtra("imageUri", uri.toString())
                        putExtra("locationTag", normalizedTag)
                        putExtra("material", result.material)
                        putExtra("damage", result.damage)
                        putExtra("confidence", result.confidence)
                        putExtra("prompt", result.prompt)
                        putExtra("damageSignal", result.damageSignal)
                        putExtra("severityScore", result.severityScore)
                        putExtra("severityLabel", result.severityLabel)
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error processing image", e)
        }
    }

    private fun saveScanToDatabase(location: String, result: ClassificationResult, imageUri: String) {
        val dataMap = mapOf(
            "material" to result.material,
            "damage" to result.damage,
            "confidence" to result.confidence,
            "severity" to result.severityLabel,
            "E" to result.damageSignal,
            "H" to result.severityScore,
            "imageUri" to imageUri
        )

        val jsonString = gson.toJson(dataMap)

        val entity = ScanEntity(
            location = location,
            jsonData = jsonString,
            timestamp = System.currentTimeMillis()
        )
        // Note: In a real app, this should also be moved off the main thread.
        // But since you have allowMainThreadQueries() in AppDatabase, it works for now.
        db.scanDao().insert(entity)
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }
}
