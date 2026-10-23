package com.nutrilens.ai.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ResizeTest {

    private fun gray(vararg v: Int) = IntArray(v.size) { (0xFF shl 24) or (v[it] shl 16) or (v[it] shl 8) or v[it] }

    @Test fun sameSizeIsIdentity() {
        val out = resizeBilinearRgb(gray(10, 20, 30, 40), 2, 2, 2)
        assertArrayEquals(floatArrayOf(10f, 10f, 10f, 20f, 20f, 20f, 30f, 30f, 30f, 40f, 40f, 40f), out, 1e-4f)
    }

    @Test fun halvingAveragesPairsLikeTfImageResize() {
        // 4x4 -> 2x2 with half-pixel centers samples exactly between source pixels 0/1 and 2/3.
        val src = gray(0, 10, 20, 30, 0, 10, 20, 30, 0, 10, 20, 30, 0, 10, 20, 30)
        val out = resizeBilinearRgb(src, 4, 4, 2)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 25f, 25f, 25f, 5f, 5f, 5f, 25f, 25f, 25f), out, 1e-4f)
    }

    @Test fun channelsStayInRgbOrder() {
        val red = intArrayOf((0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50)
        assertArrayEquals(floatArrayOf(200f, 100f, 50f), resizeBilinearRgb(red, 1, 1, 1), 1e-4f)
    }
}
