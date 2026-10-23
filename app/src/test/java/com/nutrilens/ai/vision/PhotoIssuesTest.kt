package com.nutrilens.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoIssuesTest {

    @Test fun sharpWellLitPhotoHasNoIssues() {
        assertEquals(emptyList<PhotoIssue>(), ImageAnalyzer.issuesFor(sharpness = 248.0, brightness = 117.0))
    }

    @Test fun thresholds() {
        assertEquals(listOf(PhotoIssue.BLURRY), ImageAnalyzer.issuesFor(3.0, 117.0))
        assertEquals(listOf(PhotoIssue.TOO_DARK), ImageAnalyzer.issuesFor(248.0, 44.0))
        assertEquals("mildly soft photos are fine", emptyList<PhotoIssue>(), ImageAnalyzer.issuesFor(13.2, 123.0))
        assertEquals(listOf(PhotoIssue.TOO_BRIGHT), ImageAnalyzer.issuesFor(248.0, 240.0))
        assertEquals(listOf(PhotoIssue.BLURRY, PhotoIssue.TOO_DARK), ImageAnalyzer.issuesFor(2.0, 20.0))
    }
}
