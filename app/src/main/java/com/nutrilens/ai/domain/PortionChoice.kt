package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.FoodPortion
import kotlin.math.roundToInt

/**
 * How much of a food was eaten: [quantity] × one of the food's USDA [portions], or an exact
 * [customGrams]. Shared by the Result, Food detail and meal Details screens.
 */
data class PortionChoice(
    val portions: List<FoodPortion>,
    val baseGrams: Float,
    val selected: Int = 0,
    val quantity: Float = 1f,
    val customGrams: Float? = null
) {
    val portion: FoodPortion? get() = portions.getOrNull(selected)

    val grams: Float get() = customGrams ?: ((portion?.grams ?: baseGrams) * quantity)

    /** Multiplier of the dataset's standard serving, used to scale every nutrient. */
    val servings: Float get() = if (baseGrams > 0f) grams / baseGrams else 1f

    /** e.g. "2 × 1 medium dosa", "1 bowl", "150 g". */
    val text: String
        get() = when {
            customGrams != null -> "${customGrams.roundToInt()} g"
            quantity == 1f -> portion?.description ?: "1 serving"
            else -> "${qty(quantity)} × ${portion?.description ?: "serving"}"
        }

    fun select(index: Int) = copy(selected = index.coerceIn(0, (portions.size - 1).coerceAtLeast(0)), customGrams = null)

    fun step(up: Boolean): PortionChoice {
        val q = if (customGrams != null) 1f else quantity
        return copy(quantity = (q + if (up) STEP else -STEP).coerceIn(MIN_QTY, MAX_QTY), customGrams = null)
    }

    fun withGrams(g: Float) = copy(customGrams = g.coerceIn(1f, MAX_GRAMS))

    companion object {
        const val STEP = 0.5f
        const val MIN_QTY = 0.5f
        const val MAX_QTY = 10f
        const val MAX_GRAMS = 3000f

        private fun qty(q: Float) = if (q % 1f == 0f) q.toInt().toString() else q.toString()

        /**
         * Rebuilds the choice for a saved meal from its stored grams: an exact portion × quantity
         * when it matches one (to 1 g), otherwise custom grams.
         */
        fun forSavedGrams(portions: List<FoodPortion>, baseGrams: Float, grams: Float): PortionChoice {
            val base = PortionChoice(portions, baseGrams)
            portions.forEachIndexed { i, p ->
                val q = grams / p.grams
                val rounded = (q / STEP).roundToInt() * STEP
                if (rounded in MIN_QTY..MAX_QTY && kotlin.math.abs(rounded * p.grams - grams) < 1f) {
                    return base.copy(selected = i, quantity = rounded)
                }
            }
            return base.withGrams(grams)
        }
    }
}
