package com.nutrilens.ai.data

import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.toMeal
import org.junit.Assert.assertEquals
import org.junit.Test

class ServingScaleTest {

    private val pizza = NutritionEntity("pizza", servingGrams = 107, calories = 285,
        protein = 12f, carbs = 36f, fat = 10f, fiber = 2.5f, sugar = 4f, sodiumMg = 600f, satFat = 4.5f,
        serving = "1 slice")

    @Test fun oneServingIsUnchanged() {
        val m = pizza.toMeal(0.9f, null, 0L)
        assertEquals(285, m.calories)
        assertEquals(107, m.servingGrams)
        assertEquals(12f, m.protein, 0f)
        assertEquals(0.9f, m.confidence, 0f)
    }

    @Test fun doubleServingDoublesEverything() {
        val m = pizza.toMeal(0.9f, null, 0L, servings = 2f)
        assertEquals(570, m.calories)
        assertEquals(214, m.servingGrams)
        assertEquals(24f, m.protein, 1e-4f)
        assertEquals(72f, m.carbs, 1e-4f)
        assertEquals(20f, m.fat, 1e-4f)
        assertEquals(5f, m.fiber, 1e-4f)
        assertEquals(8f, m.sugar!!, 1e-4f)
        assertEquals(1200f, m.sodiumMg!!, 1e-4f)
        assertEquals(9f, m.satFat!!, 1e-4f)
        assertEquals("1 slice", m.serving)
    }

    @Test fun halfServingRoundsToNearest() {
        val m = pizza.toMeal(-1f, "file:///x.jpg", 42L, servings = 0.5f)
        assertEquals(143, m.calories)      // 142.5 rounds up
        assertEquals(54, m.servingGrams)   // 53.5 rounds up
        assertEquals("file:///x.jpg", m.imageUri)
        assertEquals(42L, m.timestamp)
        assertEquals("Pizza", m.foodName)
    }
}
