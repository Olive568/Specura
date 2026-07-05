package com.example.specuraprototype

import android.content.Context
import org.json.JSONArray

data class LightingRobustnessProfile(
    val backgroundBlurKernel: Int,
    val cannyLow: Double,
    val cannyHigh: Double,
    val seedMinEdgeDensity: Double,
    val minEdgeDensity: Double,
    val seedMinLaplacianStdDev: Double,
    val minLaplacianStdDev: Double,
    val crackMinEdgeDensity: Double,
    val crackMinLaplacianStdDev: Double,
    val borderBandEdgeDensity: Double,
    val borderBandLaplacianStdDev: Double,
    val lightingArtifactEdgeDensity: Double,
    val lightingArtifactLaplacianStdDev: Double
)

object AppSettings {

    private const val PREFS_NAME = "specura_settings"
    private const val KEY_SHUTTER_SOUND = "shutter_sound"
    private const val KEY_DEFECT_THRESHOLD_PRESET = "defect_threshold_preset"
    private const val KEY_LIGHTING_ROBUSTNESS_PRESET = "lighting_robustness_preset"
    private const val KEY_LAST_ACTIVITY_LABEL = "last_activity_label"
    private const val KEY_LAST_ACTIVITY_TIMESTAMP = "last_activity_timestamp"
    private const val KEY_RECENT_LOCATION_TAGS = "recent_location_tags"

    const val PRESET_LOW = "low"
    const val PRESET_MEDIUM = "medium"
    const val PRESET_HIGH = "high"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isShutterSoundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHUTTER_SOUND, true)

    fun setShutterSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUTTER_SOUND, enabled).apply()
    }

    fun getDefectThresholdPreset(context: Context): String =
        normalizePreset(
            prefs(context).getString(KEY_DEFECT_THRESHOLD_PRESET, PRESET_MEDIUM)
        )

    fun setDefectThresholdPreset(context: Context, preset: String) {
        prefs(context).edit()
            .putString(KEY_DEFECT_THRESHOLD_PRESET, normalizePreset(preset))
            .apply()
    }

    fun getDefectThresholdScore(context: Context): Float {
        return when (getDefectThresholdPreset(context)) {
            PRESET_LOW -> 0.06f
            PRESET_HIGH -> 0.12f
            else -> 0.08f
        }
    }

    fun getDefectThresholdMargin(context: Context): Float {
        return when (getDefectThresholdPreset(context)) {
            PRESET_LOW -> 0.001f
            PRESET_HIGH -> 0.004f
            else -> 0.002f
        }
    }

    fun getLightingRobustnessPreset(context: Context): String =
        normalizePreset(
            prefs(context).getString(KEY_LIGHTING_ROBUSTNESS_PRESET, PRESET_LOW)
        )

    fun setLightingRobustnessPreset(context: Context, preset: String) {
        prefs(context).edit()
            .putString(KEY_LIGHTING_ROBUSTNESS_PRESET, normalizePreset(preset))
            .apply()
    }

    fun getLightingRobustnessProfile(context: Context): LightingRobustnessProfile {
        return when (getLightingRobustnessPreset(context)) {
            PRESET_LOW -> LightingRobustnessProfile(
                backgroundBlurKernel = 51,
                cannyLow = 80.0,
                cannyHigh = 160.0,
                seedMinEdgeDensity = 0.007,
                minEdgeDensity = 0.014,
                seedMinLaplacianStdDev = 4.0,
                minLaplacianStdDev = 7.0,
                crackMinEdgeDensity = 0.007,
                crackMinLaplacianStdDev = 5.6,
                borderBandEdgeDensity = 0.035,
                borderBandLaplacianStdDev = 9.5,
                lightingArtifactEdgeDensity = 0.038,
                lightingArtifactLaplacianStdDev = 10.0
            )

            PRESET_HIGH -> LightingRobustnessProfile(
                backgroundBlurKernel = 81,
                cannyLow = 110.0,
                cannyHigh = 220.0,
                seedMinEdgeDensity = 0.0105,
                minEdgeDensity = 0.020,
                seedMinLaplacianStdDev = 5.5,
                minLaplacianStdDev = 9.0,
                crackMinEdgeDensity = 0.011,
                crackMinLaplacianStdDev = 7.0,
                borderBandEdgeDensity = 0.048,
                borderBandLaplacianStdDev = 12.0,
                lightingArtifactEdgeDensity = 0.052,
                lightingArtifactLaplacianStdDev = 12.5
            )

            else -> LightingRobustnessProfile(
                backgroundBlurKernel = 61,
                cannyLow = 100.0,
                cannyHigh = 200.0,
                seedMinEdgeDensity = 0.009,
                minEdgeDensity = 0.018,
                seedMinLaplacianStdDev = 5.0,
                minLaplacianStdDev = 8.5,
                crackMinEdgeDensity = 0.010,
                crackMinLaplacianStdDev = 6.8,
                borderBandEdgeDensity = 0.045,
                borderBandLaplacianStdDev = 12.0,
                lightingArtifactEdgeDensity = 0.048,
                lightingArtifactLaplacianStdDev = 11.5
            )
        }
    }

    fun setLastActivity(context: Context, label: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ACTIVITY_LABEL, label)
            .putLong(KEY_LAST_ACTIVITY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getLastActivityLabel(context: Context): String =
        prefs(context).getString(KEY_LAST_ACTIVITY_LABEL, "No recent activity") ?: "No recent activity"

    fun getLastActivityTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ACTIVITY_TIMESTAMP, 0L)

    fun getRecentLocationTags(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT_LOCATION_TAGS, "[]") ?: "[]"
        val tags = mutableListOf<String>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) {
                    tags.add(value)
                }
            }
        }
        return tags
    }

    fun addRecentLocationTag(context: Context, tag: String) {
        val normalized = tag.trim().uppercase()
        if (normalized.isBlank()) return

        val tags = getRecentLocationTags(context).toMutableList()
        tags.removeAll { it.equals(normalized, ignoreCase = true) }
        tags.add(0, normalized)

        while (tags.size > 20) {
            tags.removeAt(tags.lastIndex)
        }

        val array = JSONArray()
        tags.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_RECENT_LOCATION_TAGS, array.toString()).apply()
    }

    private fun normalizePreset(value: String?, fallback: String = PRESET_MEDIUM): String {
        return when (value?.trim()?.lowercase()) {
            PRESET_LOW, PRESET_MEDIUM, PRESET_HIGH -> value.trim().lowercase()
            else -> fallback
        }
    }
}
