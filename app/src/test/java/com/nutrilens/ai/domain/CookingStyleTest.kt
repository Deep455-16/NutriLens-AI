package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CookingStyleTest {

    private val meal = MealEntity(
        foodLabel = "paneer_tikka", foodName = "Paneer Tikka", confidence = 0.9f, calories = 280, protein = 18f,
        carbs = 8f, fat = 20f, fiber = 2f, servingGrams = 150, imageUri = null, timestamp = 0, satFat = 10f
    )

    @Test fun standardLeavesTheMealUnchanged() = assertSame(meal, CookingStyle.STANDARD.applyTo(meal))

    @Test fun onlyFatAndItsCaloriesChange() {
        val less = CookingStyle.HOMESTYLE.applyTo(meal)
        assertEquals(13f, less.fat, 0.01f)
        assertEquals(280 - 63, less.calories) // 7 g less fat x 9 kcal
        assertEquals(6.5f, less.satFat!!, 0.01f)
        assertEquals(meal.protein, less.protein, 0f)
        assertEquals(meal.carbs, less.carbs, 0f)

        val more = CookingStyle.RESTAURANT.applyTo(meal)
        assertEquals(29f, more.fat, 0.01f)
        assertEquals(280 + 81, more.calories)
    }
}
