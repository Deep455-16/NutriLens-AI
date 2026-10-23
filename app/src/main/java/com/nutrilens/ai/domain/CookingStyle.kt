package com.nutrilens.ai.domain

import androidx.annotation.StringRes
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import kotlin.math.roundToInt

/**
 * How much oil / ghee went into the dish, relative to the dataset's typical recipe.
 * A rough, clearly-labelled estimate: only fat changes (and its 9 kcal per gram).
 */
enum class CookingStyle(@StringRes val label: Int, val fatMultiplier: Float) {
    HOMESTYLE(R.string.style_less_oil, 0.65f),
    STANDARD(R.string.style_standard, 1f),
    RESTAURANT(R.string.style_extra_oil, 1.45f);

    fun applyTo(meal: MealEntity): MealEntity {
        if (this == STANDARD) return meal
        val fat = meal.fat * fatMultiplier
        val calories = (meal.calories + ((fat - meal.fat) * 9f).roundToInt()).coerceAtLeast(1)
        return meal.copy(calories = calories, fat = fat, satFat = meal.satFat?.let { it * fatMultiplier })
    }

    companion object {
        fun fromOrdinal(ordinal: Int): CookingStyle = entries.getOrElse(ordinal) { STANDARD }
    }
}
