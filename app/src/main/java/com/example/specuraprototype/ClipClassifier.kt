package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.gson.Gson
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.android.Utils
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

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
    private val textFeatures: Array<FloatArray>
    private val historyPrefs = context.getSharedPreferences("damage_history", Context.MODE_PRIVATE)

    // Updated to match the 8 classes in text_features.json
    private val prompts = listOf(
        "a photo of raw unpainted concrete surface",
        "a photo of cracked concrete surface",
        "a photo of wood grain material",
        "a photo of damaged wood surface",
        "a photo of clean metal surface",
        "a photo of rusted metal surface",
        "a photo of brick wall surface",
        "a photo of cracked brick wall"
    )

    private val promptMapping = mapOf(
        0 to Pair("Concrete", "Raw/Unpainted"),
        1 to Pair("Concrete", "Cracked"),
        2 to Pair("Wood", "Normal"),
        3 to Pair("Wood", "Damaged"),
        4 to Pair("Metal", "Clean"),
        5 to Pair("Metal", "Rusted"),
        6 to Pair("Brick", "Normal"),
        7 to Pair("Brick", "Cracked")
    )

    // Helper class for JSON parsing
    private data class TextFeatureData(val classes: List<String>, val features: Array<FloatArray>)

    init {
        if (!OpenCVLoader.initDebug()) Log.e("ClipClassifier", "OpenCV init failed")

        val modelPath = copyAssetToFile("clip_visual.onnx")
        // .data is not required if embedded in latest onnx export
        session = env.createSession(modelPath)

        // Fix crash: jsonString is an object { "classes": [...], "features": [[...]] }
        val jsonString = context.assets.open("text_features.json").bufferedReader().use { it.readText() }
        val featureData = Gson().fromJson(jsonString, TextFeatureData::class.java)
        textFeatures = featureData.features
        Log.d("ClipClassifier", "Loaded ${textFeatures.size} features for classes: ${featureData.classes}")
    }

    private fun copyAssetToFile(assetName: String): String {
        val file = File(context.filesDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    fun classify(bitmap: Bitmap, location: String = "Default"): ClassificationResult {
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * 224 * 224 * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()

            // Standard CLIP normalization
            val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
            val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

            val inputName = session.inputNames.iterator().next()
            val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape ?: longArrayOf(1, 3, 224, 224)
            val isPlanar = if (shape.size >= 4) shape[1] == 3L else true

            if (isPlanar) {
                // NCHW
                for (p in pixels) floatBuffer.put((Color.red(p) / 255f - mean[0]) / std[0])
                for (p in pixels) floatBuffer.put((Color.green(p) / 255f - mean[1]) / std[1])
                for (p in pixels) floatBuffer.put((Color.blue(p) / 255f - mean[2]) / std[2])
            } else {
                // NHWC
                for (p in pixels) {
                    floatBuffer.put((Color.red(p) / 255f - mean[0]) / std[0])
                    floatBuffer.put((Color.green(p) / 255f - mean[1]) / std[1])
                    floatBuffer.put((Color.blue(p) / 255f - mean[2]) / std[2])
                }
            }
            floatBuffer.rewind()

            val results = session.run(Collections.singletonMap(inputName, OnnxTensor.createTensor(env, floatBuffer, shape)))
            val imageEmbedding = extractEmbedding(results[0].value)

            var maxSim = -1f
            var bestIdx = 0
            for (i in textFeatures.indices) {
                val sim = cosineSimilarity(imageEmbedding, textFeatures[i])
                if (sim > maxSim) {
                    maxSim = sim
                    bestIdx = i
                }
            }

            val mapping = promptMapping[bestIdx] ?: Pair("Unknown", "Unknown")
            val prediction = prompts.getOrElse(bestIdx) { "unknown" }.lowercase()

            var damageSignal = 0.01f
            if (prediction.contains("crack")) damageSignal = analyzeDamageOpenCV(bitmap)
            else if (prediction.contains("rust")) damageSignal = detectRustOpenCV(bitmap)

            val historyScore = getHistoryScore(location, damageSignal)
            val finalScore = (0.6f * damageSignal) + (0.2f * (1 - maxSim)) + (0.2f * historyScore)
            val label = when {
                finalScore < 0.3f -> "Minor"
                finalScore < 0.6f -> "Moderate"
                else -> "Severe"
            }

            updateHistory(location, finalScore)
            return ClassificationResult(
                material = mapping.first,
                damage = mapping.second,
                confidence = maxSim,
                prompt = prompts.getOrElse(bestIdx) { "" },
                damageSignal = damageSignal,
                severityScore = finalScore,
                severityLabel = label
            )
        } catch (e: Exception) {
            Log.e("ClipClassifier", "Error: ${e.message}")
            return ClassificationResult("Error", "Error", 0f, "Error", 0f, 0f, "Unknown")
        }
    }

    private fun extractEmbedding(output: Any?): FloatArray {
        return when (output) {
            is Array<*> -> {
                val first = output[0]
                if (first is FloatArray) first else (first as Array<*>)[0] as FloatArray
            }
            is FloatArray -> output
            else -> throw Exception("Unexpected output format")
        }
    }

    private fun analyzeDamageOpenCV(bitmap: Bitmap): Float {
        val src = Mat(); Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.Canny(src, src, 50.0, 150.0)
        val score = Core.countNonZero(src).toFloat() / (src.rows() * src.cols())
        src.release(); return score * 50f // Amplify signal for weighted score
    }

    private fun detectRustOpenCV(bitmap: Bitmap): Float {
        val src = Mat(); Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGB2HSV)
        val mask = Mat(); Core.inRange(src, Scalar(5.0, 100.0, 100.0), Scalar(20.0, 255.0, 255.0), mask)
        val score = Core.countNonZero(mask).toFloat() / (mask.rows() * mask.cols())
        src.release(); mask.release(); return score
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f; var n1 = 0f; var n2 = 0f
        for (i in 0 until Math.min(v1.size, v2.size)) {
            dot += v1[i] * v2[i]; n1 += v1[i] * v1[i]; n2 += v2[i] * v2[i]
        }
        val d = Math.sqrt(n1.toDouble()) * Math.sqrt(n2.toDouble())
        return if (d < 1e-9) 0f else (dot / d).toFloat()
    }

    private fun getHistoryScore(loc: String, cur: Float): Float {
        val h = historyPrefs.getString(loc, "") ?: ""
        if (h.isEmpty()) return 0f
        val avg = h.split(",").mapNotNull { it.toFloatOrNull() }.takeLast(3).average().toFloat()
        return (cur - avg).coerceIn(0f, 1f)
    }

    private fun updateHistory(loc: String, s: Float) {
        val c = historyPrefs.getString(loc, "")
        historyPrefs.edit().putString(loc, if (c.isNullOrEmpty()) "$s" else "$c,$s").apply()
    }

    fun close() { session.close(); env.close() }
}
