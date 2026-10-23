package com.nutrilens.ai.domain

import kotlin.math.roundToInt

/**
 * FDA Daily Values for adults on a 2,000 kcal diet (21 CFR 101.9), used for the "% DV" column.
 * Total sugars have no Daily Value.
 */
object DailyValues {
    const val FAT_G = 78f
    const val SAT_FAT_G = 20f
    const val SODIUM_MG = 2300f
    const val CARBS_G = 275f
    const val FIBER_G = 28f
    const val PROTEIN_G = 50f
    const val CALCIUM_MG = 1300f
    const val IRON_MG = 18f
    const val POTASSIUM_MG = 4700f
    const val VITAMIN_C_MG = 90f
    const val VITAMIN_B12_UG = 2.4f

    /** FDA values assume 2,000 kcal; [Personal] rescales the energy-linked ones to the user's goal. */
    const val REFERENCE_KCAL = 2000

    data class Personal(
        val calorieGoal: Int,
        val fat: Float, val satFat: Float, val carbs: Float, val fiber: Float, val protein: Float
    )

    /**
     * Daily Values for [calorieGoal]: fat, saturated fat, carbohydrate, fiber and protein scale
     * with energy needs; sodium and vitamins/minerals do not (their limits are not per-kcal).
     */
    fun personal(calorieGoal: Int): Personal {
        val f = (if (calorieGoal > 0) calorieGoal else REFERENCE_KCAL) / REFERENCE_KCAL.toFloat()
        return Personal(calorieGoal, FAT_G * f, SAT_FAT_G * f, CARBS_G * f, FIBER_G * f, PROTEIN_G * f)
    }

    /** FDA guidance: 20% DV or more in one serving is "high", 5% or less is "low". */
    const val HIGH_PERCENT = 20

    fun percent(amount: Float, dailyValue: Float): Int = (amount / dailyValue * 100f).roundToInt()
}
