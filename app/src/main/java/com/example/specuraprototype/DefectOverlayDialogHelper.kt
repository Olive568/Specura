package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import org.opencv.core.Size
import org.opencv.core.Scalar
import java.util.Locale

object DefectOverlayDialogHelper {

    private const val TAG = "DefectOverlayDialog"
    @Volatile
    private var openCvLoaded = false

    fun showDefectOverlayDialog(
        context: Context,
        imageUri: String?,
        defects: List<VerifiedDefectRecord>
    ) {
        if (imageUri.isNullOrBlank()) {
            Toast.makeText(context, R.string.defect_overlay_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = loadBitmap(context, imageUri)
        if (bitmap == null) {
            Toast.makeText(context, R.string.defect_overlay_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val rawBitmap = buildRawDetectionBitmap(bitmap)

        val overlayView = DefectOverlayView(context).apply {
            setSourceBitmap(bitmap)
            setDefects(defects)
        }

        val highlightedContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(
                overlayView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val rawView = if (rawBitmap != null) {
            ImageView(context).apply {
                setImageBitmap(rawBitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        } else {
            TextView(context).apply {
                text = context.getString(R.string.raw_detection_unavailable)
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.DKGRAY)
                textSize = 15f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        }

        val rawContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(rawView)
        }

        val contentFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 420)
            ).apply {
                topMargin = dp(context, 12)
            }
            addView(highlightedContainer)
            addView(rawContainer)
            rawContainer.visibility = View.GONE
        }

        val tabs = TabLayout(context).apply {
            tabMode = TabLayout.MODE_FIXED
            addTab(newTab().setText(R.string.highlighted_defects_tab))
            addTab(newTab().setText(R.string.raw_detection_tab))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: 0
                highlightedContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
                rawContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            addView(tabs)
            addView(contentFrame)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.show_defects_title)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .create()

        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            if (rawBitmap != null && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
        }
        dialog.show()
    }

    fun loadBitmap(context: Context, imageUri: String): Bitmap? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image bitmap", e)
            null
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun buildHighlightedDefectBitmap(
        sourceBitmap: Bitmap,
        defects: List<VerifiedDefectRecord>
    ): Bitmap? {
        val overlay = try {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy source bitmap", e)
            null
        } ?: return null

        val canvas = Canvas(overlay)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            alpha = 210
        }
        val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 200
        }
        val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = Color.WHITE
        }

        canvas.drawBitmap(sourceBitmap, 0f, 0f, bitmapPaint)
        defects.forEach { defect ->
            val roi = defect.roi
            if (roi.width <= 0 || roi.height <= 0) return@forEach

            val x1 = roi.x.coerceIn(0, sourceBitmap.width)
            val y1 = roi.y.coerceIn(0, sourceBitmap.height)
            val x2 = (roi.x + roi.width).coerceIn(0, sourceBitmap.width)
            val y2 = (roi.y + roi.height).coerceIn(0, sourceBitmap.height)

            val rect = RectF(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
            val color = colorForLabel(defect.label)
            boxPaint.color = color
            labelBackgroundPaint.color = color
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            val label = buildLabel(defect)
            drawLabel(canvas, label, rect, color, labelTextPaint, labelBackgroundPaint)
        }

        return overlay
    }

    fun buildRawDetectionBitmap(sourceBitmap: Bitmap): Bitmap? {
        if (!ensureOpenCvLoaded()) return null

        val rgba = Mat()
        val gray = Mat()
        val equalized = Mat()
        val blurred = Mat()
        val threshold = Mat()
        val edges = Mat()
        val combined = Mat()
        val contourInput = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val preview = Mat()

        return try {
            Utils.bitmapToMat(sourceBitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.equalizeHist(gray, equalized)
            Imgproc.GaussianBlur(equalized, blurred, Size(7.0, 7.0), 0.0)
            Imgproc.threshold(
                blurred,
                threshold,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )
            Imgproc.Canny(blurred, edges, 110.0, 220.0)
            Core.bitwise_or(threshold, edges, combined)

            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, closeKernel)

            combined.copyTo(contourInput)
            Imgproc.findContours(
                contourInput,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            Imgproc.cvtColor(combined, preview, Imgproc.COLOR_GRAY2RGBA)
            Imgproc.drawContours(
                preview,
                contours,
                -1,
                Scalar(255.0, 0.0, 0.0, 255.0),
                2
            )

            Bitmap.createBitmap(preview.cols(), preview.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(preview, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build raw detection bitmap", e)
            null
        } finally {
            rgba.release()
            gray.release()
            equalized.release()
            blurred.release()
            threshold.release()
            edges.release()
            combined.release()
            contourInput.release()
            hierarchy.release()
            preview.release()
            contours.forEach { it.release() }
        }
    }

    private fun ensureOpenCvLoaded(): Boolean {
        if (openCvLoaded) return true
        openCvLoaded = OpenCVLoader.initDebug()
        if (!openCvLoaded) {
            Log.e(TAG, "OpenCV init failed")
        }
        return openCvLoaded
    }

    private fun buildLabel(defect: VerifiedDefectRecord): String {
        val label = defect.label.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
        return label
    }

    private fun colorForLabel(label: String): Int {
        return when (label.lowercase(Locale.getDefault())) {
            "crack" -> Color.parseColor("#E53935")
            "possible crack" -> Color.parseColor("#FB8C00")
            "corrosion" -> Color.parseColor("#FB8C00")
            "chipping" -> Color.parseColor("#FDD835")
            "stain" -> Color.parseColor("#1E88E5")
            "deterioration" -> Color.parseColor("#8E24AA")
            else -> Color.parseColor("#00ACC1")
        }
    }

    private fun contrastingTextColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r) + (0.587 * g) + (0.114 * b)
        return if (luminance > 186) Color.BLACK else Color.WHITE
    }

    private fun drawLabel(
        canvas: Canvas,
        label: String,
        rect: RectF,
        color: Int,
        labelTextPaint: Paint,
        labelBackgroundPaint: Paint
    ) {
        val paddingX = 8f
        val paddingY = 4f
        val fm = labelTextPaint.fontMetrics
        val textHeight = fm.bottom - fm.top
        val textWidth = labelTextPaint.measureText(label)
        val backgroundWidth = textWidth + paddingX * 2
        val backgroundHeight = textHeight + paddingY * 2

        var left = rect.left
        left = left.coerceIn(0f, (canvas.width - backgroundWidth).coerceAtLeast(0f))

        var top = rect.top - backgroundHeight - 6f
        if (top < 0f) {
            top = rect.top + 6f
        }
        if (top + backgroundHeight > canvas.height) {
            top = (canvas.height - backgroundHeight).coerceAtLeast(0f)
        }

        val background = RectF(left, top, left + backgroundWidth, top + backgroundHeight)
        canvas.drawRoundRect(background, 6f, 6f, labelBackgroundPaint)

        labelTextPaint.color = contrastingTextColor(color)
        val baseline = background.top + paddingY - fm.top
        canvas.drawText(label, background.left + paddingX, baseline, labelTextPaint)
    }

    private class DefectOverlayView(context: Context) : View(context) {

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 2).toFloat()
            alpha = 210
        }
        private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 200
        }
        private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(context, 13f)
            color = Color.WHITE
        }

        private var sourceBitmap: Bitmap? = null
        private var defects: List<VerifiedDefectRecord> = emptyList()

        fun setSourceBitmap(bitmap: Bitmap) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setDefects(items: List<VerifiedDefectRecord>) {
            defects = items
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val bitmap = sourceBitmap ?: return
            if (width <= 0 || height <= 0) return

            val bmpWidth = bitmap.width.toFloat()
            val bmpHeight = bitmap.height.toFloat()
            val scale = minOf(width / bmpWidth, height / bmpHeight)
            val drawnWidth = bmpWidth * scale
            val drawnHeight = bmpHeight * scale
            val left = (width - drawnWidth) / 2f
            val top = (height - drawnHeight) / 2f
            val dest = RectF(left, top, left + drawnWidth, top + drawnHeight)

            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawBitmap(bitmap, null, dest, bitmapPaint)

            defects.forEach { defect ->
                val roi = defect.roi
                if (roi.width <= 0 || roi.height <= 0) return@forEach

                val x1 = roi.x.coerceIn(0, bitmap.width)
                val y1 = roi.y.coerceIn(0, bitmap.height)
                val x2 = (roi.x + roi.width).coerceIn(0, bitmap.width)
                val y2 = (roi.y + roi.height).coerceIn(0, bitmap.height)

                val mapped = RectF(
                    dest.left + x1.toFloat() * scale,
                    dest.top + y1.toFloat() * scale,
                    dest.left + x2.toFloat() * scale,
                    dest.top + y2.toFloat() * scale
                )

                val color = colorForLabel(defect.label)
                boxPaint.color = color
                labelBackgroundPaint.color = color

                canvas.drawRoundRect(mapped, dp(context, 6).toFloat(), dp(context, 6).toFloat(), boxPaint)

                val label = buildLabel(defect)
                drawLabel(canvas, label, mapped, color)
            }
        }

        private fun drawLabel(canvas: Canvas, label: String, rect: RectF, color: Int) {
            val paddingX = dp(context, 8).toFloat()
            val paddingY = dp(context, 4).toFloat()
            val fm = labelTextPaint.fontMetrics
            val textHeight = fm.bottom - fm.top
            val textWidth = labelTextPaint.measureText(label)
            val backgroundWidth = textWidth + paddingX * 2
            val backgroundHeight = textHeight + paddingY * 2

            var left = rect.left
            left = left.coerceIn(0f, (width - backgroundWidth).coerceAtLeast(0f))

            var top = rect.top - backgroundHeight - dp(context, 6)
            if (top < 0f) {
                top = rect.top + dp(context, 6)
            }
            if (top + backgroundHeight > height) {
                top = (height - backgroundHeight).coerceAtLeast(0f)
            }

            val background = RectF(left, top, left + backgroundWidth, top + backgroundHeight)
            canvas.drawRoundRect(background, dp(context, 6).toFloat(), dp(context, 6).toFloat(), labelBackgroundPaint)

            labelTextPaint.color = contrastingTextColor(color)
            val baseline = background.top + paddingY - fm.top
            canvas.drawText(label, background.left + paddingX, baseline, labelTextPaint)
        }

    private fun buildLabel(defect: VerifiedDefectRecord): String {
        val label = defect.label.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
        return label
    }

        private fun colorForLabel(label: String): Int {
            return when (label.lowercase(Locale.getDefault())) {
                "crack" -> Color.parseColor("#E53935")
                "corrosion" -> Color.parseColor("#FB8C00")
                "chipping" -> Color.parseColor("#FDD835")
                "stain" -> Color.parseColor("#1E88E5")
                "deterioration" -> Color.parseColor("#8E24AA")
                else -> Color.parseColor("#00ACC1")
            }
        }

        private fun contrastingTextColor(color: Int): Int {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val luminance = (0.299 * r) + (0.587 * g) + (0.114 * b)
            return if (luminance > 186) Color.BLACK else Color.WHITE
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private fun sp(context: Context, value: Float): Float =
            value * context.resources.displayMetrics.scaledDensity
    }
}
