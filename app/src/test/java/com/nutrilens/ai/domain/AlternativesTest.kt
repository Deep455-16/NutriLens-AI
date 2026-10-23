package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.NutritionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativesTest {

    private fun food(label: String, kcal: Int, protein: Float = 5f, fiber: Float = 1f) = NutritionEntity(
        label = label, servingGrams = 100, calories = kcal, protein = protein, carbs = 20f, fat = 5f, fiber = fiber
    )

    private val all = listOf(
        food("chai", 154), food("lassi", 260), food("buttermilk", 55),
        food("samosa", 260), food("idli", 97, protein = 3f), food("dal", 180, protein = 12f, fiber = 6f),
        food("chana_masala", 240, protein = 12f, fiber = 8f), food("escargots", 134, protein = 20f),
        food("tuna_tartare", 153, protein = 25f), food("ice_cream", 210), food("kulfi", 180), food("frozen_yogurt", 150)
    )

    @Test fun drinkGetsDrinks() {
        val picks = pickAlternatives(all.first { it.label == "chai" }, all)
        assertTrue(picks.map { it.label }.toString(), picks.all { kindOf(it.label) == FoodKind.DRINK })
        assertEquals("buttermilk", picks.first().label) // lighter drinks come first
    }

    @Test fun indianDishGetsIndianDishesFirst() {
        val picks = pickAlternatives(all.first { it.label == "samosa" }, all).map { it.label }
        assertEquals(setOf("dal", "chana_masala"), picks.take(2).toSet())
        assertTrue(picks.none { it == "ice_cream" || it == "lassi" })
    }

    @Test fun dessertGetsDesserts() {
        val picks = pickAlternatives(all.first { it.label == "ice_cream" }, all).map { it.label }
        assertEquals(setOf("kulfi", "frozen_yogurt"), picks.toSet())
    }
}
