package com.nutrilens.ai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageUtils {

    private const val MEALS_DIR = "meals"

    /** New file in the app's private storage where a meal photo is kept. */
    fun createMealFile(context: Context): File {
        val dir = File(context.filesDir, MEALS_DIR).apply { mkdirs() }
        return File(dir, "meal_${System.currentTimeMillis()}.jpg")
    }

    /** Copies an image picked from the gallery into private storage so it stays available. */
    suspend fun copyToInternal(context: Context, source: Uri): Uri = withContext(Dispatchers.IO) {
        val target = createMealFile(context)
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Unable to read the selected image")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        Uri.fromFile(target)
    }

    /** Decodes a (down-sampled) bitmap and applies the EXIF rotation. */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to decode the image")

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return decoded
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** Deletes a meal photo, but only if it lives in the app's own meals folder. */
    fun deleteInternalImage(uri: String?) {
        if (uri.isNullOrBlank()) return
        val file = Uri.parse(uri).path?.let { File(it) } ?: return
        if (file.parentFile?.name == MEALS_DIR && file.exists()) file.delete()
    }

    fun deleteAllMealImages(context: Context) {
        File(context.filesDir, MEALS_DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Deletes photos in the meals folder that no saved meal points to (left behind by
     * Retake, Back, or the app being killed before Save). Files newer than [minAgeMs] are
     * kept so a scan that is in progress right now is never touched. Returns the count deleted.
     */
    fun deleteUnreferenced(context: Context, referencedUris: Collection<String>, minAgeMs: Long = 60 * 60 * 1000L): Int {
        val keep = referencedUris.mapNotNull { Uri.parse(it).path }.toSet()
        val cutoff = System.currentTimeMillis() - minAgeMs
        return File(context.filesDir, MEALS_DIR).listFiles().orEmpty()
            .filter { it.path !in keep && it.lastModified() < cutoff }
            .count { it.delete() }
    }
}
