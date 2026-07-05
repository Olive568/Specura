package com.example.specuraprototype

import android.graphics.Bitmap
import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

class OpenCvStructuralAnalyzer(private val context: Context) {

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV init failed")
        }
    }

    fun analyze(bitmap: Bitmap): OpenCvAnalysis = analyze(bitmap, "unknown")

    fun analyze(bitmap: Bitmap, material: String): OpenCvAnalysis {
        val src = Mat()
        val gray = Mat()
        val equalized = Mat()
        val normalized = Mat()
        val background = Mat()
        val blurred = Mat()
        val threshold = Mat()
        val edges = Mat()
        val laplacian = Mat()
        val combined = Mat()
        val claheGray = Mat()
        val blackhatHorizontal = Mat()
        val blackhatVertical = Mat()
        val blackhatCombined = Mat()
        val crackThreshold = Mat()
        val crackEdges = Mat()
        val crackCombined = Mat()
        val nearBlackMask = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val normalizedMaterial = material.trim().lowercase(Locale.getDefault())

        return try {
            Utils.bitmapToMat(bitmap, src)
            val rawLightingProfile = AppSettings.getLightingRobustnessProfile(context)
            val lightingProfile = rawLightingProfile.copy(
                backgroundBlurKernel = maxOf(
                    rawLightingProfile.backgroundBlurKernel,
                    MIN_BACKGROUND_BLUR_KERNEL
                )
            )

            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(
                gray,
                background,
                Size(lightingProfile.backgroundBlurKernel.toDouble(), lightingProfile.backgroundBlurKernel.toDouble()),
                0.0
            )
            Core.absdiff(gray, background, normalized)
            Core.normalize(normalized, normalized, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.equalizeHist(normalized, equalized)
            Imgproc.GaussianBlur(equalized, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(
                blurred,
                nearBlackMask,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
            )
            Imgproc.threshold(
                blurred,
                threshold,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )
            Imgproc.Canny(blurred, edges, lightingProfile.cannyLow, lightingProfile.cannyHigh)
            Core.bitwise_or(threshold, edges, combined)
            Core.bitwise_and(combined, nearBlackMask, combined)

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(3.0, 3.0)
            )
            val openKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(5.0, 5.0)
            )
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, kernel)

            val crackFirstRegions = detectCrackFirstRegions(
                bitmap = bitmap,
                gray = gray,
                lightingProfile = lightingProfile,
                material = normalizedMaterial,
                claheGray = claheGray,
                blackhatHorizontal = blackhatHorizontal,
                blackhatVertical = blackhatVertical,
                blackhatCombined = blackhatCombined,
                crackThreshold = crackThreshold,
                crackEdges = crackEdges,
                crackCombined = crackCombined
            )

            val totalPixels = (bitmap.width * bitmap.height).coerceAtLeast(1)
            val seedMinArea = maxOf(28.0, totalPixels * 0.00006)
            val finalMinArea = maxOf(80.0, totalPixels * 0.00045)
            val minWidth = maxOf(8, (bitmap.width * 0.015).toInt())
            val minHeight = maxOf(8, (bitmap.height * 0.015).toInt())
            val seedMinEdgeDensity = lightingProfile.seedMinEdgeDensity
            val minEdgeDensity = lightingProfile.minEdgeDensity
            val seedMinLaplacianStdDev = lightingProfile.seedMinLaplacianStdDev
            val minLaplacianStdDev = lightingProfile.minLaplacianStdDev
            val mergeGapPx = maxOf(48, (minOf(bitmap.width, bitmap.height) * 0.12).toInt())
            val expandPx = maxOf(8, (minOf(bitmap.width, bitmap.height) * 0.02).toInt())
            val borderMarginPx = maxOf(12, (minOf(bitmap.width, bitmap.height) * 0.03).toInt())

            val suspiciousRegions = mutableListOf<SuspiciousRegion>()
            var suspiciousArea = 0.0
            val contourCandidates = mutableListOf<ContourCandidate>()
            val strongCrackRects = crackFirstRegions.map {
                Rect(it.bounds.x, it.bounds.y, it.bounds.width, it.bounds.height)
            }

            crackFirstRegions.forEach { region ->
                suspiciousRegions.add(region)
                suspiciousArea += region.bounds.area
            }

            val materialSupplementalRegions = when (normalizedMaterial) {
                "metal" -> detectMetalCorrosionRegions(bitmap, src)
                "wood" -> detectWoodDamageRegions(bitmap, gray)
                else -> emptyList()
            }

            materialSupplementalRegions.forEach { region ->
                suspiciousRegions.add(region)
                suspiciousArea += region.bounds.area
            }

            buildTileRects(bitmap.width, bitmap.height).forEach { tileRect ->
                val tileInput = combined.submat(tileRect).clone()
                val tileContours = mutableListOf<MatOfPoint>()
                val tileHierarchy = Mat()

                try {
                    Imgproc.findContours(
                        tileInput,
                        tileContours,
                        tileHierarchy,
                        Imgproc.RETR_EXTERNAL,
                        Imgproc.CHAIN_APPROX_SIMPLE
                    )

                    contourCandidates.addAll(
                        collectTileContourCandidates(
                            contours = tileContours,
                            offsetX = tileRect.x,
                            offsetY = tileRect.y,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            material = normalizedMaterial,
                            totalPixels = totalPixels,
                            edges = edges,
                            gray = gray,
                            laplacian = laplacian,
                            lightingProfile = lightingProfile,
                            seedMinArea = seedMinArea,
                            minWidth = minWidth,
                            minHeight = minHeight,
                            seedMinEdgeDensity = seedMinEdgeDensity,
                            seedMinLaplacianStdDev = seedMinLaplacianStdDev,
                            borderMarginPx = borderMarginPx
                        )
                    )
                } finally {
                    tileInput.release()
                    tileHierarchy.release()
                    tileContours.forEach { it.release() }
                }
            }

            mergeContourCandidates(contourCandidates, mergeGapPx).forEach { group ->
                val expandedRect = expandRect(group.bounds, expandPx, bitmap.width, bitmap.height)
                val safeRect = clipRect(expandedRect, bitmap.width, bitmap.height)
                if (safeRect.width <= 0 || safeRect.height <= 0) return@forEach
                if (strongCrackRects.isNotEmpty() && overlapsAny(safeRect, strongCrackRects, 12)) {
                    return@forEach
                }

                val roiArea = (safeRect.width.toLong() * safeRect.height.toLong()).coerceAtLeast(1L).toDouble()
                val areaWeight = roiArea / totalPixels.toDouble()
                val touchesBorder = touchesBorder(safeRect, bitmap.width, bitmap.height, borderMarginPx)
                val borderSpan = safeRect.width >= bitmap.width * NON_CRACK_FULL_SPAN_DIMENSION_RATIO ||
                    safeRect.height >= bitmap.height * NON_CRACK_FULL_SPAN_DIMENSION_RATIO

                val edgeDensity = computeEdgeDensity(edges, safeRect)
                val laplacianStdDev = computeLaplacianStdDev(gray, safeRect, laplacian)
                val fillRatio = (group.area / roiArea).coerceAtMost(1.0)
                val aspectRatio = max(
                    safeRect.width.toDouble() / safeRect.height.toDouble(),
                    safeRect.height.toDouble() / safeRect.width.toDouble()
                )
                val crackLike = isCrackLike(
                    aspectRatio = aspectRatio,
                    fillRatio = fillRatio,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = laplacianStdDev
                )
                val crackSpanOverride = crackLike && aspectRatio > 4.0
                val giantNonCrack = !crackLike && (
                    areaWeight >= NON_CRACK_FULL_SPAN_AREA_WEIGHT ||
                        borderSpan ||
                        safeRect.width >= bitmap.width * NON_CRACK_FULL_SPAN_DIMENSION_RATIO ||
                        safeRect.height >= bitmap.height * NON_CRACK_FULL_SPAN_DIMENSION_RATIO
                    )
                val lightingArtifact = touchesBorder &&
                    borderSpan &&
                    !crackLike &&
                    edgeDensity < lightingProfile.lightingArtifactEdgeDensity &&
                    laplacianStdDev < lightingProfile.lightingArtifactLaplacianStdDev
                val granularNoise = !crackLike &&
                    fillRatio < 0.008 &&
                    edgeDensity < minEdgeDensity &&
                    laplacianStdDev < minLaplacianStdDev

                val crackMinArea = maxOf(32.0, totalPixels * 0.000025)
                val crackMinEdgeDensity = lightingProfile.crackMinEdgeDensity
                val crackMinLaplacianStdDev = lightingProfile.crackMinLaplacianStdDev

                if (crackLike) {
                    if (group.area < crackMinArea && edgeDensity < crackMinEdgeDensity) {
                        logGroupRejection(
                            reason = "crack_small",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (edgeDensity < crackMinEdgeDensity * CRACK_EDGE_DENSITY_MULTIPLIER) {
                        logGroupRejection(
                            reason = "crack_low_edge_density",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (laplacianStdDev < crackMinLaplacianStdDev * CRACK_LAPLACIAN_MULTIPLIER) {
                        logGroupRejection(
                            reason = "crack_low_texture",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (crackSpanOverride) {
                        // Keep long vertical or diagonal cracks even if they span most of the image.
                    } else if (group.area >= NON_CRACK_FULL_SPAN_AREA_WEIGHT &&
                        (safeRect.width >= bitmap.width * NON_CRACK_FULL_SPAN_DIMENSION_RATIO ||
                            safeRect.height >= bitmap.height * NON_CRACK_FULL_SPAN_DIMENSION_RATIO)
                    ) {
                        logGroupRejection(
                            reason = "crack_span_override_missing",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                } else {
                    if (giantNonCrack) {
                        logGroupRejection(
                            reason = "giant_non_crack",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (group.area < finalMinArea && edgeDensity < 0.024) {
                        logGroupRejection(
                            reason = "small_roi",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (edgeDensity < minEdgeDensity) {
                        logGroupRejection(
                            reason = "low_edge_density",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                    if (laplacianStdDev < minLaplacianStdDev) {
                        logGroupRejection(
                            reason = "low_texture",
                            rect = safeRect,
                            contourArea = group.area,
                            areaWeight = areaWeight,
                            edgeDensity = edgeDensity,
                            laplacianStdDev = laplacianStdDev
                        )
                        return@forEach
                    }
                }
                if (lightingArtifact && !crackLike) {
                    logGroupRejection(
                        reason = "lighting_artifact",
                        rect = safeRect,
                        contourArea = group.area,
                        areaWeight = areaWeight,
                        edgeDensity = edgeDensity,
                        laplacianStdDev = laplacianStdDev
                    )
                    return@forEach
                }
                if (granularNoise) {
                    logGroupRejection(
                        reason = "granular_noise",
                        rect = safeRect,
                        contourArea = group.area,
                        areaWeight = areaWeight,
                        edgeDensity = edgeDensity,
                        laplacianStdDev = laplacianStdDev
                    )
                    return@forEach
                }
                if (!crackLike && fillRatio < 0.008 && group.area < finalMinArea * 1.15) {
                    logGroupRejection(
                        reason = "weak_material_loss",
                        rect = safeRect,
                        contourArea = group.area,
                        areaWeight = areaWeight,
                        edgeDensity = edgeDensity,
                        laplacianStdDev = laplacianStdDev
                    )
                    return@forEach
                }

                val roiRect = if (crackLike) {
                    if (safeRect.height >= safeRect.width) {
                        expandRectByRatio(
                            rect = safeRect,
                            widthMultiplier = CRACK_VERTICAL_WIDTH_MULTIPLIER,
                            heightMultiplier = CRACK_VERTICAL_HEIGHT_MULTIPLIER,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    } else {
                        expandRectByRatio(
                            rect = safeRect,
                            widthMultiplier = CRACK_HORIZONTAL_WIDTH_MULTIPLIER,
                            heightMultiplier = CRACK_HORIZONTAL_HEIGHT_MULTIPLIER,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    }
                } else {
                    safeRect
                }

                val squaredRect = squareCrop(roiRect, bitmap.width, bitmap.height)
                val roiBitmap = Bitmap.createBitmap(
                    bitmap,
                    squaredRect.x,
                    squaredRect.y,
                    squaredRect.width,
                    squaredRect.height
                )

                suspiciousRegions.add(
                    SuspiciousRegion(
                        bounds = RoiBounds(
                            x = safeRect.x,
                            y = safeRect.y,
                            width = safeRect.width,
                            height = safeRect.height,
                            area = group.area
                        ),
                        bitmap = roiBitmap
                    )
                )
                suspiciousArea += group.area
            }

            if (normalizedMaterial == "concrete") {
                suspiciousArea -= suppressConcreteNeighboringDamage(
                    regions = suspiciousRegions,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height
                )
                suspiciousArea = suspiciousArea.coerceAtLeast(0.0)
            }

            val pixelCount = Core.countNonZero(edges)
            val hScore = calculateMaterialAwareHScore(
                material = normalizedMaterial,
                totalPixels = totalPixels,
                crackRegions = crackFirstRegions,
                supplementalRegions = materialSupplementalRegions,
                fallbackArea = suspiciousArea
            )
            val eScore = (pixelCount.toFloat() / totalPixels.toFloat()).coerceIn(0f, 1f)

            OpenCvAnalysis(
                hScore = hScore,
                eScore = eScore,
                pixelCount = pixelCount,
                suspiciousRegions = suspiciousRegions
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV analysis failed", e)
            OpenCvAnalysis(
                hScore = 0f,
                eScore = 0f,
                pixelCount = 0,
                suspiciousRegions = emptyList()
            )
        } finally {
            src.release()
            gray.release()
            equalized.release()
            normalized.release()
            background.release()
            blurred.release()
            threshold.release()
            edges.release()
            laplacian.release()
            combined.release()
            claheGray.release()
            blackhatHorizontal.release()
            blackhatVertical.release()
            blackhatCombined.release()
            crackThreshold.release()
            crackEdges.release()
            crackCombined.release()
            nearBlackMask.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun buildTileRects(width: Int, height: Int): List<Rect> {
        val tileWidth = min(
            width,
            maxOf((width / 3.0).toInt() + maxOf(24, (width * TILE_OVERLAP_RATIO).toInt()), (width * 0.45).toInt())
        )
        val tileHeight = min(
            height,
            maxOf((height / 3.0).toInt() + maxOf(24, (height * TILE_OVERLAP_RATIO).toInt()), (height * 0.45).toInt())
        )

        val centersX = listOf(width / 6.0, width / 2.0, width * 5 / 6.0)
        val centersY = listOf(height / 6.0, height / 2.0, height * 5 / 6.0)
        val rects = mutableListOf<Rect>()

        centersY.forEach { centerY ->
            centersX.forEach { centerX ->
                val left = (centerX - tileWidth / 2.0).toInt()
                    .coerceIn(0, maxOf(0, width - tileWidth))
                val top = (centerY - tileHeight / 2.0).toInt()
                    .coerceIn(0, maxOf(0, height - tileHeight))
                rects.add(Rect(left, top, tileWidth, tileHeight))
            }
        }

        return rects.distinctBy { "${it.x}:${it.y}:${it.width}:${it.height}" }
    }

    private fun collectTileContourCandidates(
        contours: List<MatOfPoint>,
        offsetX: Int,
        offsetY: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        material: String,
        totalPixels: Int,
        edges: Mat,
        gray: Mat,
        laplacian: Mat,
        lightingProfile: LightingRobustnessProfile,
        seedMinArea: Double,
        minWidth: Int,
        minHeight: Int,
        seedMinEdgeDensity: Double,
        seedMinLaplacianStdDev: Double,
        borderMarginPx: Int
    ): List<ContourCandidate> {
        val candidates = mutableListOf<ContourCandidate>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)
            val safeRect = clipRect(
                Rect(rect.x + offsetX, rect.y + offsetY, rect.width, rect.height),
                bitmapWidth,
                bitmapHeight
            )
            val areaWeight = area / totalPixels.toDouble()
            val touchesBorder = touchesBorder(safeRect, bitmapWidth, bitmapHeight, borderMarginPx)
            val borderSpan = safeRect.width >= bitmapWidth * NON_CRACK_FULL_SPAN_DIMENSION_RATIO ||
                safeRect.height >= bitmapHeight * NON_CRACK_FULL_SPAN_DIMENSION_RATIO
            val rectArea = (safeRect.width.toLong() * safeRect.height.toLong()).coerceAtLeast(1L).toDouble()
            val seedAspectRatio = if (safeRect.width > 0 && safeRect.height > 0) {
                max(
                    safeRect.width.toDouble() / safeRect.height.toDouble(),
                    safeRect.height.toDouble() / safeRect.width.toDouble()
                )
            } else {
                0.0
            }
            val seedFillRatio = (area / rectArea).coerceIn(0.0, 1.0)
            val seedCrackLike = isCrackLike(
                aspectRatio = seedAspectRatio,
                fillRatio = seedFillRatio,
                edgeDensity = 0.0,
                laplacianStdDev = 0.0
            )

            if (material == "brick" && isMortarLikePattern(seedAspectRatio, edgeDensity = 0.0, laplacianStdDev = 0.0, fillRatio = seedFillRatio)) {
                continue
            }

            if (area < seedMinArea && !seedCrackLike) {
                logContourRejection(
                    stage = "seed",
                    reason = "min_area",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = 0.0,
                    laplacianStdDev = 0.0
                )
                continue
            }
            if ((safeRect.width < minWidth || safeRect.height < minHeight) && !seedCrackLike) {
                logContourRejection(
                    stage = "seed",
                    reason = "min_bounds",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = 0.0,
                    laplacianStdDev = 0.0
                )
                continue
            }
            if ((safeRect.width >= bitmapWidth * 0.85 || safeRect.height >= bitmapHeight * 0.85) && !seedCrackLike) {
                logContourRejection(
                    stage = "seed",
                    reason = "border_spanning",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = 0.0,
                    laplacianStdDev = 0.0
                )
                continue
            }
            if (safeRect.width <= 0 || safeRect.height <= 0) {
                logContourRejection(
                    stage = "seed",
                    reason = "empty_rect",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = 0.0,
                    edgeDensity = 0.0,
                    laplacianStdDev = 0.0
                )
                continue
            }

            val edgeDensity = computeEdgeDensity(edges, safeRect)
            if (edgeDensity < seedMinEdgeDensity && !(seedCrackLike && edgeDensity >= seedMinEdgeDensity * SEED_CRACK_EDGE_DENSITY_MULTIPLIER)) {
                logContourRejection(
                    stage = "seed",
                    reason = "low_edge_density",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = 0.0
                )
                continue
            }

            val laplacianStdDev = computeLaplacianStdDev(gray, safeRect, laplacian)
            if (laplacianStdDev < seedMinLaplacianStdDev && !(seedCrackLike && laplacianStdDev >= seedMinLaplacianStdDev * SEED_CRACK_LAPLACIAN_MULTIPLIER)) {
                logContourRejection(
                    stage = "seed",
                    reason = "low_texture",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = laplacianStdDev
                )
                continue
            }

            if (!seedCrackLike && touchesBorder && borderSpan &&
                edgeDensity < lightingProfile.borderBandEdgeDensity &&
                laplacianStdDev < lightingProfile.borderBandLaplacianStdDev
            ) {
                logContourRejection(
                    stage = "seed",
                    reason = "lighting_band_border",
                    rect = safeRect,
                    contourArea = area,
                    areaWeight = areaWeight,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = laplacianStdDev
                )
                continue
            }

            candidates.add(
                ContourCandidate(
                    bounds = safeRect,
                    area = area,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = laplacianStdDev,
                    crackLike = seedCrackLike
                )
            )
        }

        val minDim = minOf(bitmapWidth, bitmapHeight)
        val dominantCrack = candidates
            .filter { it.crackLike }
            .maxByOrNull { maxOf(it.bounds.width, it.bounds.height) }

        if (dominantCrack != null &&
            maxOf(dominantCrack.bounds.width, dominantCrack.bounds.height) >= minDim * 0.40
        ) {
            val isVertical = dominantCrack.bounds.height >= dominantCrack.bounds.width
            val bandMargin = (bitmapWidth * 0.20).toInt()
            val crackCenterX = dominantCrack.bounds.x + dominantCrack.bounds.width / 2
            val crackCenterY = dominantCrack.bounds.y + dominantCrack.bounds.height / 2

            candidates.removeAll { candidate ->
                if (candidate.crackLike) return@removeAll false
                val candidateCenterX = candidate.bounds.x + candidate.bounds.width / 2
                val candidateCenterY = candidate.bounds.y + candidate.bounds.height / 2
                if (isVertical) {
                    kotlin.math.abs(candidateCenterX - crackCenterX) <= bandMargin
                } else {
                    kotlin.math.abs(candidateCenterY - crackCenterY) <= bandMargin
                }
            }
        }

        return candidates
    }

    private fun detectCrackFirstRegions(
        bitmap: Bitmap,
        gray: Mat,
        lightingProfile: LightingRobustnessProfile,
        material: String,
        claheGray: Mat,
        blackhatHorizontal: Mat,
        blackhatVertical: Mat,
        blackhatCombined: Mat,
        crackThreshold: Mat,
        crackEdges: Mat,
        crackCombined: Mat
    ): List<SuspiciousRegion> {
        val crackContours = mutableListOf<MatOfPoint>()
        val crackHierarchy = Mat()
        val regions = mutableListOf<SuspiciousRegion>()
        val crackLaplacian = Mat()
        val blackhatDiagonal = Mat()
        val darkMask = Mat()

        try {
            val clahe = Imgproc.createCLAHE(
                CRACK_CLAHE_CLIP_LIMIT,
                Size(CRACK_CLAHE_TILE_GRID.toDouble(), CRACK_CLAHE_TILE_GRID.toDouble())
            )
            clahe.apply(gray, claheGray)

            val minDim = min(bitmap.width, bitmap.height)
            val longKernel = ensureOdd(maxOf(19, (minDim * 0.10).toInt()))
            val shortKernel = ensureOdd(maxOf(5, (minDim * 0.018).toInt()))

            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(longKernel.toDouble(), shortKernel.toDouble())
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(shortKernel.toDouble(), longKernel.toDouble())
            )
            val diagonalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(longKernel.toDouble(), (shortKernel * 1.5).toInt().toDouble())
            )

            Imgproc.morphologyEx(claheGray, blackhatHorizontal, Imgproc.MORPH_BLACKHAT, horizontalKernel)
            Imgproc.morphologyEx(claheGray, blackhatVertical, Imgproc.MORPH_BLACKHAT, verticalKernel)
            Core.max(blackhatHorizontal, blackhatVertical, blackhatCombined)
            Imgproc.morphologyEx(claheGray, blackhatDiagonal, Imgproc.MORPH_BLACKHAT, diagonalKernel)
            Core.max(blackhatCombined, blackhatDiagonal, blackhatCombined)
            Imgproc.GaussianBlur(blackhatCombined, blackhatCombined, Size(5.0, 5.0), 0.0)
            Imgproc.medianBlur(blackhatCombined, blackhatCombined, 3)
            val otsuThresh = Imgproc.threshold(
                gray,
                darkMask,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
            )
            val adaptiveThresh = minOf(otsuThresh + 30.0, 110.0)
            Imgproc.threshold(gray, darkMask, adaptiveThresh, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(blackhatCombined, darkMask, blackhatCombined)

            Imgproc.adaptiveThreshold(
                blackhatCombined,
                crackThreshold,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                ensureOdd(maxOf(31, (minDim * 0.16).toInt())),
                CRACK_ADAPTIVE_C
            )
            Imgproc.Canny(
                blackhatCombined,
                crackEdges,
                maxOf(28.0, lightingProfile.cannyLow * CRACK_CANNY_LOW_SCALE),
                maxOf(90.0, lightingProfile.cannyHigh * CRACK_CANNY_HIGH_SCALE)
            )
            Core.bitwise_or(crackThreshold, crackEdges, crackCombined)

            val openKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(3.0, 3.0)
            )
            val closeKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(5.0, 5.0)
            )
            Imgproc.morphologyEx(crackCombined, crackCombined, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(crackCombined, crackCombined, Imgproc.MORPH_CLOSE, closeKernel)
            Core.bitwise_and(crackCombined, darkMask, crackCombined)

            val crackInput = crackCombined.clone()
            try {
                Imgproc.findContours(
                    crackInput,
                    crackContours,
                    crackHierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
            } finally {
                crackInput.release()
            }

            for (contour in crackContours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val safeRect = clipRect(rect, bitmap.width, bitmap.height)
                if (safeRect.width <= 0 || safeRect.height <= 0) continue

                val roiArea = (safeRect.width.toLong() * safeRect.height.toLong()).coerceAtLeast(1L).toDouble()
                val areaWeight = area / (bitmap.width * bitmap.height).coerceAtLeast(1).toDouble()
                val edgeDensity = computeEdgeDensity(crackEdges, safeRect)
                val laplacianStdDev = computeLaplacianStdDev(gray, safeRect, crackLaplacian)
                val aspectRatio = max(
                    safeRect.width.toDouble() / safeRect.height.toDouble(),
                    safeRect.height.toDouble() / safeRect.width.toDouble()
                )
                val fillRatio = (area / roiArea).coerceIn(0.0, 1.0)
                val lineLength = maxOf(safeRect.width, safeRect.height)
                val minLineLength = maxOf(24, (maxOf(bitmap.width, bitmap.height) * 0.10).toInt())
                val relaxedMinLineLength = maxOf(18, (minDim * 0.08).toInt())
                val crackLike = isCrackLike(
                    aspectRatio = aspectRatio,
                    fillRatio = fillRatio,
                    edgeDensity = edgeDensity,
                    laplacianStdDev = laplacianStdDev
                )
                val dominantVertical = safeRect.height >= safeRect.width

                if (!crackLike) continue
                if (material == "brick" && isMortarLikePattern(aspectRatio, edgeDensity, laplacianStdDev, fillRatio)) continue
                if (aspectRatio < CRACK_MIN_ASPECT_RATIO) continue
                if (lineLength < minLineLength && !(aspectRatio > 4.0 && lineLength >= relaxedMinLineLength)) continue
                if (areaWeight < 0.000015 && edgeDensity < lightingProfile.crackMinEdgeDensity * 0.5) continue

                val expandedRect = if (dominantVertical) {
                    expandRectByRatio(
                        rect = safeRect,
                        widthMultiplier = CRACK_VERTICAL_WIDTH_MULTIPLIER,
                        heightMultiplier = CRACK_VERTICAL_HEIGHT_MULTIPLIER,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    )
                } else {
                    expandRectByRatio(
                        rect = safeRect,
                        widthMultiplier = CRACK_HORIZONTAL_WIDTH_MULTIPLIER,
                        heightMultiplier = CRACK_HORIZONTAL_HEIGHT_MULTIPLIER,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    )
                }

                val cropRect = clipRect(expandedRect, bitmap.width, bitmap.height)
                if (cropRect.width <= 0 || cropRect.height <= 0) continue
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropRect.x,
                    cropRect.y,
                    cropRect.width,
                    cropRect.height
                )

                regions.add(
                    SuspiciousRegion(
                        bounds = RoiBounds(
                            x = cropRect.x,
                            y = cropRect.y,
                            width = cropRect.width,
                            height = cropRect.height,
                            area = area
                        ),
                        bitmap = crop
                    )
                )
            }
            } finally {
                crackLaplacian.release()
                crackContours.forEach { it.release() }
                crackHierarchy.release()
                blackhatDiagonal.release()
                darkMask.release()
            }

        return regions
    }

    private fun detectMetalCorrosionRegions(
        bitmap: Bitmap,
        src: Mat
    ): List<SuspiciousRegion> {
        val rgb = Mat()
        val hsv = Mat()
        val mask = Mat()
        val closed = Mat()
        val opened = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val regions = mutableListOf<SuspiciousRegion>()

        try {
            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(
                hsv,
                Scalar(5.0, 30.0, 25.0),
                Scalar(32.0, 255.0, 230.0),
                mask
            )
            Imgproc.GaussianBlur(mask, mask, Size(5.0, 5.0), 0.0)
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, closeKernel)

            Imgproc.findContours(
                closed.clone(),
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val totalPixels = (bitmap.width * bitmap.height).coerceAtLeast(1)
            val minArea = maxOf(96.0, totalPixels * 0.0004)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) continue
                val rect = clipRect(Imgproc.boundingRect(contour), bitmap.width, bitmap.height)
                if (rect.width <= 0 || rect.height <= 0) continue
                val cropRect = expandRect(rect, 10, bitmap.width, bitmap.height)
                val squaredRect = squareCrop(cropRect, bitmap.width, bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, squaredRect.x, squaredRect.y, squaredRect.width, squaredRect.height)
                regions.add(
                    SuspiciousRegion(
                        bounds = RoiBounds(
                            x = cropRect.x,
                            y = cropRect.y,
                            width = cropRect.width,
                            height = cropRect.height,
                            area = area
                        ),
                        bitmap = crop
                    )
                )
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            rgb.release()
            hsv.release()
            mask.release()
            closed.release()
            opened.release()
        }

        return regions
    }

    private fun detectWoodDamageRegions(
        bitmap: Bitmap,
        gray: Mat
    ): List<SuspiciousRegion> {
        val blurredWood = Mat()
        val darkMask = Mat()
        val edgeMask = Mat()
        val combinedMask = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val regions = mutableListOf<SuspiciousRegion>()

        try {
            Imgproc.GaussianBlur(gray, blurredWood, Size(5.0, 5.0), 0.0)
            val blockSize = ensureOdd(maxOf(31, (minOf(bitmap.width, bitmap.height) * 0.14).toInt()))
            Imgproc.adaptiveThreshold(
                blurredWood,
                darkMask,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                blockSize,
                8.0
            )
            Imgproc.Canny(blurredWood, edgeMask, 45.0, 120.0)
            Core.bitwise_or(darkMask, edgeMask, combinedMask)
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(combinedMask, combinedMask, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(combinedMask, combinedMask, Imgproc.MORPH_CLOSE, closeKernel)

            Imgproc.findContours(
                combinedMask.clone(),
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val totalPixels = (bitmap.width * bitmap.height).coerceAtLeast(1)
            val minArea = maxOf(120.0, totalPixels * 0.00035)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) continue
                val rect = clipRect(Imgproc.boundingRect(contour), bitmap.width, bitmap.height)
                if (rect.width <= 0 || rect.height <= 0) continue
                val cropRect = expandRect(rect, 12, bitmap.width, bitmap.height)
                val squaredRect = squareCrop(cropRect, bitmap.width, bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, squaredRect.x, squaredRect.y, squaredRect.width, squaredRect.height)
                regions.add(
                    SuspiciousRegion(
                        bounds = RoiBounds(
                            x = cropRect.x,
                            y = cropRect.y,
                            width = cropRect.width,
                            height = cropRect.height,
                            area = area
                        ),
                        bitmap = crop
                    )
                )
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            blurredWood.release()
            darkMask.release()
            edgeMask.release()
            combinedMask.release()
        }

        return regions
    }

    private fun calculateMaterialAwareHScore(
        material: String,
        totalPixels: Int,
        crackRegions: List<SuspiciousRegion>,
        supplementalRegions: List<SuspiciousRegion>,
        fallbackArea: Double
    ): Float {
        val imageArea = totalPixels.coerceAtLeast(1).toDouble()
        val crackArea = crackRegions.sumOf { it.bounds.area }
        val supplementalArea = supplementalRegions.sumOf { it.bounds.area }
        val regionCount = crackRegions.size + supplementalRegions.size

        val rawScore = when (material) {
            "concrete" -> ((crackArea * 1.8) + (supplementalArea * 0.9) + (regionCount * imageArea * 0.0002)) / imageArea
            "brick" -> ((crackArea * 1.5) + (supplementalArea * 1.0) + (regionCount * imageArea * 0.00018)) / imageArea
            "metal" -> ((supplementalArea * 2.0) + (crackArea * 0.4) + (regionCount * imageArea * 0.00015)) / imageArea
            "wood" -> ((supplementalArea * 1.7) + (crackArea * 0.5) + (regionCount * imageArea * 0.00015)) / imageArea
            else -> (fallbackArea / imageArea) * 2.5
        }

        return rawScore.toFloat().coerceIn(0f, 1f)
    }

    private fun isMortarLikePattern(
        aspectRatio: Double,
        edgeDensity: Double,
        laplacianStdDev: Double,
        fillRatio: Double
    ): Boolean {
        return aspectRatio > 5.0 &&
            fillRatio < 0.30 &&
            edgeDensity < 0.045 &&
            laplacianStdDev < 11.0
    }

    private fun clipRect(rect: Rect, width: Int, height: Int): Rect {
        val x = rect.x.coerceAtLeast(0)
        val y = rect.y.coerceAtLeast(0)
        val right = (rect.x + rect.width).coerceAtMost(width)
        val bottom = (rect.y + rect.height).coerceAtMost(height)
        val clippedWidth = (right - x).coerceAtLeast(0)
        val clippedHeight = (bottom - y).coerceAtLeast(0)
        return Rect(x, y, clippedWidth, clippedHeight)
    }

    private fun expandRect(rect: Rect, padding: Int, width: Int, height: Int): Rect {
        if (rect.width <= 0 || rect.height <= 0) return Rect(0, 0, 0, 0)
        val x = (rect.x - padding).coerceAtLeast(0)
        val y = (rect.y - padding).coerceAtLeast(0)
        val right = (rect.x + rect.width + padding).coerceAtMost(width)
        val bottom = (rect.y + rect.height + padding).coerceAtMost(height)
        return Rect(x, y, (right - x).coerceAtLeast(0), (bottom - y).coerceAtLeast(0))
    }

    private fun expandRectByRatio(
        rect: Rect,
        widthMultiplier: Double,
        heightMultiplier: Double,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        if (rect.width <= 0 || rect.height <= 0) return Rect(0, 0, 0, 0)

        val targetWidth = (rect.width * widthMultiplier).toInt().coerceAtLeast(rect.width)
        val targetHeight = (rect.height * heightMultiplier).toInt().coerceAtLeast(rect.height)

        val centerX = rect.x + rect.width / 2.0
        val centerY = rect.y + rect.height / 2.0

        var left = (centerX - targetWidth / 2.0).toInt()
        var top = (centerY - targetHeight / 2.0).toInt()
        var right = left + targetWidth
        var bottom = top + targetHeight

        if (left < 0) {
            right = (right - left).coerceAtMost(imageWidth)
            left = 0
        }
        if (top < 0) {
            bottom = (bottom - top).coerceAtMost(imageHeight)
            top = 0
        }
        if (right > imageWidth) {
            val shift = right - imageWidth
            left = (left - shift).coerceAtLeast(0)
            right = imageWidth
        }
        if (bottom > imageHeight) {
            val shift = bottom - imageHeight
            top = (top - shift).coerceAtLeast(0)
            bottom = imageHeight
        }

        val finalWidth = (right - left).coerceAtLeast(0)
        val finalHeight = (bottom - top).coerceAtLeast(0)
        return Rect(left, top, finalWidth, finalHeight)
    }

    private fun squareCrop(rect: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val size = maxOf(rect.width, rect.height)
        val centerX = rect.x + rect.width / 2
        val centerY = rect.y + rect.height / 2
        var left = centerX - size / 2
        var top = centerY - size / 2
        var right = left + size
        var bottom = top + size

        if (left < 0) { right -= left; left = 0 }
        if (top < 0) { bottom -= top; top = 0 }
        if (right > imageWidth) { left -= (right - imageWidth); right = imageWidth }
        if (bottom > imageHeight) { top -= (bottom - imageHeight); bottom = imageHeight }

        left = left.coerceAtLeast(0)
        top = top.coerceAtLeast(0)
        val finalSize = minOf(right - left, bottom - top).coerceAtLeast(1)
        return Rect(left, top, finalSize, finalSize)
    }

    private fun ensureOdd(value: Int): Int = if (value % 2 == 0) value + 1 else value

    private fun mergeContourCandidates(
        candidates: List<ContourCandidate>,
        mergeGapPx: Int
    ): List<MergedContourGroup> {
        if (candidates.isEmpty()) return emptyList()

        val remaining = candidates.sortedByDescending { it.area }.toMutableList()
        val groups = mutableListOf<List<ContourCandidate>>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val group = mutableListOf(seed)
            var mergedInPass: Boolean

            do {
                mergedInPass = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    val crackAwareGap = if (group.any { it.crackLike } || candidate.crackLike) {
                        maxOf(mergeGapPx, (mergeGapPx * CRACK_MERGE_GAP_MULTIPLIER).toInt(), CRACK_MERGE_GAP_MIN)
                    } else {
                        mergeGapPx
                    }
                    if (group.any { shouldMerge(it.bounds, candidate.bounds, crackAwareGap) }) {
                        group.add(candidate)
                        iterator.remove()
                        mergedInPass = true
                    }
                }
            } while (mergedInPass)

            groups.add(group)
        }

        return groups.map { group ->
            val bounds = group.fold(group.first().bounds) { acc, candidate ->
                unionRect(acc, candidate.bounds)
            }
            val totalArea = group.fold(0.0) { acc, candidate -> acc + candidate.area }
            MergedContourGroup(bounds = bounds, area = totalArea)
        }
    }

    private fun shouldMerge(first: Rect, second: Rect, gapPx: Int): Boolean {
        val firstLeft = first.x - gapPx
        val firstTop = first.y - gapPx
        val firstRight = first.x + first.width + gapPx
        val firstBottom = first.y + first.height + gapPx

        val secondLeft = second.x - gapPx
        val secondTop = second.y - gapPx
        val secondRight = second.x + second.width + gapPx
        val secondBottom = second.y + second.height + gapPx

        return firstLeft <= secondRight &&
            secondLeft <= firstRight &&
            firstTop <= secondBottom &&
            secondTop <= firstBottom
    }

    private fun overlapsAny(rect: Rect, others: List<Rect>, padding: Int): Boolean {
        return others.any { other ->
            val leftA = rect.x - padding
            val topA = rect.y - padding
            val rightA = rect.x + rect.width + padding
            val bottomA = rect.y + rect.height + padding

            val leftB = other.x - padding
            val topB = other.y - padding
            val rightB = other.x + other.width + padding
            val bottomB = other.y + other.height + padding

            leftA <= rightB &&
                leftB <= rightA &&
                topA <= bottomB &&
                topB <= bottomA
        }
    }

    private fun suppressConcreteNeighboringDamage(
        regions: MutableList<SuspiciousRegion>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Double {
        if (regions.size <= 1) return 0.0

        val dominantCrack = regions
            .filter { isCrackLikeBounds(it.bounds) }
            .maxByOrNull { maxOf(it.bounds.width, it.bounds.height) }
            ?: return 0.0

        val dominantSize = maxOf(dominantCrack.bounds.width, dominantCrack.bounds.height)
        if (dominantSize < minOf(bitmapWidth, bitmapHeight) * 0.30) {
            return 0.0
        }

        val isVertical = dominantCrack.bounds.height >= dominantCrack.bounds.width
        val bandMargin = maxOf(
            if (isVertical) (bitmapWidth * 0.18).toInt() else (bitmapHeight * 0.18).toInt(),
            24
        )
        val crackCenterX = dominantCrack.bounds.x + dominantCrack.bounds.width / 2
        val crackCenterY = dominantCrack.bounds.y + dominantCrack.bounds.height / 2

        var removedArea = 0.0
        regions.removeAll { region ->
            if (region.bounds == dominantCrack.bounds) return@removeAll false
            if (isCrackLikeBounds(region.bounds)) return@removeAll false

            val candidateCenterX = region.bounds.x + region.bounds.width / 2
            val candidateCenterY = region.bounds.y + region.bounds.height / 2
            val nearCrackBand = if (isVertical) {
                kotlin.math.abs(candidateCenterX - crackCenterX) <= bandMargin
            } else {
                kotlin.math.abs(candidateCenterY - crackCenterY) <= bandMargin
            }
            val overlapsCrack = rectsOverlapOrTouch(region.bounds, dominantCrack.bounds, bandMargin / 2)

            if (nearCrackBand || overlapsCrack) {
                removedArea += region.bounds.area
                true
            } else {
                false
            }
        }

        return removedArea
    }

    private fun isCrackLikeBounds(bounds: RoiBounds): Boolean {
        val width = bounds.width.coerceAtLeast(0)
        val height = bounds.height.coerceAtLeast(0)
        if (width == 0 || height == 0) return false

        val aspectRatio = max(
            width.toDouble() / height.toDouble(),
            height.toDouble() / width.toDouble()
        )
        val rectArea = (width.toLong() * height.toLong()).toDouble().coerceAtLeast(1.0)
        val fillRatio = (bounds.area / rectArea).coerceIn(0.0, 1.0)
        val perimeterBias = (width + height).toDouble() / rectArea
        return aspectRatio >= 2.4 &&
            fillRatio <= 0.80 &&
            perimeterBias >= 0.005
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

    private fun unionRect(first: Rect, second: Rect): Rect {
        val left = min(first.x, second.x)
        val top = min(first.y, second.y)
        val right = max(first.x + first.width, second.x + second.width)
        val bottom = max(first.y + first.height, second.y + second.height)
        return Rect(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
    }

    private data class ContourCandidate(
        val bounds: Rect,
        val area: Double,
        val edgeDensity: Double,
        val laplacianStdDev: Double,
        val crackLike: Boolean
    )

    private data class MergedContourGroup(
        val bounds: Rect,
        val area: Double
    )

    private fun computeEdgeDensity(edges: Mat, rect: Rect): Double {
        val roi = edges.submat(rect)
        return try {
            val edgePixels = Core.countNonZero(roi).toDouble()
            edgePixels / max(1, rect.width * rect.height).toDouble()
        } finally {
            roi.release()
        }
    }

    private fun computeLaplacianStdDev(gray: Mat, rect: Rect, laplacian: Mat): Double {
        val roi = gray.submat(rect)
        return try {
            Imgproc.Laplacian(roi, laplacian, org.opencv.core.CvType.CV_64F)
            val mean = MatOfDouble()
            val stdDev = MatOfDouble()
            Core.meanStdDev(laplacian, mean, stdDev)
            stdDev.toArray().firstOrNull() ?: 0.0
        } finally {
            roi.release()
        }
    }

    private fun touchesBorder(rect: Rect, width: Int, height: Int, marginPx: Int): Boolean {
        return rect.x <= marginPx ||
            rect.y <= marginPx ||
            rect.x + rect.width >= width - marginPx ||
            rect.y + rect.height >= height - marginPx
    }

    private fun logContourRejection(
        stage: String,
        reason: String,
        rect: Rect,
        contourArea: Double,
        areaWeight: Double,
        edgeDensity: Double,
        laplacianStdDev: Double
    ) {
        Log.d(
            TAG,
            "[ROIReject] stage=$stage reason=$reason x=${rect.x} y=${rect.y} w=${rect.width} h=${rect.height} " +
                "contourArea=${formatMetric(contourArea)} areaWeight=${formatMetric(areaWeight)} " +
                "edgeDensity=${formatMetric(edgeDensity)} laplacian=${formatMetric(laplacianStdDev)}"
        )
    }

    private fun logGroupRejection(
        reason: String,
        rect: Rect,
        contourArea: Double,
        areaWeight: Double,
        edgeDensity: Double,
        laplacianStdDev: Double
    ) {
        Log.d(
            TAG,
            "[ROIReject] stage=merged reason=$reason x=${rect.x} y=${rect.y} w=${rect.width} h=${rect.height} " +
                "contourArea=${formatMetric(contourArea)} areaWeight=${formatMetric(areaWeight)} " +
                "edgeDensity=${formatMetric(edgeDensity)} laplacian=${formatMetric(laplacianStdDev)}"
        )
    }

    private fun formatMetric(value: Double): String {
        return String.format(Locale.US, "%.4f", value)
    }

    private fun isCrackLike(
        aspectRatio: Double,
        fillRatio: Double,
        edgeDensity: Double,
        laplacianStdDev: Double
    ): Boolean {
        val aspectScore = ((aspectRatio - 1.0) / CRACK_ASPECT_RATIO_SCALE)
            .coerceIn(0.0, 1.0)
        val fillScore = (1.0 - (fillRatio * CRACK_FILL_RATIO_SCALE))
            .coerceIn(0.0, 1.0)
        val edgeScore = if (edgeDensity > 0.0) {
            (edgeDensity / CRACK_EDGE_DENSITY_SCALE).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val textureScore = if (laplacianStdDev > 0.0) {
            (laplacianStdDev / CRACK_LAPLACIAN_SCALE).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val score = if (edgeDensity > 0.0 || laplacianStdDev > 0.0) {
            (aspectScore * 0.30) +
                (fillScore * 0.35) +
                (edgeScore * 0.20) +
                (textureScore * 0.15)
        } else {
            (aspectScore * 0.45) + (fillScore * 0.55)
        }

        return score >= CRACK_LIKE_SCORE_THRESHOLD
    }

    companion object {
        private const val TAG = "OpenCvStructural"
        private const val MIN_BACKGROUND_BLUR_KERNEL = 81
        private const val CRACK_CLAHE_CLIP_LIMIT = 1.8
        private const val CRACK_CLAHE_TILE_GRID = 8
        private const val CRACK_ADAPTIVE_C = 7.0
        private const val CRACK_CANNY_LOW_SCALE = 0.5
        private const val CRACK_CANNY_HIGH_SCALE = 0.45
        private const val CRACK_MIN_ASPECT_RATIO = 2.0
        private const val CRACK_ASPECT_RATIO_SCALE = 2.4
        private const val CRACK_FILL_RATIO_SCALE = 4.5
        private const val CRACK_EDGE_DENSITY_SCALE = 0.015
        private const val CRACK_LAPLACIAN_SCALE = 8.0
        private const val CRACK_LIKE_SCORE_THRESHOLD = 0.46
        private const val NON_CRACK_FULL_SPAN_AREA_WEIGHT = 0.20
        private const val NON_CRACK_FULL_SPAN_DIMENSION_RATIO = 1
        private const val SEED_CRACK_EDGE_DENSITY_MULTIPLIER = 0.50
        private const val SEED_CRACK_LAPLACIAN_MULTIPLIER = 0.65
        private const val CRACK_EDGE_DENSITY_MULTIPLIER = 0.55
        private const val CRACK_LAPLACIAN_MULTIPLIER = 0.60
        private const val CRACK_VERTICAL_WIDTH_MULTIPLIER = 1.8
        private const val CRACK_VERTICAL_HEIGHT_MULTIPLIER = 3.2
        private const val CRACK_HORIZONTAL_WIDTH_MULTIPLIER = 3.2
        private const val CRACK_HORIZONTAL_HEIGHT_MULTIPLIER = 1.8
        private const val CRACK_MERGE_GAP_MULTIPLIER = 2.0
        private const val CRACK_MERGE_GAP_MIN = 48
        private const val TILE_OVERLAP_RATIO = 0.20
    }
}
