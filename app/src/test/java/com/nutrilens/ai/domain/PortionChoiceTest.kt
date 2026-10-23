package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.FoodPortion
import org.junit.Assert.assertEquals
import org.junit.Test

class PortionChoiceTest {

    private val dosa = listOf(
        FoodPortion("masala_dosa", "1 dosa", 267f, 0),
        FoodPortion("masala_dosa", "1 small", 113f, 1),
        FoodPortion("masala_dosa", "1 large", 523f, 2)
    )
    private val choice = PortionChoice(dosa, baseGrams = 267f)

    @Test fun standardServingIsOneTimesTheBase() {
        assertEquals(267f, choice.grams, 0f)
        assertEquals(1f, choice.servings, 0f)
        assertEquals("1 dosa", choice.text)
    }

    @Test fun portionAndQuantity() {
        val c = choice.select(1).step(up = true).step(up = true)   // 2 × 1 small
        assertEquals(226f, c.grams, 0.01f)
        assertEquals(226f / 267f, c.servings, 1e-4f)
        assertEquals("2 × 1 small", c.text)
    }

    @Test fun quantityStaysInRange() {
        var c = choice
        repeat(5) { c = c.step(up = false) }
        assertEquals(PortionChoice.MIN_QTY, c.quantity, 0f)
    }

    @Test fun customGramsOverrideAndAreClearedByChoosingAPortion() {
        val c = choice.withGrams(150f)
        assertEquals("150 g", c.text)
        assertEquals(150f / 267f, c.servings, 1e-4f)
        assertEquals(523f, c.select(2).grams, 0f)
    }

    @Test fun savedMealsMapBackToAPortion() {
        assertEquals("2 × 1 small", PortionChoice.forSavedGrams(dosa, 267f, 226f).text)
        assertEquals("1 large", PortionChoice.forSavedGrams(dosa, 267f, 523f).text)
        assertEquals("200 g", PortionChoice.forSavedGrams(dosa, 267f, 200f).text)
    }
}
