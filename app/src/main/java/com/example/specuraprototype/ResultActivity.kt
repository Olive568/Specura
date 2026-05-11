package com.example.specuraprototype

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val imageUri = intent.getStringExtra("imageUri")
        val prompt = intent.getStringExtra("prompt")
        val confidence = intent.getFloatExtra("confidence", 0.0f)
        val material = intent.getStringExtra("material")
        val damage = intent.getStringExtra("damage")
        val severityLabel = intent.getStringExtra("severityLabel")
        val severityScore = intent.getFloatExtra("severityScore", 0.0f)
        val damageSignal = intent.getFloatExtra("damageSignal", 0.0f)

        val imageView = findViewById<ImageView>(R.id.resultImage)
        val promptView = findViewById<TextView>(R.id.resultPrompt)
        val confidenceView = findViewById<TextView>(R.id.resultConfidence)
        val damageView = findViewById<TextView>(R.id.resultDamage)
        val severityView = findViewById<TextView>(R.id.resultSeverity)
        val closeButton = findViewById<Button>(R.id.btnClose)

        if (imageUri != null) {
            imageView.setImageURI(Uri.parse(imageUri))
        }

        val unknown = getString(R.string.unknown)

        promptView.text = getString(R.string.result_prompt_format, prompt ?: unknown)
        confidenceView.text = getString(R.string.result_confidence_format, formatScore(confidence))

        val lowConfidenceWarning =
            if (confidence < 0.45f) {
                getString(R.string.low_confidence_warning)
            } else {
                ""
            }

        damageView.text = getString(
            R.string.result_damage_format,
            lowConfidenceWarning,
            material ?: unknown,
            damage ?: unknown,
            formatScore(damageSignal)
        ).trim()

        severityView.text = getString(
            R.string.result_severity_format,
            severityLabel ?: unknown,
            formatScore(severityScore)
        )

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun formatScore(score: Float): String =
        String.format(Locale.US, "%.4f", score)
}
