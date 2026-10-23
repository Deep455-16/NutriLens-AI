package com.nutrilens.ai.vision

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * Reads retail barcodes (EAN-13, EAN-8, UPC-A, UPC-E) from camera frames with ZXing, offline.
 * The camera sensor is usually turned 90° from the screen, so a frame is tried as-is and rotated.
 */
object BarcodeReader {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
        DecodeHintType.TRY_HARDER to true
    )

    /** [luma] is one byte of brightness per pixel, row by row. Returns the digits, or null. */
    fun decode(luma: ByteArray, width: Int, height: Int): String? =
        decodeOnce(luma, width, height) ?: decodeOnce(rotate90(luma, width, height), height, width)

    /** Brightness from an RGBA_8888 camera frame (CameraX ImageAnalysis output). */
    fun lumaFromRgba(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            var i = y * rowStride
            for (x in 0 until width) {
                val r = rgba.get(i).toInt() and 0xFF
                val g = rgba.get(i + 1).toInt() and 0xFF
                val b = rgba.get(i + 2).toInt() and 0xFF
                out[y * width + x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                i += 4
            }
        }
        return out
    }

    /** True for an 8, 12 or 13-digit code whose last digit is the correct GS1 check digit. */
    fun isValid(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13) || !code.all(Char::isDigit)) return false
        val digits = code.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.withIndex().sumOf { (i, d) -> if (i % 2 == 0) d * 3 else d }
        return (10 - sum % 10) % 10 == digits.last()
    }

    private fun decodeOnce(luma: ByteArray, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    } catch (e: ReaderException) {
        null
    }

    private fun rotate90(luma: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(luma.size)
        for (y in 0 until height) for (x in 0 until width) {
            out[x * height + (height - 1 - y)] = luma[y * width + x]
        }
        return out
    }
}
