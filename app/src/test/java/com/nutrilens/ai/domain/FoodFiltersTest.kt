package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.NutritionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FoodFiltersTest {

    private fun food(label: String, kcal: Int, protein: Float, fiber: Float = 1f) =
        NutritionEntity(label, 100, kcal, protein, 20f, 10f, fiber)

    private val foods = listOf(
        food("grilled_salmon", 370, 34f),
        food("french_fries", 365, 4f, fiber = 4f),
        food("caesar_salad", 330, 10f, fiber = 3f),
        food("edamame", 190, 17f, fiber = 8f)
    )

    @Test fun searchMatchesWordsWithSpaces() {
        assertEquals(listOf("grilled_salmon"), foods.browse("grilled sal", FoodFilter.ALL, FoodSort.NAME).map { it.label })
    }

    @Test fun filters() {
        assertEquals(listOf("edamame", "grilled_salmon"), foods.browse("", FoodFilter.HIGH_PROTEIN, FoodSort.NAME).map { it.label })
        assertEquals(listOf("edamame"), foods.browse("", FoodFilter.LOW_CALORIE, FoodSort.NAME).map { it.label })
        assertEquals(listOf("edamame", "french_fries"), foods.browse("", FoodFilter.HIGH_FIBER, FoodSort.NAME).map { it.label })
    }

    @Test fun filterIndian() {
        val testFoods = foods + listOf(
            food("biryani", 350, 14f),
            food("palak_paneer", 280, 12f)
        )
        val indianOnly = testFoods.browse("", FoodFilter.INDIAN, FoodSort.NAME).map { it.label }
        assertEquals(listOf("biryani", "palak_paneer"), indianOnly)
    }

    @Test fun featuredPutsIndianDishesWithPhotosFirst() {
        val mixed = foods + listOf(food("tandoori_chicken", 310, 41f), food("biryani", 350, 14f))
        val order = mixed.browse("", FoodFilter.ALL, FoodSort.FEATURED, hasPhoto = { it == "biryani" || it == "edamame" }).map { it.label }
        assertEquals(listOf("biryani", "tandoori_chicken", "edamame", "caesar_salad", "french_fries", "grilled_salmon"), order)
    }

    @Test fun sorting() {
        assertEquals("edamame", foods.browse("", FoodFilter.ALL, FoodSort.CALORIES_LOW).first().label)
        assertEquals("grilled_salmon", foods.browse("", FoodFilter.ALL, FoodSort.PROTEIN_HIGH).first().label)
    }

    @Test fun searchMatchesTranslatedName() {
        val hindi = mapOf("edamame" to "एडामामे", "grilled_salmon" to "ग्रिल्ड सैल्मन")
        assertEquals(listOf("grilled_salmon"), foods.browse("सैल्मन", FoodFilter.ALL, FoodSort.NAME, name = { hindi[it] ?: it }).map { it.label })
        assertEquals(listOf("grilled_salmon"), foods.browse("grilled sal", FoodFilter.ALL, FoodSort.NAME, name = { hindi[it] ?: it }).map { it.label })
    }
}
