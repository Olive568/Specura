package com.example.specuraprototype

import kotlin.math.min

object RoiSeverityScaler {

    fun filterForSeverity(
        material: String,
        defects: List<VerifiedDefectRecord>,
        imageWidth: Int,
        imageHeight: Int
    ): List<VerifiedDefectRecord> {
        if (defects.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return emptyList()
        }

        val imageArea = (imageWidth.toLong() * imageHeight.toLong()).toDouble().coerceAtLeast(1.0)
        return defects.filter { defect ->
            val normalizedLabel = normalizedLabel(defect.label)
            val roi = defect.roi
            val roiWidth = roi.width.coerceAtLeast(0)
            val roiHeight = roi.height.coerceAtLeast(0)
            if (roiWidth == 0 || roiHeight == 0) {
                return@filter false
            }

            if (!DamageMultiplier.isAllowedForMaterial(material, normalizedLabel)) {
                return@filter false
            }

            val roiArea = (roiWidth.toLong() * roiHeight.toLong()).toDouble()
            val areaWeight = min(roiArea / imageArea, 1.0)
            areaWeight >= minimumAreaWeight(material, normalizedLabel)
        }.sortedByDescending { defect ->
            severityContribution(
                baseSeverity = 1f,
                material = material,
                defect = defect,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }

    fun calculateWeightedHScore(
        baseSeverity: Float,
        material: String,
        defects: List<VerifiedDefectRecord>,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        if (defects.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return 0f
        }

        val filteredDefects = filterForSeverity(material, defects, imageWidth, imageHeight)
        if (filteredDefects.isEmpty()) {
            return 0f
        }

        val contributions = filteredDefects.map { defect ->
            severityContribution(
                baseSeverity = baseSeverity,
                material = material,
                defect = defect,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }.filter { it > 0f }

        if (contributions.isEmpty()) {
            return 0f
        }

        val ordered = contributions.sortedDescending()
        var aggregated = 0f
        ordered.forEachIndexed { index, contribution ->
            aggregated += contribution * diminishingReturns(index)
        }

        return aggregated.coerceIn(0f, 1f)
    }

    private fun severityContribution(
        baseSeverity: Float,
        material: String,
        defect: VerifiedDefectRecord,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val roi = defect.roi
        val roiWidth = roi.width.coerceAtLeast(0)
        val roiHeight = roi.height.coerceAtLeast(0)
        if (roiWidth == 0 || roiHeight == 0) {
            return 0f
        }

        val imageArea = (imageWidth.toLong() * imageHeight.toLong()).toDouble().coerceAtLeast(1.0)
        val roiArea = (roiWidth.toLong() * roiHeight.toLong()).toDouble()
        val areaWeight = min(roiArea / imageArea, 1.0)
        val normalizedLabel = normalizedLabel(defect.label)
        if (areaWeight < minimumAreaWeight(material, normalizedLabel)) {
            return 0f
        }

        val materialMultiplier = DamageMultiplier.defectMultiplier(material, normalizedLabel)
        val priorityWeight = DamageMultiplier.defectPriority(normalizedLabel)
        val continuityBoost = if (normalizedLabel.equals("crack", ignoreCase = true) && isCrackLike(defect.roi)) {
            1.12f
        } else {
            1.0f
        }
        return baseSeverity * materialMultiplier * priorityWeight * areaWeight.toFloat() * continuityBoost
    }

    private fun minimumAreaWeight(material: String, defectLabel: String): Double {
        return when (normalizedLabel(defectLabel)) {
            "stain" -> when (material.lowercase()) {
                "wood" -> 0.012
                "metal" -> 0.010
                else -> 0.018
            }
            "deterioration" -> when (material.lowercase()) {
                "wood" -> 0.010
                "metal" -> 0.010
                else -> 0.011
            }
            "corrosion" -> 0.008
            "crack" -> 0.0025
            "chipping" -> when (material.lowercase()) {
                "wood" -> 0.008
                "metal" -> 0.008
                else -> 0.007
            }
            else -> 0.012
        }
    }

    private fun normalizedLabel(defectLabel: String): String {
        return when (defectLabel.lowercase()) {
            "possible crack" -> "crack"
            else -> defectLabel.lowercase()
        }
    }

    private fun isCrackLike(roi: RoiBounds): Boolean {
        val width = roi.width.coerceAtLeast(0)
        val height = roi.height.coerceAtLeast(0)
        if (width == 0 || height == 0) return false

        val aspectRatio = maxOf(
            width.toDouble() / height.toDouble(),
            height.toDouble() / width.toDouble()
        )
        return aspectRatio >= 2.8
    }

    private fun diminishingReturns(index: Int): Float {
        return when (index) {
            0 -> 1.0f
            1 -> 0.65f
            2 -> 0.42f
            3 -> 0.28f
            4 -> 0.20f
            else -> 0.14f
        }
    }
}
