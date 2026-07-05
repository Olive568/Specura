package com.example.specuraprototype

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        db = AppDatabase.getDatabase(this)

        val ledgerJson = intent.getStringExtra(EXTRA_LEDGER_JSON)
        val ledgerRecord = ledgerJson?.let {
            runCatching { gson.fromJson(it, TechnicalLedgerRecord::class.java) }.getOrNull()
        }

        val imageUri = ledgerRecord?.imageUri
            ?: intent.getStringExtra(EXTRA_IMAGE_URI)
            ?: intent.getStringExtra("imageUri")

        val locationTag = ledgerRecord?.actvLocationTag
            ?: intent.getStringExtra(EXTRA_LOCATION_TAG)
            ?: "Unknown"

        val material = ledgerRecord?.material
            ?: intent.getStringExtra(EXTRA_MATERIAL)
            ?: intent.getStringExtra("material")
            ?: getString(R.string.unknown)

        val severityLabel = ledgerRecord?.severity_label
            ?: intent.getStringExtra(EXTRA_SEVERITY_LABEL)
            ?: intent.getStringExtra("severityLabel")
            ?: getString(R.string.unknown)

        val severityScore = ledgerRecord?.severity_score
            ?: intent.getFloatExtra(EXTRA_SEVERITY_SCORE, intent.getFloatExtra("severityScore", 0.0f))

        val hScore = ledgerRecord?.h_score
            ?: intent.getFloatExtra(EXTRA_H_SCORE, intent.getFloatExtra("H", 0.0f))

        val eScore = ledgerRecord?.e_score
            ?: intent.getFloatExtra(EXTRA_E_SCORE, intent.getFloatExtra("E", 0.0f))

        val materialSummary = ledgerRecord?.material_result?.takeIf { it.isMeaningful() }
        val materialConfidence = materialSummary?.confidence?.div(100f) ?: 0f
        val topMaterials = materialSummary?.top3?.map { prediction ->
            MaterialPredictionRecord(
                label = prediction.material.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                },
                confidence = prediction.confidence / 100f
            )
        }.orEmpty()

        val clipVerified = ledgerRecord?.clip_verified
            ?: intent.getBooleanExtra(EXTRA_CLIP_VERIFIED, intent.getBooleanExtra("clip_verified", false))

        val verifiedDefects = ledgerRecord?.verified_defects.orEmpty()
        val hasDefects = verifiedDefects.isNotEmpty()
        val timestamp = ledgerRecord?.timestamp
            ?: intent.getLongExtra("timestamp", System.currentTimeMillis())

        val imageView = findViewById<ImageView>(R.id.resultImage)
        val severityView = findViewById<TextView>(R.id.resultSeverity)
        val materialView = findViewById<TextView>(R.id.resultMaterial)
        val locationView = findViewById<TextView>(R.id.resultLocationTag)
        val ledgerStatusView = findViewById<TextView>(R.id.resultLedgerStatus)
        val verifiedDefectsView = findViewById<TextView>(R.id.resultVerifiedDefects)
        val materialConfidenceView = findViewById<TextView>(R.id.resultMaterialConfidence)
        val topMaterialsView = findViewById<TextView>(R.id.resultTopMaterials)
        val hScoreView = findViewById<TextView>(R.id.resultHScore)
        val eScoreView = findViewById<TextView>(R.id.resultEScore)
        val timestampView = findViewById<TextView>(R.id.resultTimestamp)
        val showDefectsButton = findViewById<Button>(R.id.btnShowDefects)

        val btnKeep = findViewById<Button>(R.id.btnKeep)
        val btnDelete = findViewById<Button>(R.id.btnDelete)

        if (!imageUri.isNullOrBlank()) {
            imageView.setImageURI(Uri.parse(imageUri))
        }

        materialView.text = getString(R.string.result_material_format, material)
        materialConfidenceView.text = getString(
            R.string.result_material_confidence_format,
            formatConfidence(materialConfidence)
        )
        materialConfidenceView.visibility = View.VISIBLE
        topMaterialsView.text = if (topMaterials.isNotEmpty()) {
            getString(R.string.result_top_materials_format, formatTopMaterials(topMaterials))
        } else {
            getString(R.string.result_top_materials_unavailable)
        }
        topMaterialsView.visibility = View.VISIBLE
        locationView.text = getString(R.string.result_location_format, locationTag)
        ledgerStatusView.text = getString(
            R.string.result_status_format,
            if (hasDefects) getString(R.string.ledger_status_verified) else getString(R.string.ledger_status_intact)
        )
        severityView.text = if (hasDefects) {
            severityLabel.uppercase(Locale.getDefault())
        } else {
            getString(R.string.ledger_status_intact)
        }
        severityView.background = buildSeverityBadge(severityLabel, clipVerified, hasDefects)
        if (hasDefects) {
            verifiedDefectsView.text = getString(
                R.string.result_verified_defects_format,
                formatVerifiedDefects(verifiedDefects)
            )
            verifiedDefectsView.visibility = View.VISIBLE
            hScoreView.visibility = View.VISIBLE
            eScoreView.visibility = View.VISIBLE
            hScoreView.text = getString(R.string.result_h_score_format, formatScore(hScore))
            eScoreView.text = getString(R.string.result_e_score_format, formatScore(eScore))
        } else {
            verifiedDefectsView.text = getString(R.string.no_defects)
            verifiedDefectsView.visibility = View.VISIBLE
            hScoreView.visibility = View.GONE
            eScoreView.visibility = View.GONE
        }
        timestampView.text = getString(R.string.result_timestamp_format, formatTimestamp(timestamp))

        showDefectsButton.visibility = if (imageUri.isNullOrBlank()) View.GONE else View.VISIBLE
        showDefectsButton.setOnClickListener {
            val overlayDefects = if (ledgerRecord?.verified_defects.orEmpty().isNotEmpty()) {
                ledgerRecord?.verified_defects.orEmpty()
            } else {
                ledgerRecord?.rois.orEmpty().map { roi ->
                    VerifiedDefectRecord(
                        label = "?",
                        roi = roi
                    )
                }
            }
            DefectOverlayDialogHelper.showDefectOverlayDialog(
                this,
                imageUri,
                overlayDefects
            )
        }

        btnKeep.setOnClickListener {
            finish()
        }

        btnDelete.setOnClickListener {
            deleteLedgerEntry(imageUri)
        }
    }

    private fun deleteLedgerEntry(imageUri: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!imageUri.isNullOrBlank()) {
                try {
                    db.scanDao().deleteScanByImageUri(imageUri)
                } catch (e: Exception) {
                    Log.e("ResultActivity", "Failed to delete ledger row", e)
                }

                try {
                    contentResolver.delete(Uri.parse(imageUri), null, null)
                } catch (e: Exception) {
                    Log.e("ResultActivity", "Failed to delete image file", e)
                }
            }

            withContext(Dispatchers.Main) {
                AppSettings.setLastActivity(this@ResultActivity, "Deleted a file")
                Toast.makeText(this@ResultActivity, "Result discarded", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun buildSeverityBadge(
        severityLabel: String,
        clipVerified: Boolean,
        hasDefects: Boolean
    ): GradientDrawable {
        val normalized = severityLabel.lowercase(Locale.getDefault())
        val color = when {
            !hasDefects -> Color.parseColor("#546E7A")
            normalized.contains("severe") -> Color.parseColor("#C62828")
            normalized.contains("moderate") -> Color.parseColor("#EF6C00")
            normalized.contains("minor") -> Color.parseColor("#2E7D32")
            clipVerified -> Color.parseColor("#1565C0")
            else -> Color.parseColor("#546E7A")
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(color)
        }
    }

    private fun formatVerifiedDefects(defects: List<VerifiedDefectRecord>): String {
        if (defects.isEmpty()) {
            return getString(R.string.no_defects)
        }

        val counts = linkedMapOf<String, Int>()
        val displayLabels = linkedMapOf<String, String>()
        defects.forEach { defect ->
            val raw = defect.label.trim()
            if (raw.isBlank()) return@forEach
            val key = raw.lowercase(Locale.getDefault())
            counts[key] = (counts[key] ?: 0) + 1
            if (!displayLabels.containsKey(key)) {
                displayLabels[key] = raw.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
            }
        }

        return counts.entries.joinToString(separator = "\n") { entry ->
            val label = displayLabels[entry.key].orEmpty()
            if (entry.value > 1) "- $label (${entry.value})" else "- $label"
        }
    }

    private fun formatScore(score: Float): String =
        String.format(Locale.US, "%.4f", score)

    private fun formatConfidence(score: Float): String {
        val percentValue = if (score <= 1f) {
            score.coerceIn(0f, 1f) * 100f
        } else {
            score
        }
        return String.format(Locale.US, "%.2f%%", percentValue)
    }

    private fun formatTopMaterials(materials: List<MaterialPredictionRecord>): String {
        if (materials.isEmpty()) {
            return getString(R.string.result_top_materials_unavailable)
        }

        return materials.take(3).mapIndexed { index, item ->
            "${index + 1}. ${item.label} - ${formatConfidence(item.confidence)}"
        }.joinToString(separator = "\n")
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    private fun MaterialResultRecord.isMeaningful(): Boolean {
        return material.isNotBlank() ||
            top3.isNotEmpty() ||
            all_scores.isNotEmpty() ||
            raw_similarity != 0f ||
            margin != 0f ||
            confidence != 0f
    }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_LOCATION_TAG = "extra_location_tag"
        const val EXTRA_LEDGER_JSON = "extra_ledger_json"
        const val EXTRA_MATERIAL = "extra_material"
        const val EXTRA_SEVERITY_LABEL = "extra_severity_label"
        const val EXTRA_SEVERITY_SCORE = "extra_severity_score"
        const val EXTRA_H_SCORE = "extra_h_score"
        const val EXTRA_E_SCORE = "extra_e_score"
        const val EXTRA_CLIP_VERIFIED = "extra_clip_verified"
    }
}
