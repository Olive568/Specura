package com.example.specuraprototype

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val imageUri = intent.getStringExtra("imageUri")
        val prompt = intent.getStringExtra("prompt")
        val confidence = intent.getFloatExtra("confidence", 0.0f)
        val damage = intent.getStringExtra("damage")
        val severityLabel = intent.getStringExtra("severityLabel")
        val severityScore = intent.getFloatExtra("severityScore", 0.0f)

        val imageView = findViewById<ImageView>(R.id.resultImage)
        val promptView = findViewById<TextView>(R.id.resultPrompt)
        val confidenceView = findViewById<TextView>(R.id.resultConfidence)
        val damageView = findViewById<TextView>(R.id.resultDamage)
        val severityView = findViewById<TextView>(R.id.resultSeverity)
        val closeButton = findViewById<Button>(R.id.btnClose)

        if (imageUri != null) {
            imageView.setImageURI(Uri.parse(imageUri))
        }

        promptView.text = "→ $prompt"
        confidenceView.text = "Confidence: ${String.format("%.4f", confidence)}"
        
        if (damage?.contains("Normal", ignoreCase = true) == true || 
            damage?.contains("Clean", ignoreCase = true) == true ||
            damage?.contains("Raw", ignoreCase = true) == true) {
            damageView.text = "No strong damage detected"
            severityView.text = ""
        } else {
            damageView.text = "Detected: $damage"
            severityView.text = "Severity: $severityLabel (${String.format("%.4f", severityScore)})"
        }

        closeButton.setOnClickListener {
            finish()
        }
    }
}
