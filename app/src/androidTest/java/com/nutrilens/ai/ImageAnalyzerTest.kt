package com.nutrilens.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutrilens.ai.vision.ImageAnalyzer
import com.nutrilens.ai.vision.PhotoIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The OpenCV photo check on a real device (native library, real photo). */
@RunWith(AndroidJUnit4::class)
class ImageAnalyzerTest {

    private val analyzer = ImageAnalyzer()
    private val photo: Bitmap = InstrumentationRegistry.getInstrumentation().context.assets.open("pizza.jpg")
        .use { BitmapFactory.decodeStream(it) }

    @Test fun sharpWellLitPhotoHasNoIssuesAndIsNotAltered() {
        val q = assertNotNull(analyzer.analyze(photo))
        assertEquals(emptyList<PhotoIssue>(), q.issues)
        assertSame(photo, analyzer.enhanceForModel(photo, q))
    }

    @Test fun blurredPhotoIsFlagged() {
        // Shrink to 1/16 and scale back up: a strong blur with the same colours.
        val small = Bitmap.createScaledBitmap(photo, photo.width / 16, photo.height / 16, true)
        val blurred = Bitmap.createScaledBitmap(small, photo.width, photo.height, true)
        val q = assertNotNull(analyzer.analyze(blurred))
        assertTrue("sharpness ${q.sharpness}", PhotoIssue.BLURRY in q.issues)
    }

    @Test fun darkPhotoIsFlaggedAndBrightened() {
        val dark = scaled(photo, 0.2f)
        val q = assertNotNull(analyzer.analyze(dark))
        assertTrue("brightness ${q.brightness}", PhotoIssue.TOO_DARK in q.issues)
        val fixed = analyzer.enhanceForModel(dark, q)
        val after = assertNotNull(analyzer.analyze(fixed)).brightness
        assertTrue("brightness $after after auto gamma (was ${q.brightness})", after > ImageAnalyzer.MIN_BRIGHTNESS)
    }

    @Test fun suggestedRegionIsInsideThePhoto() {
        val r = analyzer.suggestRegion(photo)
        assertTrue(r.toString(), r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f && r.width() > 0.2f && r.height() > 0.2f)
    }

    private fun assertNotNull(q: com.nutrilens.ai.vision.PhotoQuality?) = q.also { assertNotNull("OpenCV did not load", it) }!!

    private fun scaled(src: Bitmap, factor: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(factor, factor, factor, 1f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
