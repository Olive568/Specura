package com.example.specuraprototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object OpenCvPreviewHelper {

    private const val TAG = "OpenCvPreview"

    @Volatile
    private var openCvLoaded = false

    fun showPreviewDialog(context: Context, imageUri: String?) {
        if (imageUri.isNullOrBlank()) {
            Toast.makeText(context, R.string.opencv_preview_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val previewBitmap = createPreviewBitmap(context, imageUri)
        if (previewBitmap == null) {
            Toast.makeText(context, R.string.opencv_preview_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val imageView = ImageView(context).apply {
            setImageBitmap(previewBitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            addView(imageView)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.opencv_preview_title)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun createPreviewBitmap(context: Context, imageUri: String): Bitmap? {
        if (!ensureOpenCvLoaded()) return null

        val sourceBitmap = context.contentResolver.openInputStream(Uri.parse(imageUri))?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val previewMat = Mat()

        return try {
            Utils.bitmapToMat(sourceBitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blurred, edges, 90.0, 180.0)
            Imgproc.cvtColor(edges, previewMat, Imgproc.COLOR_GRAY2RGBA)

            Bitmap.createBitmap(previewMat.cols(), previewMat.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(previewMat, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build OpenCV preview", e)
            null
        } finally {
            rgba.release()
            gray.release()
            blurred.release()
            edges.release()
            previewMat.release()
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
