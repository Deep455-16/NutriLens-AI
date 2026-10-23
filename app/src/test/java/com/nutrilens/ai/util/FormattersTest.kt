package com.nutrilens.ai.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test fun manualSelectionNeverShowsAPercentage() {
        assertEquals(null, confidencePercent(-1f))
        assertEquals(87, confidencePercent(0.874f))
        assertEquals("never claims certainty", 99, confidencePercent(0.9997f))
    }

    @Test fun displayName() {
        assertEquals("Fish and Chips", "fish_and_chips".toDisplayName())
    }
}
