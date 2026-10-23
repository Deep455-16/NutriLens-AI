package com.nutrilens.ai.vision

import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeReaderTest {

    /** A barcode drawn into a camera-sized grey frame with a margin (black bars = 0). */
    private fun frame(code: String, format: BarcodeFormat, w: Int = 640, h: Int = 480): ByteArray {
        val m = if (format == BarcodeFormat.EAN_8) EAN8Writer().encode(code, format, 400, 160)
                else EAN13Writer().encode(code, format, 480, 160)
        val out = ByteArray(w * h) { 0xE0.toByte() } // light background
        val ox = (w - m.width) / 2
        val oy = (h - m.height) / 2
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) out[(oy + y) * w + ox + x] = 0x20
        }
        return out
    }

    private fun rotate(luma: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(luma.size)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = luma[y * w + x]
        return out
    }

    @Test fun readsEan13() = assertEquals("8901058851298", BarcodeReader.decode(frame("8901058851298", BarcodeFormat.EAN_13), 640, 480))

    @Test fun readsBarcodeHeldSideways() {
        val sideways = rotate(frame("3017620422003", BarcodeFormat.EAN_13), 640, 480)
        assertEquals("3017620422003", BarcodeReader.decode(sideways, 480, 640))
    }

    @Test fun readsEan8() = assertEquals("96385074", BarcodeReader.decode(frame("96385074", BarcodeFormat.EAN_8), 640, 480))

    @Test fun blankFrameHasNoBarcode() = assertNull(BarcodeReader.decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 480))

    @Test fun checkDigit() {
        assertTrue(BarcodeReader.isValid("8901058851298"))
        assertTrue(BarcodeReader.isValid("3017620422003"))
        assertTrue(BarcodeReader.isValid("96385074"))
        assertTrue(BarcodeReader.isValid("036000291452")) // UPC-A
        assertFalse(BarcodeReader.isValid("8901058851299"))
        assertFalse(BarcodeReader.isValid("12345"))
        assertFalse(BarcodeReader.isValid("89010588512a8"))
    }
}
