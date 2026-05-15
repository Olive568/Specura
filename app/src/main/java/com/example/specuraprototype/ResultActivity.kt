package com.example.specuraprototype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        db = AppDatabase.getDatabase(this)

        val imageUri = intent.getStringExtra("imageUri")
        val locationTag = intent.getStringExtra("locationTag") ?: "Unknown"
        val confidence = intent.getFloatExtra("confidence", 0.0f)
        val material = intent.getStringExtra("material")
        val damage = intent.getStringExtra("damage")
        val severityLabel = intent.getStringExtra("severityLabel")
        val severityScore = intent.getFloatExtra("severityScore", 0.0f)
        val damageSignal = intent.getFloatExtra("damageSignal", 0.0f)

        val imageView = findViewById<ImageView>(R.id.resultImage)
        val materialView = findViewById<TextView>(R.id.resultMaterial)
        val conditionView = findViewById<TextView>(R.id.resultCondition)
        val confidenceView = findViewById<TextView>(R.id.resultConfidence)
        val severityView = findViewById<TextView>(R.id.resultSeverity)
        
        val btnKeep = findViewById<Button>(R.id.btnKeep)
        val btnDelete = findViewById<Button>(R.id.btnDelete)
        val closeButton = findViewById<Button>(R.id.btnClose)

        if (imageUri != null) {
            imageView.setImageURI(Uri.parse(imageUri))
        }

        val unknown = getString(R.string.unknown)

        // 1. Material at the top
        materialView.text = getString(R.string.result_material_format, material ?: unknown)
        
        // 2. Condition (Damage type)
        conditionView.text = getString(R.string.result_condition_format, damage ?: unknown)

        // 3. Confidence score
        confidenceView.text = getString(R.string.result_confidence_format, formatScore(confidence))

        // 4. Logic for "Intact" material: Hide severity if condition is Intact
        val isIntact = damage?.contains("Intact", ignoreCase = true) == true
        
        if (isIntact) {
            severityView.visibility = View.GONE
        } else {
            severityView.visibility = View.VISIBLE
            severityView.text = getString(
                R.string.result_severity_format,
                severityLabel ?: unknown,
                formatScore(severityScore)
            )
        }

        btnKeep.setOnClickListener {
            saveToHistory(locationTag, material, damage, confidence, severityLabel, damageSignal, severityScore, imageUri)
            Toast.makeText(this, "Saved to history", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnDelete.setOnClickListener {
            deleteResult(imageUri)
            Toast.makeText(this, "Result discarded", Toast.LENGTH_SHORT).show()
            finish()
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun saveToHistory(
        location: String,
        material: String?,
        damage: String?,
        confidence: Float,
        severityLabel: String?,
        damageSignal: Float,
        severityScore: Float,
        imageUri: String?
    ) {
        val dataMap = mapOf(
            "material" to (material ?: "Unknown"),
            "damage" to (damage ?: "Unknown"),
            "confidence" to confidence,
            "severity" to (severityLabel ?: "Unknown"),
            "E" to damageSignal,
            "H" to severityScore,
            "imageUri" to (imageUri ?: "")
        )

        val jsonString = gson.toJson(dataMap)

        val entity = ScanEntity(
            location = location,
            jsonData = jsonString,
            timestamp = System.currentTimeMillis()
        )
        
        try {
            db.scanDao().insert(entity)
        } catch (e: Exception) {
            Log.e("ResultActivity", "Failed to save scan", e)
        }
    }

    private fun deleteResult(imageUri: String?) {
        if (imageUri != null) {
            try {
                contentResolver.delete(Uri.parse(imageUri), null, null)
            } catch (e: Exception) {
                Log.e("ResultActivity", "Failed to delete image file", e)
            }
        }
    }

    private fun formatScore(score: Float): String =
        String.format(Locale.US, "%.4f", score)
}
