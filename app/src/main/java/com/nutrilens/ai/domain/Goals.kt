package com.nutrilens.ai.domain

import androidx.annotation.StringRes
import com.nutrilens.ai.R
import kotlin.math.roundToInt

/** How the daily calorie goal is split between protein, carbs and fat (percent of kcal). */
enum class MacroSplit(@StringRes val label: Int, val proteinPct: Int, val carbsPct: Int, val fatPct: Int) {
    BALANCED(R.string.macro_balanced, 20, 50, 30),
    HIGH_PROTEIN(R.string.macro_high_protein, 30, 40, 30),
    LOWER_CARB(R.string.macro_lower_carb, 30, 25, 45);

    /** Daily gram targets: protein and carbs have 4 kcal/g, fat 9 kcal/g. */
    fun targets(calories: Int) = MacroTargets(
        protein = (calories * proteinPct / 100f / 4f).roundToInt(),
        carbs = (calories * carbsPct / 100f / 4f).roundToInt(),
        fat = (calories * fatPct / 100f / 9f).roundToInt()
    )

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

data class MacroTargets(val protein: Int, val carbs: Int, val fat: Int)

enum class Sex { MALE, FEMALE }

enum class Activity(@StringRes val label: Int, val factor: Float) {
    LOW(R.string.activity_low, 1.2f),
    MODERATE(R.string.activity_moderate, 1.375f),
    HIGH(R.string.activity_high, 1.55f)
}

/** Goal keys as stored in preferences (language-independent); [label] is what is shown. */
object GoalNames {
    const val LOSE = "Lose Weight"
    const val MAINTAIN = "Maintain Weight"
    const val GAIN = "Gain Weight"
    val ALL = arrayOf(LOSE, MAINTAIN, GAIN)

    @StringRes
    fun label(goal: String): Int = when (goal) {
        LOSE -> R.string.goal_lose
        GAIN -> R.string.goal_gain
        else -> R.string.goal_maintain
    }
}

object CalorieGoal {

    const val MIN = 1200
    const val MAX = 4500

    /**
     * Suggested daily kcal: Mifflin-St Jeor resting energy × activity factor, -500 to lose /
     * +300 to gain, rounded to 50 and kept within [MIN]..[MAX]. A starting point the user can
     * edit, not medical advice.
     */
    fun suggest(sex: Sex, age: Int, heightCm: Int, weightKg: Int, activity: Activity, goal: String): Int {
        val bmr = 10f * weightKg + 6.25f * heightCm - 5f * age + if (sex == Sex.MALE) 5f else -161f
        val adjust = when (goal) {
            GoalNames.LOSE -> -500
            GoalNames.GAIN -> 300
            else -> 0
        }
        val kcal = bmr * activity.factor + adjust
        return ((kcal / 50f).roundToInt() * 50).coerceIn(MIN, MAX)
    }
}
