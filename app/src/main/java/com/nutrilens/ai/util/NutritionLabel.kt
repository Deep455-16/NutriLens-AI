package com.nutrilens.ai.util

import android.content.Context
import android.widget.LinearLayout
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.domain.DailyValues
import kotlin.math.roundToInt

/** "% DV" values at 20% or more (FDA "high") are shown in this warning colour. */
private val WARN = R.color.macro_fat

/**
 * Fills [this] (a vertical LinearLayout) with a US-style Nutrition Facts table for [meal]:
 * amount and % Daily Value per row, using [dv] (the user's calorie-adjusted values).
 * Nutrients unknown for older saved meals show "–"; vitamins and minerals are listed when known.
 */
fun LinearLayout.renderNutritionLabel(meal: MealEntity, dv: DailyValues.Personal = DailyValues.personal(DailyValues.REFERENCE_KCAL)) {
    removeAllViews()
    fun g(v: Float?) = when {
        v == null -> "–"
        v == 0f -> "0 g"
        v < 10f -> "${"%.1f".format(v)} g"
        else -> "${v.roundToInt()} g"
    }
    fun mg(v: Float?, unit: String = "mg") = when {
        v == null -> "–"
        v < 10f && v != 0f -> "${"%.1f".format(v)} $unit"
        else -> "${v.roundToInt()} $unit"
    }
    fun pct(v: Float?, ref: Float) = v?.let { DailyValues.percent(it, ref) }
    fun dvText(p: Int?) = p?.let { "$it%" } ?: "–"
    fun warnIfHigh(p: Int?) = if ((p ?: 0) >= DailyValues.HIGH_PERCENT) setOf(1) else emptySet()

    addRow(context.getString(R.string.nl_amount_per_serving), "", context.getString(R.string.nl_dv), header = true)
    addRow(context.getString(R.string.nl_calories), "${meal.calories} kcal", "")
    pct(meal.fat, dv.fat).let { addRow(context.getString(R.string.nl_total_fat), g(meal.fat), dvText(it), highlight = warnIfHigh(it), highlightColor = WARN) }
    pct(meal.satFat, dv.satFat).let { addRow("    " + context.getString(R.string.nl_sat_fat), g(meal.satFat), dvText(it), highlight = warnIfHigh(it), highlightColor = WARN) }
    pct(meal.sodiumMg, DailyValues.SODIUM_MG).let { addRow(context.getString(R.string.nl_sodium), mg(meal.sodiumMg), dvText(it), highlight = warnIfHigh(it), highlightColor = WARN) }
    addRow(context.getString(R.string.nl_total_carb), g(meal.carbs), dvText(pct(meal.carbs, dv.carbs)))
    addRow("    " + context.getString(R.string.nl_fiber), g(meal.fiber), dvText(pct(meal.fiber, dv.fiber)))
    addRow("    " + context.getString(R.string.nl_sugars), g(meal.sugar), "")
    addRow(context.getString(R.string.nl_protein), g(meal.protein), dvText(pct(meal.protein, dv.protein)))
    if (meal.ironMg != null) {
        addRow(context.getString(R.string.nl_vitamins), "", "", header = true)
        addRow(context.getString(R.string.nl_calcium), mg(meal.calciumMg), dvText(pct(meal.calciumMg, DailyValues.CALCIUM_MG)))
        addRow(context.getString(R.string.nl_iron), mg(meal.ironMg), dvText(pct(meal.ironMg, DailyValues.IRON_MG)))
        addRow(context.getString(R.string.nl_potassium), mg(meal.potassiumMg), dvText(pct(meal.potassiumMg, DailyValues.POTASSIUM_MG)))
        addRow(context.getString(R.string.nl_vit_c), mg(meal.vitaminCMg), dvText(pct(meal.vitaminCMg, DailyValues.VITAMIN_C_MG)))
        addRow(context.getString(R.string.nl_vit_b12), mg(meal.vitaminB12Ug, "µg"), dvText(pct(meal.vitaminB12Ug, DailyValues.VITAMIN_B12_UG)))
    }
}

/** Caption explaining the % DV column for [dv]. */
fun Context.dailyValueNote(dv: DailyValues.Personal): String =
    if (dv.calorieGoal == DailyValues.REFERENCE_KCAL) getString(R.string.dv_note_reference)
    else getString(R.string.dv_note_personal_fmt, dv.calorieGoal)
