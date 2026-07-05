package com.example.specuraprototype

data class RoiBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val area: Double = 0.0
)

data class SuspiciousRegion(
    val bounds: RoiBounds,
    val bitmap: android.graphics.Bitmap
)

data class VerifiedDefectRecord(
    val label: String,
    val roi: RoiBounds = RoiBounds(0, 0, 0, 0, 0.0)
)

data class MaterialPredictionRecord(
    val label: String = "",
    val confidence: Float = 0f
)

data class MaterialTopRecord(
    val material: String = "",
    val confidence: Float = 0f
)

data class MaterialResultRecord(
    val material: String = "",
    val confidence: Float = 0f,
    val raw_similarity: Float = 0f,
    val margin: Float = 0f,
    val top3: List<MaterialTopRecord> = emptyList(),
    val all_scores: Map<String, Float> = emptyMap()
)

data class TechnicalLedgerRecord(
    val imageUri: String = "",
    val location: String = "",
    val actvLocationTag: String = "",
    val material: String = "",
    val material_confidence: Float = 0f,
    val material_predictions: List<MaterialPredictionRecord> = emptyList(),
    val material_result: MaterialResultRecord = MaterialResultRecord(),
    val h_score: Float = 0f,
    val e_score: Float = 0f,
    val pixel_count: Int = 0,
    val clip_verified: Boolean = false,
    val verified_defects: List<VerifiedDefectRecord> = emptyList(),
    val rois: List<RoiBounds> = emptyList(),
    val severity_label: String = "",
    val severity_score: Float = 0f,
    val timestamp: Long = 0L,
    val schema_version: Int = 2,
    val damage: String = "",
    val severity: String = "",
    val H: Float = 0f,
    val E: Float = 0f
)

data class OpenCvAnalysis(
    val hScore: Float,
    val eScore: Float,
    val pixelCount: Int,
    val suspiciousRegions: List<SuspiciousRegion>
)
