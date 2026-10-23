package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.NutritionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextMealSuggesterTest {

    private fun food(label: String, kcal: Int, protein: Float) =
        NutritionEntity(label, 100, kcal, protein, 20f, 5f, 2f, serving = "1 serving")

    private val foods = listOf(
        food("salmon", 363, 36f), food("pizza", 458, 20f), food("edamame", 112, 9f),
        food("cheesecake", 499, 7f), food("dal", 474, 21f)
    )

    @Test fun picksMostProteinPerKcalThatFits() {
        val s = NextMealSuggester.suggest(foods, caloriesLeft = 400, proteinLeft = 45f)
        assertEquals(listOf("salmon"), s.map { it.food.label })   // edamame (112 kcal) is a snack, not a meal
        assertEquals(36 to 363, s.first().protein to s.first().calories)
    }

    @Test fun smallFoodsAreFineWhenLittleIsLeft() {
        assertEquals(listOf("edamame"), NextMealSuggester.suggest(foods, caloriesLeft = 150, proteinLeft = 20f).map { it.food.label })
    }

    @Test fun nothingWhenTheDayIsFull() {
        assertEquals(emptyList<NextMealSuggestion>(), NextMealSuggester.suggest(foods, caloriesLeft = 50, proteinLeft = 30f))
    }

    @Test fun neverSuggestsMoreThanWhatIsLeft() {
        assertTrue(NextMealSuggester.suggest(foods, caloriesLeft = 460, proteinLeft = 80f).all { it.food.calories <= 460 })
    }

    @Test fun prefersTheGivenCuisineWhenEnoughFit() {
        val all = listOf(
            NutritionEntity("filet_mignon", 200, 314, 49f, 0f, 12f, 0f),
            NutritionEntity("prime_rib", 200, 412, 58f, 0f, 20f, 0f),
            NutritionEntity("pork_chop", 200, 220, 33f, 0f, 10f, 0f),
            NutritionEntity("chana_masala", 240, 265, 12f, 30f, 10f, 8f),
            NutritionEntity("dal", 240, 348, 20f, 46f, 10f, 18f),
            NutritionEntity("paneer_tikka", 150, 280, 18f, 8f, 19f, 2f),
            NutritionEntity("lassi", 250, 260, 9f, 40f, 7f, 0f)
        )
        val picks = NextMealSuggester.suggest(all, 1300, 70f, prefer = { isIndianDish(it.label) }).map { it.food.label }
        assertEquals(setOf("chana_masala", "dal", "paneer_tikka"), picks.toSet()) // lassi is a drink, not a meal
        val any = NextMealSuggester.suggest(all, 1300, 70f).map { it.food.label }
        assertTrue("filet_mignon" in any)
    }
}
