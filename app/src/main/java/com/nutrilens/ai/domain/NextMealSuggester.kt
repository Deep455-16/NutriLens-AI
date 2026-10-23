package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.NutritionEntity
import kotlin.math.min
import kotlin.math.roundToInt

/** A food that fits what is left of today's goals, with the numbers behind the choice. */
data class NextMealSuggestion(val food: NutritionEntity, val protein: Int, val calories: Int)

/**
 * "What should I eat next?": meal-sized foods (one standard serving) that fit the calories left today and
 * fill as much of the remaining protein as possible per kcal; ties go to the better Nutrition
 * Score. Transparent on purpose: the reason line says exactly why a food was picked.
 */
object NextMealSuggester {

    fun suggest(
        foods: List<NutritionEntity>,
        caloriesLeft: Int,
        proteinLeft: Float,
        limit: Int = 3,
        /** Foods to favour (e.g. the cuisine the user eats); used when enough of them fit. */
        prefer: (NutritionEntity) -> Boolean = { true }
    ): List<NextMealSuggestion> {
        if (caloriesLeft < 100) return emptyList()
        // A suggestion should be a meal, not a 79 kcal starter: at least 200 kcal (or 15% of
        // what is left), unless very little is left.
        val minKcal = minOf(maxOf(200, caloriesLeft * 15 / 100), caloriesLeft / 2)
        // A meal, not a drink or a dessert.
        val fits = foods.filter { it.calories in minKcal..caloriesLeft && kindOf(it.label) == FoodKind.DISH }
        val preferred = fits.filter(prefer)
        return (if (preferred.size >= limit) preferred else fits)
            .sortedWith(
                compareByDescending<NutritionEntity> { min(it.protein, proteinLeft.coerceAtLeast(0f)) / it.calories }
                    .thenByDescending { NutritionScore.evaluate(it).score }
                    .thenBy { it.label }
            )
            .take(limit)
            .map { f -> NextMealSuggestion(f, f.protein.roundToInt(), f.calories) }
    }
}
