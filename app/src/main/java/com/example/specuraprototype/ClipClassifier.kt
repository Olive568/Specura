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
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.exp
import kotlin.math.sqrt

private data class TextFeatureAsset(
    val classes: List<String>? = null,
    val features: Array<FloatArray>? = null,
    val temperature: Float? = null
)

data class ClassificationResult(
    val material: String,
    val damage: String,
    val prompt: String,
    val damageSignal: Float,
    val severityScore: Float,
    val severityLabel: String,
    val materialConfidence: Float = 0f,
    val topMaterials: List<MaterialPredictionRecord> = emptyList(),
    val rawSimilarity: Float = 0f,
    val margin: Float = 0f,
    val allScores: Map<String, Float> = emptyMap()
)

class ClipClassifier(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private lateinit var textFeatures: Array<FloatArray>
    private var modelTemperature = 1f

    private val LOW_MATCH_THRESHOLD = 0.20f
    private val BRICK_INDEX = 3
    private val DEBUG_TAG = "SpecuraDebug"
    private val MATERIAL_CROP_SIZE = 1024

    private val historyPrefs =
        context.getSharedPreferences(
            "damage_history",
            Context.MODE_PRIVATE
        )

    // =========================================
    // NEW MATERIAL-ONLY PROMPTS
    // =========================================

    private val prompts = listOf(
        "concrete",
        "wood",
        "metal",
        "brick"
    )

    private val promptMapping = mapOf(
        0 to "Concrete",
        1 to "Wood",
        2 to "Metal",
        3 to "Brick"
    )

    init {

        if (!OpenCVLoader.initDebug()) {
            Log.e("ClipClassifier", "OpenCV init failed")
        }

        val assets = context.assets.list("") ?: emptyArray()

        if ("mobileclip_visual.onnx.data" in assets) {
            copyAssetToFile(
                "mobileclip_visual.onnx.data"
            )
        }

        val modelPath = copyAssetToFile(
            "mobileclip_visual.onnx"
        )

        val options = OrtSession.SessionOptions()

        session = env.createSession(
            modelPath,
            options
        )

        val jsonString = context.assets
            .open("text_features.json")
            .bufferedReader()
            .use { it.readText() }

        val featureData = Gson().fromJson(
            jsonString,
            TextFeatureAsset::class.java
        )

        textFeatures = featureData.features ?: emptyArray()

        modelTemperature = featureData.temperature ?: 1f
    }

    private fun copyAssetToFile(
        assetName: String,
        targetName: String = assetName
    ): String {

        val file = File(
            context.filesDir,
            targetName
        )

        context.assets.open(assetName).use { input ->

            FileOutputStream(file).use { output ->

                input.copyTo(output)
            }
        }

        return file.absolutePath
    }

    // =========================================
    // MAIN CLASSIFICATION
    // =========================================

    fun classify(
        bitmap: Bitmap,
        location: String = "Default",
        selectedMaterial: String = "Auto"
    ): ClassificationResult {

        try {

            // =====================================
            // PREPROCESSING
            // =====================================

            val size = minOf(
                minOf(bitmap.width, bitmap.height),
                MATERIAL_CROP_SIZE
            )

            val xOffset =
                ((bitmap.width - size) / 2).coerceAtLeast(0)

            val yOffset =
                ((bitmap.height - size) / 2).coerceAtLeast(0)

            val squareBitmap = Bitmap.createBitmap(
                bitmap,
                xOffset,
                yOffset,
                size,
                size
            )

            val resized =
                Bitmap.createScaledBitmap(
                    squareBitmap,
                    224,
                    224,
                    true
                )

            val pixels =
                IntArray(224 * 224)

            resized.getPixels(
                pixels,
                0,
                224,
                0,
                0,
                224,
                224
            )

            val byteBuffer =
                ByteBuffer.allocateDirect(
                    1 * 3 * 224 * 224 * 4
                ).order(ByteOrder.nativeOrder())

            val floatBuffer =
                byteBuffer.asFloatBuffer()

            val mean = floatArrayOf(
                0.48145466f,
                0.4578275f,
                0.40821073f
            )

            val std = floatArrayOf(
                0.26862954f,
                0.26130258f,
                0.27577711f
            )

            for (p in pixels)
                floatBuffer.put(
                    ((Color.red(p) / 255f) - mean[0]) / std[0]
                )

            for (p in pixels)
                floatBuffer.put(
                    ((Color.green(p) / 255f) - mean[1]) / std[1]
                )

            for (p in pixels)
                floatBuffer.put(
                    ((Color.blue(p) / 255f) - mean[2]) / std[2]
                )

            floatBuffer.rewind()

            val inputName =
                session.inputNames.iterator().next()

            val inputTensor =
                OnnxTensor.createTensor(
                    env,
                    floatBuffer,
                    longArrayOf(1, 3, 224, 224)
                )

            val results = session.run(
                Collections.singletonMap(
                    inputName,
                    inputTensor
                )
            )

            val outputName =
                session.outputNames.iterator().next()

            val outputData = flattenOutput(
                (results.get(outputName).get() as OnnxTensor)
                    .getValue()
            )

            normalize(outputData)

            // =====================================
            // TARGET INDICES
            // =====================================

            val classCount = minOf(
                prompts.size,
                textFeatures.size
            )

            if (classCount <= 0) {
                throw IllegalStateException("No material text features loaded")
            }

            val scoringIndices = if (selectedMaterial == "Auto") {
                val nonBrick = (0 until classCount).filterNot { it == BRICK_INDEX }
                if (nonBrick.isNotEmpty()) nonBrick else (0 until classCount).toList()
            } else {
                (0 until classCount).toList()
            }

            data class MaterialScore(
                val index: Int,
                val rawSimilarity: Float,
                val adjustedSimilarity: Float
            )

            val materialScores = mutableListOf<MaterialScore>()

            for (i in scoringIndices) {
                val rawSimilarity = cosineSimilarity(outputData, textFeatures[i])
                var sim = rawSimilarity
                if (selectedMaterial == "Auto" && i == BRICK_INDEX) {
                    sim *= 0.65f
                }
                materialScores.add(
                    MaterialScore(
                        index = i,
                        rawSimilarity = rawSimilarity,
                        adjustedSimilarity = sim
                    )
                )
            }

            val rankedScores = materialScores.sortedByDescending { it.adjustedSimilarity }
            val bestScore = rankedScores.firstOrNull()
                ?: throw IllegalStateException("No material scores computed")

            val probabilities = if (materialScores.isNotEmpty()) {
                val expScores = materialScores.map {
                    exp((it.adjustedSimilarity * modelTemperature).toDouble()).toFloat()
                }
                val sumExp = expScores.sum()
                materialScores.mapIndexed { index, score ->
                    score.index to if (sumExp <= 0f) 0f else expScores[index] / sumExp
                }.toMap()
            } else {
                emptyMap()
            }

            val selectedIndex = when (selectedMaterial) {
                "Concrete" -> 0
                "Wood" -> 1
                "Metal" -> 2
                "Brick" -> 3
                else -> bestScore.index
            }.takeIf { it in 0 until classCount } ?: bestScore.index

            val selectedConfidence =
                if (selectedMaterial == "Auto") {
                    calibrateMatchScore(
                        probabilities = materialScores.map {
                            probabilities[it.index] ?: 0f
                        },
                        selectedPos = materialScores.indexOfFirst { it.index == bestScore.index }
                    )
                } else {
                    calibrateMatchScore(
                        probabilities = materialScores.map {
                            probabilities[it.index] ?: 0f
                        },
                        selectedPos = materialScores.indexOfFirst { it.index == selectedIndex }
                    )
                }

            val selectedLabel = promptMapping[bestScore.index] ?: "Concrete"
            val selectedPrompt = prompts.getOrElse(bestScore.index) { "concrete" }

            val topMaterials = rankedScores.take(3).map { score ->
                MaterialPredictionRecord(
                    label = promptMapping[score.index] ?: "Concrete",
                    confidence = probabilities[score.index] ?: 0f
                )
            }

            val allScores = materialScores.associate { score ->
                val label = (promptMapping[score.index] ?: "Concrete").lowercase(Locale.US)
                label to ((probabilities[score.index] ?: 0f) * 100f)
            }

            // =====================================
            // MATCH STRENGTH
            // =====================================

            val rawCosine = bestScore.rawSimilarity
            val topProbability = probabilities[bestScore.index] ?: 0f
            val runnerUpProbability = rankedScores.getOrNull(1)?.let { probabilities[it.index] ?: 0f } ?: 0f
            val confidenceMargin = (topProbability - runnerUpProbability).coerceAtLeast(0f)
            val matchScore = selectedConfidence

            Log.d(
                DEBUG_TAG,
                "[SpecuraDebug] Label=$selectedLabel " +
                    "RawSim=${String.format(Locale.US, "%.3f", rawCosine)} " +
                    "Softmax=${String.format(Locale.US, "%.3f", topProbability)} " +
                    "Margin=${String.format(Locale.US, "%.3f", confidenceMargin)} " +
                    "Confidence=${String.format(Locale.US, "%.3f", matchScore)}"
            )

            if (selectedMaterial == "Auto" && matchScore < LOW_MATCH_THRESHOLD) {
                Log.d(
                    DEBUG_TAG,
                    "[SpecuraDebug] Low-match Auto result retained: label=$selectedLabel " +
                        "confidence=${String.format(Locale.US, "%.3f", matchScore)} " +
                        "threshold=$LOW_MATCH_THRESHOLD"
                )
            }

            // =====================================
            // DAMAGE ANALYSIS
            // =====================================

            val damageSignal =
                analyzeDamageOpenCV(
                    squareBitmap
                )

            results.close()
            inputTensor.close()

            val finalScore =
                (0.6f * damageSignal) +
                        (0.2f * (1f - matchScore)) +
                        (0.2f * getHistoryScore(
                            location,
                            damageSignal
                        ))

            val severityLabel = when {

                finalScore < 0.30f ->
                    "Minor"

                finalScore < 0.60f ->
                    "Moderate"

                else ->
                    "Severe"
            }

            updateHistory(
                location,
                finalScore
            )

            return ClassificationResult(

                material =
                    if (selectedMaterial != "Auto")
                        selectedMaterial
                    else
                        selectedLabel,

                damage = severityLabel,

                prompt = selectedPrompt,

                damageSignal = damageSignal,

                severityScore = finalScore,

                severityLabel = severityLabel,
                materialConfidence = matchScore,
                topMaterials = topMaterials,
                rawSimilarity = rawCosine,
                margin = confidenceMargin,
                allScores = allScores
            )

        } catch (e: Exception) {

            Log.e(
                "ClipClassifier",
                "Classification failed",
                e
            )

            return ClassificationResult(
                "Error",
                "Error",
                "Error",
                0f,
                0f,
                "Unknown",
                rawSimilarity = 0f,
                margin = 0f,
                allScores = emptyMap()
            )
        }
    }

    // =========================================
    // NORMALIZATION
    // =========================================

    private fun normalize(v: FloatArray) {

        var norm = 0f

        for (x in v)
            norm += x * x

        norm = sqrt(norm)

        if (norm > 1e-6f) {

            for (i in v.indices)
                v[i] /= norm
        }
    }

    // =========================================
    // COSINE SIMILARITY
    // =========================================

    private fun cosineSimilarity(
        v1: FloatArray,
        v2: FloatArray
    ): Float {

        var dot = 0f
        var n1 = 0f
        var n2 = 0f

        for (i in v1.indices) {

            dot += v1[i] * v2[i]

            n1 += v1[i] * v1[i]

            n2 += v2[i] * v2[i]
        }

        val denom =
            sqrt(n1.toDouble()) *
                    sqrt(n2.toDouble())

        return if (denom < 1e-9)
            0f
        else
            (dot / denom).toFloat()
    }

    // =========================================
    // MATCH STRENGTH
    // =========================================

    private fun calibrateMatchScore(
        probabilities: List<Float>,
        selectedPos: Int
    ): Float {

        if (probabilities.isEmpty() || selectedPos !in probabilities.indices) {
            return 0f
        }

        val topProbability = probabilities[selectedPos]

        val sorted = probabilities.sortedDescending()

        val runnerUpProbability = sorted.getOrElse(1) { 0f }

        val margin = (topProbability - runnerUpProbability).coerceAtLeast(0f)

        val calibrated =
            (topProbability * 0.75f) +
                    (margin * 0.90f)

        return calibrated.coerceIn(
            0f,
            1f
        )
    }

    // =========================================
    // OPENCV DAMAGE ANALYSIS
    // =========================================

    private fun analyzeDamageOpenCV(
        bitmap: Bitmap
    ): Float {

        val src = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()

        Utils.bitmapToMat(bitmap, src)

        Imgproc.cvtColor(
            src,
            gray,
            Imgproc.COLOR_RGBA2GRAY
        )

        val clahe =
            Imgproc.createCLAHE(
                2.0,
                Size(8.0, 8.0)
            )

        clahe.apply(gray, gray)

        Imgproc.GaussianBlur(
            gray,
            gray,
            Size(3.0, 3.0),
            0.0
        )

        Imgproc.Canny(
            gray,
            edges,
            90.0,
            180.0
        )

        val kernel =
            Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(3.0, 3.0)
            )

        Imgproc.dilate(
            edges,
            edges,
            kernel
        )

        val contours =
            mutableListOf<MatOfPoint>()

        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        var crackArea = 0.0

        for (contour in contours) {

            val area =
                Imgproc.contourArea(contour)

            if (area > 600) {

                crackArea += area
            }
        }

        val totalArea =
            edges.rows() * edges.cols()

        val score =
            (crackArea / totalArea).toFloat()

        val finalScore =
            (score * 2.5f)
                .coerceIn(0f, 1f)

        src.release()
        gray.release()
        edges.release()
        hierarchy.release()

        return finalScore
    }

    // =========================================
    // OUTPUT FLATTEN
    // =========================================

    private fun flattenOutput(
        output: Any?
    ): FloatArray {

        return when (output) {

            is FloatArray ->
                output

            is Array<*> ->
                (output[0] as? FloatArray)
                    ?: (output[0] as Array<*>)[0] as FloatArray

            else ->
                throw Exception(
                    "Unsupported output type"
                )
        }
    }

    // =========================================
    // HISTORY SCORE
    // =========================================

    private fun getHistoryScore(
        location: String,
        current: Float
    ): Float {

        val history =
            historyPrefs.getString(
                location,
                ""
            ) ?: ""

        if (history.isEmpty())
            return 0f

        val scores =
            history.split(",")
                .mapNotNull {
                    it.toFloatOrNull()
                }

        if (scores.isEmpty())
            return 0f

        val avg =
            scores.takeLast(3)
                .average()
                .toFloat()

        return (
                current - avg
                ).coerceIn(0f, 1f)
    }

    // =========================================
    // UPDATE HISTORY
    // =========================================

    private fun updateHistory(
        location: String,
        score: Float
    ) {

        val current =
            historyPrefs.getString(
                location,
                ""
            )

        val updated =
            if (current.isNullOrEmpty())
                "$score"
            else
                "$current,$score"

        historyPrefs.edit()
            .putString(
                location,
                updated
            )
            .apply()
    }

    fun close() {

        session.close()
    }
}
