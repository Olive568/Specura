package com.example.specuraprototype

import java.util.Locale

object DamageMultiplier {

    fun calculate(material: String, defects: List<VerifiedDefectRecord>): Float {
        if (defects.isEmpty()) {
            return 0.35f
        }

        val normalizedMaterial = material.lowercase(Locale.getDefault())
        val allowedDefects = defects.filter { defect ->
            isAllowedForMaterial(normalizedMaterial, defect.label)
        }

        if (allowedDefects.isEmpty()) {
            return 0.35f
        }

        val weights = allowedDefects.map { defect ->
            defectMultiplier(normalizedMaterial, defect.label)
        }

        val averageWeight = weights.average().toFloat()
        val stackBonus = ((weights.size - 1) * 0.08f)

        return (averageWeight + stackBonus).coerceIn(0.55f, 1.65f)
    }

    fun defectMultiplier(material: String, defectLabel: String): Float {
        val label = normalizeLabel(defectLabel)

        return when (material) {
            "wood" -> when (label) {
                "crack" -> 1.22f
                "corrosion" -> 0.70f
                "chipping" -> 1.12f
                "stain" -> 1.42f
                "deterioration" -> 1.34f
                else -> 1.00f
            }
            "concrete" -> when (label) {
                "crack" -> 1.42f
                "corrosion" -> 0.72f
                "chipping" -> 1.28f
                "stain" -> 0.68f
                "deterioration" -> 1.08f
                else -> 1.00f
            }
            "brick" -> when (label) {
                "crack" -> 1.36f
                "corrosion" -> 0.76f
                "chipping" -> 1.22f
                "stain" -> 0.70f
                "deterioration" -> 1.12f
                else -> 1.00f
            }
            "metal" -> when (label) {
                "crack" -> 1.12f
                "corrosion" -> 1.48f
                "chipping" -> 0.92f
                "stain" -> 0.68f
                "deterioration" -> 1.06f
                else -> 1.00f
            }
            else -> when (label) {
                "crack" -> 1.20f
                "corrosion" -> 1.05f
                "chipping" -> 1.00f
                "stain" -> 0.85f
                "deterioration" -> 1.10f
                else -> 1.00f
            }
        }
    }

    fun defectPriority(defectLabel: String): Float {
        return when (normalizeLabel(defectLabel)) {
            "deterioration" -> 1.25f
            "corrosion" -> 1.15f
            "crack" -> 1.05f
            "chipping" -> 0.92f
            "stain" -> 0.75f
            else -> 1.00f
        }
    }

    fun isAllowedForMaterial(material: String, defectLabel: String): Boolean {
        val normalizedMaterial = material.lowercase(Locale.getDefault())
        val normalizedLabel = normalizeLabel(defectLabel)
        return when (normalizedMaterial) {
            "concrete", "brick" -> normalizedLabel in setOf("crack", "chipping", "deterioration")
            "metal" -> normalizedLabel in setOf("corrosion", "deterioration")
            "wood" -> normalizedLabel in setOf("deterioration", "chipping")
            else -> normalizedLabel in setOf("crack", "corrosion", "chipping", "stain", "deterioration")
        }
    }

    private fun normalizeLabel(defectLabel: String): String {
        val normalized = defectLabel.lowercase(Locale.getDefault())
        return when (normalized) {
            "possible crack" -> "crack"
            else -> normalized
        }
    }
}
