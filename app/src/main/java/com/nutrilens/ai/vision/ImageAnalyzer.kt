package com.nutrilens.ai.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.annotation.StringRes
import com.nutrilens.ai.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Problems that make recognition less reliable, worth telling the user about. */
enum class PhotoIssue(@StringRes val message: Int, @StringRes val live: Int) {
    BLURRY(R.string.issue_blurry, R.string.live_blurry),
    TOO_DARK(R.string.issue_dark, R.string.live_dark),
    TOO_BRIGHT(R.string.issue_bright, R.string.live_bright)
}

data class PhotoQuality(
    /** Variance of the Laplacian at 512 px: low means few sharp edges, i.e. blur. */
    val sharpness: Double,
    /** Mean grey level 0..255. */
    val brightness: Double,
    val issues: List<PhotoIssue>
)

/**
 * OpenCV photo-quality check run before recognition. Mirrors ml/vision_eval.py exactly
 * (same OpenCV version, 512 px INTER_AREA resize, Laplacian variance, mean brightness).
 *
 * Measured in vision_eval.py on 2,441 held-out photos (see docs/MODEL_CARD.md):
 *  - CLAHE and cropping made the classifier less accurate, so they are not applied;
 *  - auto-gamma on *dark* photos raised top-1 from 71.9% to 74.8%, so [enhanceForModel] applies
 *    it only below [MIN_BRIGHTNESS]; normal photos are never altered.
 */
@Singleton
class ImageAnalyzer @Inject constructor() {

    private val ready: Boolean by lazy {
        OpenCVLoader.initLocal().also { if (!it) Log.w(TAG, "OpenCV failed to load; skipping quality check") }
    }

    /** Returns null if OpenCV could not load; recognition then simply proceeds without it. */
    fun analyze(bitmap: Bitmap): PhotoQuality? {
        if (!ready) return null
        val rgba = Mat()
        val small = Mat()
        val gray = Mat()
        val lap = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val scale = ANALYSIS_SIDE / max(rgba.cols(), rgba.rows()).toDouble()
            if (scale < 1.0) {
                Imgproc.resize(rgba, small, Size((rgba.cols() * scale).roundToInt().toDouble(),
                    (rgba.rows() * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                rgba.copyTo(small)
            }
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(lap, mean, std)
            val sharpness = std.get(0, 0)[0].let { it * it }
            val brightness = Core.mean(gray).`val`[0]
            return PhotoQuality(sharpness, brightness, issuesFor(sharpness, brightness))
        } finally {
            rgba.release(); small.release(); gray.release(); lap.release()
        }
    }

    /**
     * The photo the model should see: a brightened copy if [quality] says it is dark (auto gamma
     * to mean 0.5, same maths as vision_eval.auto_gamma), otherwise [bitmap] itself.
     */
    fun enhanceForModel(bitmap: Bitmap, quality: PhotoQuality?): Bitmap {
        if (!ready || quality == null || quality.brightness >= MIN_BRIGHTNESS) return bitmap
        val mean = max(quality.brightness / 255.0, 1e-3)
        val gamma = ln(0.5) / ln(mean)
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val rgba = Mat()
        try {
            // astype(uint8) in numpy truncates, so floor here too.
            lut.put(0, 0, ByteArray(256) { i -> floor(min(255.0, (i / 255.0).pow(gamma) * 255.0)).toInt().toByte() })
            Utils.bitmapToMat(bitmap, rgba)
            val channels = ArrayList<Mat>()
            Core.split(rgba, channels)
            for (c in 0 until 3) Core.LUT(channels[c], lut, channels[c]) // RGB only, keep alpha
            Core.merge(channels, rgba)
            channels.forEach { it.release() }
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, out)
            return out
        } finally {
            lut.release(); rgba.release()
        }
    }

    /**
     * Where the food probably is, as 0..1 fractions of [bitmap]: the largest plate or bowl found
     * with a Hough circle transform (same parameters as vision_eval.plate_crop), otherwise the
     * central 70%. Only a starting point for the region the user marks; never applied silently,
     * because automatic cropping measured slightly worse (77.3% vs 79.2%).
     */
    fun suggestRegion(bitmap: Bitmap): RectF {
        val fallback = RectF(0.15f, 0.15f, 0.85f, 0.85f)
        if (!ready) return fallback
        val rgba = Mat()
        val small = Mat()
        val gray = Mat()
        val circles = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val scale = min(1.0, ANALYSIS_SIDE / max(rgba.cols(), rgba.rows()).toDouble())
            Imgproc.resize(rgba, small, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.medianBlur(gray, gray, 5)
            val m = min(gray.cols(), gray.rows()).toDouble()
            Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.2, m, 120.0, 60.0, (m * 0.3).toInt(), (m * 0.6).toInt())
            if (circles.cols() == 0) return fallback
            val c = circles.get(0, 0)
            val r = c[2] * 1.05
            return RectF(
                ((c[0] - r) / gray.cols()).toFloat().coerceIn(0f, 1f),
                ((c[1] - r) / gray.rows()).toFloat().coerceIn(0f, 1f),
                ((c[0] + r) / gray.cols()).toFloat().coerceIn(0f, 1f),
                ((c[1] + r) / gray.rows()).toFloat().coerceIn(0f, 1f)
            )
        } finally {
            rgba.release(); small.release(); gray.release(); circles.release()
        }
    }

    companion object {
        private const val TAG = "ImageAnalyzer"
        const val ANALYSIS_SIDE = 512

        // From ml/vision_eval.py: sharp photos score >= 97 (10th percentile); blur that costs
        // 8 points of accuracy scores ~3.4; mildly soft photos (~13) lose only ~1 point.
        const val MIN_SHARPNESS = 8.0
        // Darkened test photos averaged 44, normal ones 123.
        const val MIN_BRIGHTNESS = 60.0
        const val MAX_BRIGHTNESS = 225.0

        fun issuesFor(sharpness: Double, brightness: Double): List<PhotoIssue> = buildList {
            if (sharpness < MIN_SHARPNESS) add(PhotoIssue.BLURRY)
            if (brightness < MIN_BRIGHTNESS) add(PhotoIssue.TOO_DARK)
            if (brightness > MAX_BRIGHTNESS) add(PhotoIssue.TOO_BRIGHT)
        }
    }
}
