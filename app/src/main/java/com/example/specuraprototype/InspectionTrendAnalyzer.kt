package com.example.specuraprototype

import com.google.gson.Gson
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

data class InspectionTrendOverview(
    val trendType: InspectionTrendType,
    val trendLabel: String,
    val lastInspectionDate: String,
    val daysSinceLastInspection: String
)

enum class InspectionTrendType {
    NEW,
    STABLE,
    WORSENING
}

object InspectionTrendAnalyzer {

    fun analyze(
        scans: List<ScanEntity>,
        gson: Gson,
        scanDataType: Type
    ): InspectionTrendOverview? {
        if (scans.isEmpty()) return null

        val sortedScans = scans.sortedBy { it.timestamp }
        val current = sortedScans.last()
        val currentData: Map<String, Any> = gson.fromJson(current.jsonData, scanDataType)
        val currentSeverity = extractScore(currentData, "h_score", "H")
        val currentDefects = extractDefectLabels(currentData)

        val previousScans = sortedScans.dropLast(1)
        val lastInspection = previousScans.lastOrNull()
        val lastInspectionDate = lastInspection?.let { formatDateOnly(it.timestamp) } ?: "N/A"
        val daysSinceLastInspection = lastInspection?.let {
            elapsedDays(current.timestamp, it.timestamp).toString()
        } ?: "N/A"

        val matchedPrevious = previousScans
            .asReversed()
            .firstOrNull { previous ->
                val previousData: Map<String, Any> = gson.fromJson(previous.jsonData, scanDataType)
                val previousDefects = extractDefectLabels(previousData)
                currentDefects.isNotEmpty() && previousDefects.intersect(currentDefects).isNotEmpty()
            }

        val trendType = when {
            currentDefects.isEmpty() -> InspectionTrendType.STABLE
            matchedPrevious == null -> InspectionTrendType.NEW
            else -> {
                val previousData: Map<String, Any> = gson.fromJson(matchedPrevious.jsonData, scanDataType)
                val previousSeverity = extractScore(previousData, "h_score", "H")
                val recurrenceCount = previousScans.count { previous ->
                    val previousMap: Map<String, Any> = gson.fromJson(previous.jsonData, scanDataType)
                    val previousLabels = extractDefectLabels(previousMap)
                    previousLabels.intersect(currentDefects).isNotEmpty()
                } + 1

                classifyConditionTrend(
                    currentSeverity = currentSeverity,
                    previousSeverity = previousSeverity,
                    currentTimestamp = current.timestamp,
                    previousTimestamp = matchedPrevious.timestamp,
                    recurrenceCount = recurrenceCount
                )
            }
        }

        return InspectionTrendOverview(
            trendType = trendType,
            trendLabel = trendType.name,
            lastInspectionDate = lastInspectionDate,
            daysSinceLastInspection = daysSinceLastInspection
        )
    }

    private fun extractDefectLabels(data: Map<String, Any>): Set<String> {
        val labels = linkedSetOf<String>()

        val verifiedDefects = data["verified_defects"]
        if (verifiedDefects is List<*>) {
            verifiedDefects.forEach { item ->
                when (item) {
                    is Map<*, *> -> {
                        val label = (item["label"] as? String)?.trim().orEmpty()
                        if (label.isNotBlank()) labels.add(normalizeDefectLabel(label))
                    }
                    is String -> {
                        val label = item.trim()
                        if (label.isNotBlank()) labels.add(normalizeDefectLabel(label))
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            val damage = (data["damage"] as? String).orEmpty().trim()
            if (damage.isNotBlank() &&
                !damage.equals("Normal Surface", ignoreCase = true) &&
                !damage.equals("Intact", ignoreCase = true)
            ) {
                damage.split(',', '/', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { labels.add(normalizeDefectLabel(it)) }
            }
        }

        return labels
    }

    private fun normalizeDefectLabel(value: String): String =
        value.trim().lowercase(Locale.getDefault())

    private fun classifyConditionTrend(
        currentSeverity: Double,
        previousSeverity: Double,
        currentTimestamp: Long,
        previousTimestamp: Long,
        recurrenceCount: Int
    ): InspectionTrendType {
        val severityDelta = currentSeverity - previousSeverity
        val daysElapsed = elapsedDays(currentTimestamp, previousTimestamp).toDouble()
        val severityVelocity = severityDelta / daysElapsed
        val timeWeight = min(daysElapsed / 365.0, 1.0)
        val recurrenceWeight = recurrenceWeight(recurrenceCount)
        val trendScore = (severityVelocity * 0.7) + (recurrenceWeight * 0.1) - (timeWeight * 0.2)

        return when {
            severityVelocity > 0.015 -> InspectionTrendType.WORSENING
            severityDelta == 0.0 && daysElapsed > 90.0 -> InspectionTrendType.STABLE
            trendScore > 0.02 -> InspectionTrendType.WORSENING
            else -> InspectionTrendType.STABLE
        }
    }

    private fun recurrenceWeight(recurrenceCount: Int): Double {
        return when {
            recurrenceCount >= 5 -> 1.0
            recurrenceCount >= 3 -> 0.5
            else -> 0.2
        }
    }

    private fun elapsedDays(currentTimestamp: Long, previousTimestamp: Long): Long {
        val delta = (currentTimestamp - previousTimestamp).coerceAtLeast(0L)
        return (delta / 86400000L).coerceAtLeast(1L)
    }

    private fun formatDateOnly(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun extractScore(data: Map<String, Any>, primaryKey: String, fallbackKey: String): Double {
        val primary = (data[primaryKey] as? Number)?.toDouble()
        if (primary != null) return primary
        return (data[fallbackKey] as? Number)?.toDouble() ?: 0.0
    }
}
