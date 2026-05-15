package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.exp
import kotlin.math.sqrt

data class ClassificationResult(
    val material: String,
    val damage: String,
    val confidence: Float,
    val prompt: String,
    val damageSignal: Float,
    val severityScore: Float,
    val severityLabel: String
)

class ClipClassifier(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private lateinit var textFeatures: Array<FloatArray>
    private var modelTemperature = 1f
    private val LOW_CONFIDENCE_THRESHOLD = 0.50f

    private val historyPrefs = context.getSharedPreferences("damage_history", Context.MODE_PRIVATE)

    private val prompts = listOf(
        "concrete_intact", "concrete_cracked", "wood_intact", "wood_damaged",
        "metal_intact", "metal_rusted", "brick_intact", "brick_cracked"
    )

    private val promptMapping = mapOf(
        0 to Pair("Concrete", "Intact"),
        1 to Pair("Concrete", "Cracked"),
        2 to Pair("Wood", "Intact"),
        3 to Pair("Wood", "Damaged"),
        4 to Pair("Metal", "Intact"),
        5 to Pair("Metal", "Rusted"),
        6 to Pair("Brick", "Intact"),
        7 to Pair("Brick", "Cracked")
    )

    private data class TextFeatureData(
        val classes: List<String>,
        val features: Array<FloatArray>,
        val temperature: Float
    )

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e("ClipClassifier", "OpenCV init failed")
        }

        val assets = context.assets.list("") ?: emptyArray()
        if ("mobileclip_visual.onnx.data" in assets) {
            copyAssetToFile("mobileclip_visual.onnx.data")
        }

        val modelPath = copyAssetToFile("mobileclip_visual.onnx")
        val options = OrtSession.SessionOptions()
        session = env.createSession(modelPath, options)

        val jsonString = context.assets.open("text_features.json").bufferedReader().use { it.readText() }
        val featureData = Gson().fromJson(jsonString, TextFeatureData::class.java)

        textFeatures = featureData.features
        modelTemperature = featureData.temperature

        for (i in textFeatures.indices) {
            normalize(textFeatures[i])
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

    fun classify(bitmap: Bitmap, location: String = "Default", selectedMaterial: String = "Auto"): ClassificationResult {
        try {
            // 1. Pre-processing
            val size = minOf(bitmap.width, bitmap.height)
            val xOffset = (bitmap.width - size) / 2
            val yOffset = (bitmap.height - size) / 2
            val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
            val resized = Bitmap.createScaledBitmap(squareBitmap, 224, 224, true)

            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

            val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * 224 * 224 * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()

            val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
            val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

            for (p in pixels) floatBuffer.put(((Color.red(p) / 255f) - mean[0]) / std[0])
            for (p in pixels) floatBuffer.put(((Color.green(p) / 255f) - mean[1]) / std[1])
            for (p in pixels) floatBuffer.put(((Color.blue(p) / 255f) - mean[2]) / std[2])

            floatBuffer.rewind()
            val inputName = session.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, 224, 224))
            val results = session.run(Collections.singletonMap(inputName, inputTensor))

            val outputName = session.outputNames.iterator().next()
            val outputData = flattenOutput((results.get(outputName).get() as OnnxTensor).getValue())
            normalize(outputData)

            // Determine which indices to consider
            val targetIndices = when (selectedMaterial) {
                "Concrete" -> listOf(0, 1)
                "Wood" -> listOf(2, 3)
                "Metal" -> listOf(4, 5)
                "Brick" -> listOf(6, 7)
                else -> prompts.indices.toList() // Auto Mode
            }

            val similarities = FloatArray(textFeatures.size)
            var bestIdx = -1
            var maxSimilarity = -999f

            for (i in targetIndices) {
                val sim = cosineSimilarity(outputData, textFeatures[i]) * modelTemperature
                similarities[i] = sim
                if (sim > maxSimilarity) {
                    maxSimilarity = sim
                    bestIdx = i
                }
            }

            // Calculate probabilities only for target indices
            val expScores = targetIndices.map { i -> exp(similarities[i].toDouble()).toFloat() }
            val sumExp = expScores.sum()
            val probabilities = targetIndices.mapIndexed { index, i -> i to (expScores[index] / sumExp) }.toMap()

            var finalBestIdx = bestIdx
            val initialPrediction = prompts[bestIdx]

            // --- MATERIAL TIE-BREAKER LOGIC (Only for Auto Mode) ---
            val textureDensity = analyzeDamageOpenCV(squareBitmap)
            if (selectedMaterial == "Auto") {
                if (initialPrediction.contains("brick")) {
                    if (textureDensity < 0.15f) {
                        finalBestIdx = 2 // Switch to wood_intact
                        Log.d("SpecuraFix", "Correction: Brick -> Wood (Density: $textureDensity)")
                    }
                } else if (initialPrediction.contains("metal")) {
                    val concreteProb = (exp(similarities[0].toDouble()) / prompts.indices.sumOf { exp(similarities[it].toDouble()) }).toFloat()
                    val metalProb = (exp(similarities[bestIdx].toDouble()) / prompts.indices.sumOf { exp(similarities[it].toDouble()) }).toFloat()
                    if (metalProb < 0.50f && concreteProb > 0.10f) {
                        finalBestIdx = 0 // Switch to concrete_intact
                    }
                }
            }

            val prediction = prompts[finalBestIdx]
            val mapping = promptMapping[finalBestIdx] ?: Pair("Unknown", "Unknown")
            
            // Confidence calculation
            val confidence = if (selectedMaterial == "Auto") {
                val allExp = prompts.indices.map { exp(similarities[it].toDouble()).toFloat() }
                val allSum = allExp.sum()
                val allProbabilities = allExp.map { it / allSum }
                calibrateConfidence(
                    probabilities = allProbabilities,
                    selectedIdx = finalBestIdx,
                    bestIdx = bestIdx,
                    wasCorrected = finalBestIdx != bestIdx
                )
            } else {
                // In manual mode, confidence is the relative probability between intact/damaged
                probabilities[finalBestIdx] ?: 0f
            }

            // --- DAMAGE ANALYSIS ---
            var damageSignal = textureDensity
            if (prediction.contains("wood")) {
                damageSignal *= 0.5f 
            } else if (prediction.contains("rusted")) {
                damageSignal = detectRustOpenCV(squareBitmap)
            }

            results.close()
            inputTensor.close()

            val finalScore = (0.6f * damageSignal) + (0.2f * (1f - confidence)) + (0.2f * getHistoryScore(location, damageSignal))
            val severityLabel = when {
                finalScore < 0.30f -> "Minor"
                finalScore < 0.60f -> "Moderate"
                else -> "Severe"
            }

            updateHistory(location, finalScore)

            // Fallback only for Auto mode
            val finalPrompt = if (selectedMaterial == "Auto" && confidence < LOW_CONFIDENCE_THRESHOLD) {
                "Possible material detected, but confidence is low. Try better lighting or closer distance."
            } else prediction

            return ClassificationResult(
                material = if (selectedMaterial != "Auto") selectedMaterial else mapping.first,
                damage = mapping.second,
                confidence = confidence,
                prompt = finalPrompt,
                damageSignal = damageSignal,
                severityScore = finalScore,
                severityLabel = severityLabel
            )

        } catch (e: Exception) {
            Log.e("ClipClassifier", "Classification failed", e)
            return ClassificationResult("Error", "Error", 0f, "Error", 0f, 0f, "Unknown")
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

    private fun calibrateConfidence(
        probabilities: List<Float>,
        selectedIdx: Int,
        bestIdx: Int,
        wasCorrected: Boolean
    ): Float {
        val selectedProbability = probabilities[selectedIdx]
        val topProbability = probabilities[bestIdx]
        val sorted = probabilities.sortedDescending()
        val runnerUpProbability = sorted.getOrElse(1) { 0f }
        val margin = (sorted.firstOrNull() ?: topProbability) - runnerUpProbability

        val calibrated = if (wasCorrected) {
            0.70f + (topProbability * 0.30f) + (margin * 1.50f)
        } else {
            0.35f + (topProbability * 1.20f) + (margin * 2.50f)
        }

        return maxOf(selectedProbability, calibrated.coerceIn(0.05f, 0.97f))
    }

    private fun analyzeDamageOpenCV(bitmap: Bitmap): Float {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(src, src, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(src, src, 120.0, 250.0)
        val score = Core.countNonZero(src).toFloat() / (src.rows() * src.cols())
        src.release()
        return (score * 7.5f).coerceIn(0f, 1f)
    }

    private fun detectRustOpenCV(bitmap: Bitmap): Float {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        Core.inRange(src, Scalar(5.0, 100.0, 100.0), Scalar(20.0, 255.0, 255.0), mask)
        val score = Core.countNonZero(mask).toFloat() / (mask.rows() * mask.cols())
        src.release()
        mask.release()
        return (score * 3f).coerceIn(0f, 1f)
    }

    private fun flattenOutput(output: Any?): FloatArray {
        return when (output) {
            is FloatArray -> output
            is Array<*> -> (output[0] as? FloatArray) ?: (output[0] as Array<*>)[0] as FloatArray
            else -> throw Exception("Unsupported output type")
        }
    }

    private fun getHistoryScore(location: String, current: Float): Float {
        val history = historyPrefs.getString(location, "") ?: ""
        if (history.isEmpty()) return 0f
        val scores = history.split(",").mapNotNull { it.toFloatOrNull() }
        if (scores.isEmpty()) return 0f
        val avg = scores.takeLast(3).average().toFloat()
        return (current - avg).coerceIn(0f, 1f)
    }

    private fun updateHistory(location: String, score: Float) {
        val current = historyPrefs.getString(location, "")
        val updated = if (current.isNullOrEmpty()) "$score" else "$current,$score"
        historyPrefs.edit().putString(location, updated).apply()
    }

    fun close() { session.close() }
}
