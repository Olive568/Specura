package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

private data class DefectFeatureAsset(
    val classes: List<String>? = null,
    val features: Array<FloatArray>? = null,
    val temperature: Float? = null,
    @SerializedName(value = "similarity_threshold", alternate = ["similarityThreshold"])
    val similarityThreshold: Float? = null
)

class MobileClipDefectValidator(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val classes: List<String>
    private val textFeatures: Array<FloatArray>
    private val temperature: Float
    private val similarityThreshold: Float

    init {
        val assets = context.assets.list("") ?: emptyArray()
        if ("mobileclip_defect_validator.onnx.data" in assets) {
            copyAssetToFile("mobileclip_defect_validator.onnx.data")
        }

        val modelPath = copyAssetToFile("mobileclip_defect_validator.onnx")
        session = env.createSession(modelPath, OrtSession.SessionOptions())

        val jsonString = context.assets.open("defect_validation_features.json")
            .bufferedReader()
            .use { it.readText() }

        val featureData = Gson().fromJson(
            jsonString,
            DefectFeatureAsset::class.java
        )
        classes = featureData.classes.orEmpty()
        textFeatures = featureData.features ?: emptyArray()
        temperature = featureData.temperature ?: 1f
        similarityThreshold = featureData.similarityThreshold ?: 0.26f
    }

    fun validate(
        _bitmap: Bitmap,
        rois: List<SuspiciousRegion>,
        material: String = "unknown"
    ): List<VerifiedDefectRecord> {
        if (rois.isEmpty()) return emptyList()

        val normalizedMaterial = material.trim().lowercase(Locale.getDefault())
        val validated = rois.mapNotNull { region ->
            validateRegion(region, normalizedMaterial)
        }

        return if (normalizedMaterial == "concrete") {
            collapseConcreteCrackRecords(validated)
        } else {
            validated
        }
    }

    private fun validateRegion(
        region: SuspiciousRegion,
        material: String
    ): VerifiedDefectRecord? {
        val embedding = inferEmbedding(region.bitmap) ?: return null
        val limit = minOf(classes.size, textFeatures.size)
        val requiredSimilarity = AppSettings.getDefectThresholdScore(context)
        val requiredMargin = AppSettings.getDefectThresholdMargin(context)
        val crackLikeRegion = isCrackLike(region.bounds)
        val similarities = FloatArray(limit)

        var bestIdx = -1
        var secondBestIdx = -1
        var bestSimilarity = Float.NEGATIVE_INFINITY
        var secondBestSimilarity = Float.NEGATIVE_INFINITY

        for (i in 0 until limit) {
            val label = classes[i]
            val rawSim = (cosineSimilarity(embedding, textFeatures[i]) * temperature)
                .coerceIn(0f, 1f)
            val sim = applyCrackShapeBias(
                label = label,
                similarity = rawSim,
                crackLikeRegion = crackLikeRegion
            )
            similarities[i] = sim
            if (sim > bestSimilarity) {
                secondBestIdx = bestIdx
                secondBestSimilarity = bestSimilarity
                bestSimilarity = sim
                bestIdx = i
            } else if (sim > secondBestSimilarity) {
                secondBestSimilarity = sim
                secondBestIdx = i
            }
        }

        if (bestIdx < 0) return null

        val crackIdx = classes.indexOfFirst { it.equals("crack", ignoreCase = true) }
        val bestLabel = classes[bestIdx]
        val normalizedBestLabel = normalizeDefectLabelForMaterial(material, bestLabel, crackLikeRegion)
        val secondBestLabel = if (secondBestIdx in 0 until limit) {
            normalizeDefectLabelForMaterial(material, classes[secondBestIdx], crackLikeRegion)
        } else {
            null
        }

        if (crackLikeRegion && crackIdx in 0 until limit) {
            val crackSimilarity = similarities[crackIdx]
            if (crackSimilarity >= requiredSimilarity &&
                (crackSimilarity - secondBestSimilarity) >= requiredMargin
            ) {
                logDecision(
                    region = region,
                    bestLabel = "crack",
                    bestSimilarity = crackSimilarity,
                    secondBestSimilarity = secondBestSimilarity,
                    requiredSimilarity = requiredSimilarity,
                    requiredMargin = requiredMargin,
                    decision = "accepted",
                    reason = "crack_shape_preferred"
                )
                return VerifiedDefectRecord(
                    label = "crack",
                    roi = region.bounds
                )
            }
        }

        if (crackLikeRegion && isCrackAdjacentCandidate(bestLabel)) {
            if (bestSimilarity >= requiredSimilarity * POSSIBLE_CRACK_SCORE_MULTIPLIER) {
                val acceptedLabel = if (material.equals("metal", ignoreCase = true)) {
                    "corrosion"
                } else {
                    "possible crack"
                }
                logDecision(
                    region = region,
                    bestLabel = acceptedLabel,
                    bestSimilarity = bestSimilarity,
                    secondBestSimilarity = secondBestSimilarity,
                    requiredSimilarity = requiredSimilarity,
                    requiredMargin = requiredMargin,
                    decision = "accepted",
                    reason = "possible_crack"
                )
                return VerifiedDefectRecord(
                    label = acceptedLabel,
                    roi = region.bounds
                )
            }
        }

        if (bestLabel.equals("normal", ignoreCase = true)) {
            if (secondBestLabel != null &&
                isMaterialLossSemanticCandidate(secondBestLabel) &&
                secondBestSimilarity >= requiredSimilarity &&
                (bestSimilarity - secondBestSimilarity) < requiredMargin
            ) {
                val normalizedSecondBest = normalizeDefectLabelForMaterial(
                    material = material,
                    label = secondBestLabel,
                    crackLikeRegion = crackLikeRegion
                )
                logDecision(
                    region = region,
                    bestLabel = normalizedSecondBest,
                    bestSimilarity = secondBestSimilarity,
                    secondBestSimilarity = bestSimilarity,
                    requiredSimilarity = requiredSimilarity,
                    requiredMargin = requiredMargin,
                    decision = "accepted",
                    reason = "normal_override_material_loss"
                )
                return VerifiedDefectRecord(
                    label = normalizedSecondBest,
                    roi = region.bounds
                )
            }

            logDecision(
                region = region,
                bestLabel = bestLabel,
                bestSimilarity = bestSimilarity,
                secondBestSimilarity = secondBestSimilarity,
                requiredSimilarity = requiredSimilarity,
                requiredMargin = requiredMargin,
                decision = "rejected",
                reason = "normal_label"
            )
            return null
        }

        if (crackLikeRegion && isCrackSemanticCandidate(bestLabel)) {
            if (bestSimilarity >= requiredSimilarity &&
                (bestSimilarity - secondBestSimilarity) >= requiredMargin
            ) {
                logDecision(
                    region = region,
                    bestLabel = bestLabel,
                    bestSimilarity = bestSimilarity,
                    secondBestSimilarity = secondBestSimilarity,
                    requiredSimilarity = requiredSimilarity,
                    requiredMargin = requiredMargin,
                    decision = "accepted",
                    reason = "crack_like_fallback"
                )
                return VerifiedDefectRecord(
                    label = normalizedBestLabel,
                    roi = region.bounds
                )
            }
            logDecision(
                region = region,
                bestLabel = bestLabel,
                bestSimilarity = bestSimilarity,
                secondBestSimilarity = secondBestSimilarity,
                requiredSimilarity = requiredSimilarity,
                requiredMargin = requiredMargin,
                decision = "rejected",
                reason = "crack_like_fallback"
            )
        }

        if (bestSimilarity < requiredSimilarity) {
            logDecision(
                region = region,
                bestLabel = bestLabel,
                bestSimilarity = bestSimilarity,
                secondBestSimilarity = secondBestSimilarity,
                requiredSimilarity = requiredSimilarity,
                requiredMargin = requiredMargin,
                decision = "rejected",
                reason = "below_similarity_threshold"
            )
            return null
        }

        if ((bestSimilarity - secondBestSimilarity) < requiredMargin) {
            logDecision(
                region = region,
                bestLabel = bestLabel,
                bestSimilarity = bestSimilarity,
                secondBestSimilarity = secondBestSimilarity,
                requiredSimilarity = requiredSimilarity,
                requiredMargin = requiredMargin,
                decision = "rejected",
                reason = "below_margin"
            )
            return null
        }

        logDecision(
            region = region,
            bestLabel = normalizedBestLabel,
            bestSimilarity = bestSimilarity,
            secondBestSimilarity = secondBestSimilarity,
            requiredSimilarity = requiredSimilarity,
            requiredMargin = requiredMargin,
            decision = "accepted",
            reason = "verified"
        )

        return VerifiedDefectRecord(
            label = normalizedBestLabel,
            roi = region.bounds
        )
    }

    private fun inferEmbedding(bitmap: Bitmap): FloatArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val resized = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(Color.rgb(127, 127, 127))

        val scale = minOf(224f / bitmap.width.toFloat(), 224f / bitmap.height.toFloat())
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (224f - drawWidth) / 2f
        val top = (224f - drawHeight) / 2f
        val destRect = RectF(left, top, left + drawWidth, top + drawHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, destRect, paint)

        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

        val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * 224 * 224 * 4)
            .order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        for (p in pixels) floatBuffer.put(((android.graphics.Color.red(p) / 255f) - mean[0]) / std[0])
        for (p in pixels) floatBuffer.put(((android.graphics.Color.green(p) / 255f) - mean[1]) / std[1])
        for (p in pixels) floatBuffer.put(((android.graphics.Color.blue(p) / 255f) - mean[2]) / std[2])
        floatBuffer.rewind()

        val inputName = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 3, 224, 224)
        )

        return try {
            val results = session.run(mapOf(inputName to inputTensor))
            val outputName = session.outputNames.iterator().next()
            val outputData = flattenOutput((results.get(outputName).get() as OnnxTensor).getValue())
            normalize(outputData)
            outputData
        } catch (e: Exception) {
            Log.e(TAG, "Defect validation failed", e)
            null
        } finally {
            inputTensor.close()
            if (!resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    private fun normalize(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in v.indices) v[i] /= norm
        }
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        var n1 = 0f
        var n2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            n1 += v1[i] * v1[i]
            n2 += v2[i] * v2[i]
        }
        val denom = sqrt(n1.toDouble()) * sqrt(n2.toDouble())
        return if (denom < 1e-9) 0f else (dot / denom).toFloat()
    }

    private fun isCrackSemanticCandidate(label: String): Boolean {
        return label.equals("crack", ignoreCase = true)
    }

    private fun isAllowedDefectForMaterial(material: String, label: String): Boolean {
        if (label.equals("normal", ignoreCase = true)) return false

        return when (material) {
            "concrete", "brick" -> label.equals("crack", ignoreCase = true) ||
                label.equals("chipping", ignoreCase = true) ||
                label.equals("deterioration", ignoreCase = true)

            "metal" -> label.equals("corrosion", ignoreCase = true) ||
                label.equals("deterioration", ignoreCase = true)

            "wood" -> label.equals("deterioration", ignoreCase = true) ||
                label.equals("chipping", ignoreCase = true)

            else -> label.equals("crack", ignoreCase = true) ||
                label.equals("corrosion", ignoreCase = true) ||
                label.equals("chipping", ignoreCase = true) ||
                label.equals("stain", ignoreCase = true) ||
                label.equals("deterioration", ignoreCase = true)
        }
    }

    private fun normalizeDefectLabelForMaterial(
        material: String,
        label: String,
        crackLikeRegion: Boolean
    ): String {
        if (label.equals("normal", ignoreCase = true)) return label

        return when (material) {
            "concrete" -> "crack"

            "brick" -> when {
                isAllowedDefectForMaterial(material, label) -> label
                label.equals("corrosion", ignoreCase = true) -> "deterioration"
                label.equals("stain", ignoreCase = true) -> "deterioration"
                label.equals("crack", ignoreCase = true) && crackLikeRegion -> "crack"
                else -> "deterioration"
            }

            "metal" -> when {
                label.equals("normal", ignoreCase = true) -> label
                else -> "corrosion"
            }

            "wood" -> when {
                isAllowedDefectForMaterial(material, label) -> label
                label.equals("corrosion", ignoreCase = true) -> "deterioration"
                label.equals("stain", ignoreCase = true) -> "deterioration"
                label.equals("crack", ignoreCase = true) -> "deterioration"
                else -> "deterioration"
            }

            else -> when {
                isAllowedDefectForMaterial(material, label) -> label
                label.equals("corrosion", ignoreCase = true) -> "deterioration"
                else -> "deterioration"
            }
        }
    }

    private fun isCrackAdjacentCandidate(label: String): Boolean {
        return label.equals("crack", ignoreCase = true) ||
            label.equals("chipping", ignoreCase = true)
    }

    private fun applyCrackShapeBias(
        label: String,
        similarity: Float,
        crackLikeRegion: Boolean
    ): Float {
        if (!crackLikeRegion) return similarity
        return when {
            label.equals("crack", ignoreCase = true) ->
                (similarity + CRACK_SHAPE_BIAS).coerceIn(0f, 1f)
            label.equals("chipping", ignoreCase = true) ->
                (similarity - CHIPPING_CRACK_SHAPE_PENALTY).coerceIn(0f, 1f)
            else -> similarity
        }
    }

    private fun isMaterialLossSemanticCandidate(label: String): Boolean {
        return label.equals("deterioration", ignoreCase = true) ||
            label.equals("chipping", ignoreCase = true)
    }

    private fun isCrackLike(bounds: RoiBounds): Boolean {
        val width = bounds.width.coerceAtLeast(0)
        val height = bounds.height.coerceAtLeast(0)
        if (width == 0 || height == 0) return false

        val aspectRatio = maxOf(
            width.toDouble() / height.toDouble(),
            height.toDouble() / width.toDouble()
        )
        val roiArea = (width.toLong() * height.toLong()).toDouble().coerceAtLeast(1.0)
        val perimeterBias = (width + height).toDouble() / roiArea
        return aspectRatio >= CRACK_LIKE_ASPECT_RATIO && perimeterBias >= CRACK_LIKE_PERIMETER_BIAS
    }

    private fun collapseConcreteCrackRecords(
        defects: List<VerifiedDefectRecord>
    ): List<VerifiedDefectRecord> {
        if (defects.size <= 1) return defects

        val remaining = defects.toMutableList()
        val collapsed = mutableListOf<VerifiedDefectRecord>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val cluster = mutableListOf(seed)
            var mergedInPass: Boolean

            do {
                mergedInPass = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (shouldMergeConcreteDefects(cluster, candidate)) {
                        cluster.add(candidate)
                        iterator.remove()
                        mergedInPass = true
                    }
                }
            } while (mergedInPass)

            collapsed.add(
                VerifiedDefectRecord(
                    label = "crack",
                    roi = unionBounds(cluster.map { it.roi })
                )
            )
        }

        return collapsed.sortedByDescending { it.roi.area }
    }

    private fun shouldMergeConcreteDefects(
        cluster: List<VerifiedDefectRecord>,
        candidate: VerifiedDefectRecord
    ): Boolean {
        return cluster.any { existing ->
            rectsOverlapOrTouch(existing.roi, candidate.roi, mergePadding(existing.roi, candidate.roi))
        }
    }

    private fun rectsOverlapOrTouch(first: RoiBounds, second: RoiBounds, padding: Int): Boolean {
        val firstLeft = first.x - padding
        val firstTop = first.y - padding
        val firstRight = first.x + first.width + padding
        val firstBottom = first.y + first.height + padding

        val secondLeft = second.x - padding
        val secondTop = second.y - padding
        val secondRight = second.x + second.width + padding
        val secondBottom = second.y + second.height + padding

        return firstLeft <= secondRight &&
            secondLeft <= firstRight &&
            firstTop <= secondBottom &&
            secondTop <= firstBottom
    }

    private fun mergePadding(first: RoiBounds, second: RoiBounds): Int {
        val minDim = minOf(
            first.width.coerceAtLeast(0),
            first.height.coerceAtLeast(0),
            second.width.coerceAtLeast(0),
            second.height.coerceAtLeast(0)
        ).coerceAtLeast(1)
        return maxOf(16, (minDim * 0.18).toInt())
    }

    private fun unionBounds(bounds: List<RoiBounds>): RoiBounds {
        if (bounds.isEmpty()) {
            return RoiBounds(x = 0, y = 0, width = 0, height = 0, area = 0.0)
        }

        val left = bounds.minOf { it.x }
        val top = bounds.minOf { it.y }
        val right = bounds.maxOf { it.x + it.width }
        val bottom = bounds.maxOf { it.y + it.height }
        val unionArea = bounds.sumOf { it.area }

        return RoiBounds(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(0),
            height = (bottom - top).coerceAtLeast(0),
            area = unionArea
        )
    }

    private fun flattenOutput(output: Any?): FloatArray {
        return when (output) {
            is FloatArray -> output
            is Array<*> -> (output[0] as? FloatArray) ?: (output[0] as Array<*>)[0] as FloatArray
            else -> throw Exception("Unsupported output type")
        }
    }

    private fun copyAssetToFile(assetName: String, targetName: String = assetName): String {
        val file = File(context.filesDir, targetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    fun close() {
        session.close()
    }

    private fun logDecision(
        region: SuspiciousRegion,
        bestLabel: String,
        bestSimilarity: Float,
        secondBestSimilarity: Float,
        requiredSimilarity: Float,
        requiredMargin: Float,
        decision: String,
        reason: String
    ) {
        val margin = (bestSimilarity - secondBestSimilarity).coerceAtLeast(0f)
        Log.d(
            TAG,
            "[SpecuraDefectDebug] decision=$decision reason=$reason label=$bestLabel " +
                "roi=${region.bounds.width}x${region.bounds.height} " +
                "best=${formatMetric(bestSimilarity.toDouble())} " +
                "second=${formatMetric(secondBestSimilarity.toDouble())} " +
                "margin=${formatMetric(margin.toDouble())} " +
                "needSim=${formatMetric(requiredSimilarity.toDouble())} " +
                "needMargin=${formatMetric(requiredMargin.toDouble())}"
        )
    }

    private fun formatMetric(value: Double): String {
        return String.format(Locale.US, "%.4f", value)
    }

    companion object {
        private const val TAG = "MobileClipValidator"
        private const val CRACK_SHAPE_BIAS = 0.05f
        private const val CHIPPING_CRACK_SHAPE_PENALTY = 0.04f
        private const val CRACK_LIKE_ASPECT_RATIO = 1.6
        private const val CRACK_LIKE_PERIMETER_BIAS = 0.0055
        private const val POSSIBLE_CRACK_SCORE_MULTIPLIER = 0.85f
    }
}

