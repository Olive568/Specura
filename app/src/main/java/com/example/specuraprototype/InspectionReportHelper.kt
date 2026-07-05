package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.gson.Gson
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InspectionReportHelper {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 28f

    fun buildCsvReport(scans: List<ScanEntity>, gson: Gson, scanDataType: java.lang.reflect.Type): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = "Location,Timestamp,Date,Material,Status,VerifiedDefects,HScore,EScore,ImageUri\n"
        val builder = StringBuilder(header)

        scans.sortedBy { it.timestamp }.forEach { scan ->
            val data: Map<String, Any> = gson.fromJson(scan.jsonData, scanDataType)
            val material = (data["material"] as? String).orEmpty().ifBlank { "Unknown" }
            val status = if (extractBoolean(data["clip_verified"])) "DEFECT VERIFIED" else "INTACT"
            val defects = formatDefectSummaryJson(data["verified_defects"])
            val hScore = extractScore(data, "h_score", "H")
            val eScore = extractScore(data, "e_score", "E")
            val dateStr = sdf.format(Date(scan.timestamp))

            builder.append(csvValue(scan.location)).append(',')
            builder.append(scan.timestamp).append(',')
            builder.append(csvValue(dateStr)).append(',')
            builder.append(csvValue(material)).append(',')
            builder.append(csvValue(status)).append(',')
            builder.append(csvValue(defects)).append(',')
            builder.append(String.format(Locale.US, "%.4f", hScore)).append(',')
            builder.append(String.format(Locale.US, "%.4f", eScore)).append(',')
            builder.append(csvValue((data["imageUri"] as? String).orEmpty())).append('\n')
        }

        return builder.toString()
    }

    fun writePdfReport(
        context: Context,
        outputStream: OutputStream,
        scans: List<ScanEntity>,
        gson: Gson,
        scanDataType: java.lang.reflect.Type
    ): Boolean {
        val orderedScans = scans.sortedBy { it.timestamp }
        val sections = orderedScans.mapNotNull { scan ->
            val ledger = runCatching {
                gson.fromJson(scan.jsonData, TechnicalLedgerRecord::class.java)
            }.getOrNull() ?: return@mapNotNull null

            val locationKey = LocationHelper.normalizeLocationTag(scan.location)
            val sourceBitmap = ledger.imageUri.takeIf { it.isNotBlank() }?.let {
                DefectOverlayDialogHelper.loadBitmap(context, it)
            }

            val highlightedBitmap = sourceBitmap?.let {
                DefectOverlayDialogHelper.buildHighlightedDefectBitmap(it, ledger.verified_defects)
            }

            val rawBitmap = sourceBitmap?.let {
                DefectOverlayDialogHelper.buildRawDetectionBitmap(it)
            }

            val historyForTrend = orderedScans.filter {
                LocationHelper.normalizeLocationTag(it.location) == locationKey && it.timestamp <= scan.timestamp
            }
            val trend = InspectionTrendAnalyzer.analyze(historyForTrend, gson, scanDataType)

            PdfSection(
                locationKey = locationKey,
                scan = scan,
                ledger = ledger,
                trend = trend,
                sourceBitmap = sourceBitmap,
                highlightedBitmap = highlightedBitmap,
                rawBitmap = rawBitmap
            )
        }

        if (sections.isEmpty()) return false

        val document = PdfDocument()
        try {
            sections.forEachIndexed { index, section ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                drawSectionPage(context, page.canvas, section, index + 1, sections.size)
                document.finishPage(page)
            }
            document.writeTo(outputStream)
            return true
        } finally {
            document.close()
            sections.forEach { it.recycle() }
        }
    }

    private fun drawSectionPage(
        context: Context,
        canvas: Canvas,
        section: PdfSection,
        pageNumber: Int,
        totalPages: Int
    ) {
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 11f
        }
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F8FAFC")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1.5f
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            strokeWidth = 1.2f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 11f
        }

        val left = MARGIN
        val right = PAGE_WIDTH - MARGIN
        var top = MARGIN + 20f

        canvas.drawText("SPECURA INSPECTION REPORT", left, top, titlePaint)
        top += 20f

        val currentDate = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            .format(Date(section.scan.timestamp))
        canvas.drawText("Location: ${section.locationKey}", left, top, subtitlePaint)
        top += 14f
        canvas.drawText("Inspection Date: $currentDate", left, top, subtitlePaint)
        top += 18f
        canvas.drawLine(left, top, right, top, dividerPaint)
        top += 12f
        canvas.drawText(context.getString(R.string.inspection_overview_title), left, top, sectionTitlePaint)
        top += 10f

        val assessmentTop = top
        drawAssessmentDetailsBox(
            canvas = canvas,
            context = context,
            section = section,
            left = left,
            top = assessmentTop,
            width = right - left,
            height = 164f
        )

        top = assessmentTop + 174f
        drawImageSection(canvas, context.getString(R.string.captured_surface_title), section.sourceBitmap, left, top, right - left, 94f)
        top += 122f
        drawImageSection(canvas, context.getString(R.string.highlighted_defects_title), section.highlightedBitmap, left, top, right - left, 94f)
        top += 122f
        drawImageSection(canvas, context.getString(R.string.raw_detection_title), section.rawBitmap, left, top, right - left, 100f)
        top += 128f

        drawRoundedBox(canvas, left, top, right - left, 84f, boxPaint, borderPaint)
        canvas.drawText(context.getString(R.string.inspection_summary_title), left + 14f, top + 20f, sectionTitlePaint)
        val summary = buildSummaryText(section)
        drawWrappedText(canvas, summary, left + 14f, top + 40f, right - left - 28f, bodyPaint)

        val footer = "Page $pageNumber / $totalPages"
        canvas.drawText(footer, right - 80f, PAGE_HEIGHT - 12f, subtitlePaint)
    }

    private fun drawAssessmentDetailsBox(
        canvas: Canvas,
        context: Context,
        section: PdfSection,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ) {
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 9.5f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 9.5f
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F8FAFC")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1.3f
        }

        drawRoundedBox(canvas, left, top, width, height, boxPaint, borderPaint)

        val titleLeft = left + 14f
        var y = top + 18f
        canvas.drawText(context.getString(R.string.assessment_details_title), titleLeft, y, sectionTitlePaint)
        y += 16f

        val columnGap = 16f
        val innerWidth = width - 28f
        val columnWidth = (innerWidth - columnGap) / 2f
        val rowHeight = 14f

        val trendLabel = section.trend?.trendLabel ?: "N/A"
        val lastInspectionDate = section.trend?.lastInspectionDate ?: "N/A"
        val daysSinceLast = section.trend?.daysSinceLastInspection ?: "N/A"
        val defects = formatDefectSummary(section.ledger.verified_defects)
        y = drawDetailPairRow(
            canvas = canvas,
            left = titleLeft,
            baseline = y,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            labelWidth = 78f,
            leftLabel = "Location Tag",
            leftValue = section.locationKey,
            rightLabel = "Material",
            rightValue = section.ledger.material.ifBlank { "Unknown" },
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            columnGap = columnGap
        )
        y = drawDetailPairRow(
            canvas = canvas,
            left = titleLeft,
            baseline = y,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            labelWidth = 78f,
            leftLabel = "Status",
            leftValue = if (section.ledger.clip_verified) "DEFECT VERIFIED" else "INTACT",
            rightLabel = "Condition Trend",
            rightValue = trendLabel,
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            columnGap = columnGap
        )
        y = drawDetailPairRow(
            canvas = canvas,
            left = titleLeft,
            baseline = y,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            labelWidth = 120f,
            leftLabel = "Last Inspection Date",
            leftValue = lastInspectionDate,
            rightLabel = "Days Since Last Inspection",
            rightValue = daysSinceLast,
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            columnGap = columnGap
        )
        y = drawDetailPairRow(
            canvas = canvas,
            left = titleLeft,
            baseline = y,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            labelWidth = 78f,
            leftLabel = "H-Score",
            leftValue = String.format(Locale.US, "%.4f", section.ledger.h_score),
            rightLabel = "E-Score",
            rightValue = String.format(Locale.US, "%.4f", section.ledger.e_score),
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            columnGap = columnGap
        )
        y = drawDetailPairRow(
            canvas = canvas,
            left = titleLeft,
            baseline = y,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            labelWidth = 120f,
            leftLabel = "Timestamp",
            leftValue = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(section.scan.timestamp)),
            rightLabel = "",
            rightValue = "",
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            columnGap = columnGap
        )

        val defectsText = if (defects.isBlank()) "No Defects" else defects
        drawWrappedText(
            canvas,
            "Detected Defects: $defectsText",
            titleLeft,
            y + 1f,
            innerWidth,
            bodyPaint
        )
    }

    private fun drawDetailPairRow(
        canvas: Canvas,
        left: Float,
        baseline: Float,
        columnWidth: Float,
        rowHeight: Float,
        labelWidth: Float,
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
        labelPaint: Paint,
        valuePaint: Paint,
        columnGap: Float
    ): Float {
        drawDetail(
            canvas = canvas,
            left = left,
            baseline = baseline,
            columnWidth = columnWidth,
            labelWidth = labelWidth,
            label = leftLabel,
            value = leftValue,
            labelPaint = labelPaint,
            valuePaint = valuePaint
        )
        if (rightLabel.isNotBlank()) {
            drawDetail(
                canvas = canvas,
                left = left + columnWidth + columnGap,
                baseline = baseline,
                columnWidth = columnWidth,
                labelWidth = labelWidth,
                label = rightLabel,
                value = rightValue,
                labelPaint = labelPaint,
                valuePaint = valuePaint
            )
        }
        return baseline + rowHeight
    }

    private fun drawDetail(
        canvas: Canvas,
        left: Float,
        baseline: Float,
        columnWidth: Float,
        labelWidth: Float,
        label: String,
        value: String,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        canvas.drawText("$label:", left, baseline, labelPaint)
        val valueStart = left + minOf(labelWidth, columnWidth - 12f)
        val fittedValue = fitText(value.ifBlank { "N/A" }, valuePaint, (columnWidth - (valueStart - left)).coerceAtLeast(24f))
        canvas.drawText(fittedValue, valueStart, baseline, valuePaint)
    }

    private fun drawImageSection(
        canvas: Canvas,
        title: String,
        bitmap: Bitmap?,
        left: Float,
        top: Float,
        width: Float,
        boxHeight: Float
    ) {
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 11f
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F8FAFC")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1.3f
        }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawText(title, left, top + 13f, sectionTitlePaint)
        val boxTop = top + 18f
        drawRoundedBox(canvas, left, boxTop, width, boxHeight, boxPaint, borderPaint)

        val inner = RectF(left + 10f, boxTop + 10f, left + width - 10f, boxTop + boxHeight - 10f)
        if (bitmap != null) {
            val srcWidth = bitmap.width.toFloat()
            val srcHeight = bitmap.height.toFloat()
            val scale = minOf(inner.width() / srcWidth, inner.height() / srcHeight)
            val drawnWidth = srcWidth * scale
            val drawnHeight = srcHeight * scale
            val dest = RectF(
                inner.centerX() - drawnWidth / 2f,
                inner.centerY() - drawnHeight / 2f,
                inner.centerX() + drawnWidth / 2f,
                inner.centerY() + drawnHeight / 2f
            )
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
        } else {
            drawCenteredText(canvas, "Unavailable", inner, placeholderPaint)
        }
    }

    private fun drawRoundedBox(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        fillPaint: Paint,
        borderPaint: Paint
    ) {
        val rect = RectF(left, top, left + width, top + height)
        canvas.drawRoundRect(rect, 14f, 14f, fillPaint)
        canvas.drawRoundRect(rect, 14f, 14f, borderPaint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        width: Float,
        paint: Paint
    ) {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val lineHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top) + 2f
        var currentLine = StringBuilder()
        var y = top

        fun drawCurrentLine() {
            if (currentLine.length > 0) {
                canvas.drawText(currentLine.toString(), left, y, paint)
                y += lineHeight
                currentLine = StringBuilder()
            }
        }

        words.forEach { word ->
            val candidate = if (currentLine.length == 0) word else "${currentLine} $word"
            if (paint.measureText(candidate) > width && currentLine.length > 0) {
                drawCurrentLine()
                currentLine.append(word)
            } else {
                if (currentLine.length > 0) currentLine.append(' ')
                currentLine.append(word)
            }
        }
        drawCurrentLine()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        val fm = paint.fontMetrics
        val x = rect.centerX() - (paint.measureText(text) / 2f)
        val y = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, x, y, paint)
    }

    private fun buildSummaryText(section: PdfSection): String {
        val defectText = formatDefectSummary(section.ledger.verified_defects)
        val trendText = section.trend?.trendLabel ?: "N/A"
        return if (defectText.isBlank()) {
            "No verified defects were recorded for this inspection. The surface is currently classified as intact and the trend is $trendText."
        } else {
            "This inspection recorded $defectText. Condition trend is $trendText for ${section.locationKey}. The technical ledger stores the image, defect overlay, and raw detection evidence."
        }
    }

    private fun extractBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun extractScore(data: Map<String, Any>, primaryKey: String, fallbackKey: String): Double {
        val primary = (data[primaryKey] as? Number)?.toDouble()
        if (primary != null) return primary
        return (data[fallbackKey] as? Number)?.toDouble() ?: 0.0
    }

    private fun formatDefectSummaryJson(value: Any?): String {
        val labels = when (value) {
            is List<*> -> value.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> (item["label"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    is String -> item.trim().takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            is String -> value.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }

        return summarizeDefectLabels(labels)
    }

    private fun formatDefectSummary(defects: List<VerifiedDefectRecord>): String {
        return summarizeDefectLabels(defects.mapNotNull { it.label.trim().takeIf { label -> label.isNotBlank() } })
    }

    private fun summarizeDefectLabels(labels: List<String>): String {
        if (labels.isEmpty()) return ""

        val counts = linkedMapOf<String, Int>()
        val displayLabels = linkedMapOf<String, String>()
        labels.forEach { label ->
            val key = label.lowercase(Locale.getDefault())
            counts[key] = (counts[key] ?: 0) + 1
            if (!displayLabels.containsKey(key)) {
                displayLabels[key] = label.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
            }
        }

        return counts.entries.joinToString(", ") { entry ->
            val label = displayLabels[entry.key].orEmpty()
            if (entry.value > 1) "$label (${entry.value})" else label
        }
    }

    private fun csvValue(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun fitText(value: String, paint: Paint, maxWidth: Float): String {
        if (value.isBlank() || paint.measureText(value) <= maxWidth) return value

        val ellipsis = "..."
        if (paint.measureText(ellipsis) > maxWidth) return ""

        var end = value.length
        while (end > 0 && paint.measureText(value.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return if (end <= 0) ellipsis else value.substring(0, end) + ellipsis
    }

    private data class PdfSection(
        val locationKey: String,
        val scan: ScanEntity,
        val ledger: TechnicalLedgerRecord,
        val trend: InspectionTrendOverview?,
        val sourceBitmap: Bitmap?,
        val highlightedBitmap: Bitmap?,
        val rawBitmap: Bitmap?
    ) {
        fun recycle() {
            sourceBitmap?.recycle()
            highlightedBitmap?.recycle()
            rawBitmap?.recycle()
        }
    }
}
