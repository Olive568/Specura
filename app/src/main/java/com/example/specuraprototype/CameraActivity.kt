package com.example.specuraprototype

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
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
import java.text.SimpleDateFormat
import java.util.Locale
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


class CameraActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            takePhoto()
        }

        // Ask permission if needed, then start preview
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }




    private fun takePhoto() {
        val capture = imageCapture ?: return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val fileName = "SPECURA_$timeStamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

            // ✅ Your requested folder: Pictures/Specura/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Specura")
            }
        }


        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraActivity", "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(this@CameraActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri?.toString() ?: return

                    saveToHistory(uri)
                    showDetectionWindow()

                    // Optional: go back to Menu so you can see it in Recent Photos


                }
            }
        )
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

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }
    private fun showDetectionWindow() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_detection)

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.6f)

        dialog.findViewById<Button>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
            // Camera remains active after closing
        }

        dialog.show()
    }
    private fun saveToHistory(imageUri: String) {
        val prefs = getSharedPreferences("specura_history", MODE_PRIVATE)
        val gson = Gson()

        val existingJson = prefs.getString("items", null)
        val type = object : TypeToken<MutableList<HistoryItem>>() {}.type

        val historyList: MutableList<HistoryItem> =
            if (existingJson != null) {
                gson.fromJson(existingJson, type)
            } else {
                mutableListOf()
            }

        historyList.add(
            0,
            HistoryItem(
                imageUri = imageUri,
                detectedMaterial = "Material detected: Placeholder\nLorem ipsum dolor sit amet...",
                timestamp = System.currentTimeMillis()
            )
        )

        prefs.edit()
            .putString("items", gson.toJson(historyList))
            .apply()
    }


}
