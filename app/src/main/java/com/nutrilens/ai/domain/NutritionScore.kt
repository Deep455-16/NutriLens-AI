package com.nutrilens.ai.domain

import androidx.annotation.StringRes
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One line of the score breakdown: a string resource, its optional argument, and the points. */
data class ScorePart(@StringRes val reason: Int, val arg: Any? = null, val points: Int)

/**
 * [headline] and [notes] are string resources (shown together as the score's explanation);
 * [parts] explain how the score was built, before clamping to 0..100.
 */
data class ScoreResult(
    val score: Int,
    @StringRes val headline: Int,
    val notes: List<Int>,
    val parts: List<ScorePart> = emptyList()
)

/**
 * A simple, transparent heuristic that rates how balanced a food is from its stored
 * nutrition values (protein share, fiber density, fat share, calorie load).
 * It is an informational estimate only and is NOT medical or dietary advice.
 */
object NutritionScore {

    /** HIGH_SUGAR_G: roughly the daily added-sugar guidance (50 g) at 40% in one serving. */
    private const val HIGH_SUGAR_G = 20f

    fun evaluate(
        calories: Int,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float,
        sodiumMg: Float? = null,
        sugar: Float? = null
    ): ScoreResult {
        if (calories <= 0) return ScoreResult(0, R.string.score_no_data, listOf(R.string.score_no_data_detail))

        val pCal = protein * 4f
        val cCal = carbs * 4f
        val fCal = fat * 9f
        val total = max(pCal + cCal + fCal, 1f)
        val pShare = pCal / total
        val fShare = fCal / total
        val fiberPer100Kcal = fiber * 100f / calories

        val proteinPts = (pShare - 0.15f) * 100f
        val fiberPts = min(fiberPer100Kcal, 2f) * 8f
        val fatPts = -max(0f, fShare - 0.35f) * 80f
        val kcalPts = -max(0f, calories - 700f) / 20f
        val s = 60f + proteinPts + fiberPts + fatPts + kcalPts
        val score = s.coerceIn(0f, 100f).roundToInt()
        val parts = listOf(
            ScorePart(R.string.part_start, null, 60),
            ScorePart(R.string.part_protein, (pShare * 100).roundToInt(), proteinPts.roundToInt()),
            ScorePart(R.string.part_fiber, "%.1f".format(fiberPer100Kcal), fiberPts.roundToInt()),
            ScorePart(R.string.part_fat, (fShare * 100).roundToInt(), fatPts.roundToInt()),
            ScorePart(R.string.part_kcal, null, kcalPts.roundToInt())
        )

        val headline = when {
            score >= 80 -> R.string.score_good
            score >= 60 -> R.string.score_fair
            else -> R.string.score_moderation
        }

        val notes = mutableListOf<Int>()
        val highProtein = pShare >= 0.25f
        val highFiber = fiberPer100Kcal >= 1f
        notes += when {
            highProtein && highFiber -> R.string.note_high_protein_fiber
            highProtein -> R.string.note_protein
            highFiber -> R.string.note_fiber
            else -> R.string.note_balanced
        }
        if (fShare > 0.45f) notes += R.string.note_higher_fat
        if (calories > 700) notes += R.string.note_higher_calorie
        if (sodiumMg != null && DailyValues.percent(sodiumMg, DailyValues.SODIUM_MG) >= DailyValues.HIGH_PERCENT) {
            notes += R.string.note_high_sodium
        }
        if (sugar != null && sugar >= HIGH_SUGAR_G) notes += R.string.note_high_sugar
        return ScoreResult(score, headline, notes, parts)
    }

    fun evaluate(meal: MealEntity) =
        evaluate(meal.calories, meal.protein, meal.carbs, meal.fat, meal.fiber, meal.sodiumMg, meal.sugar)

    fun evaluate(item: NutritionEntity) =
        evaluate(item.calories, item.protein, item.carbs, item.fat, item.fiber, item.sodiumMg, item.sugar)

    data class Tag(@StringRes val label: Int, val positive: Boolean)

    /**
     * Highlights for one serving, most useful first: good news (protein, fiber), then FDA "high"
     * warnings (20%+ of a Daily Value), else "Low calorie" or "Balanced". Never empty.
     */
    fun tags(item: NutritionEntity): List<Tag> {
        val macroKcal = max(item.protein * 4f + item.carbs * 4f + item.fat * 9f, 1f)
        val out = mutableListOf<Tag>()
        if (item.protein * 4f / macroKcal >= 0.25f) out += Tag(R.string.tag_high_protein, true)
        if (DailyValues.percent(item.fiber, DailyValues.FIBER_G) >= DailyValues.HIGH_PERCENT) out += Tag(R.string.tag_high_fiber, true)
        if (DailyValues.percent(item.satFat, DailyValues.SAT_FAT_G) >= DailyValues.HIGH_PERCENT) out += Tag(R.string.tag_high_sat_fat, false)
        if (DailyValues.percent(item.sodiumMg, DailyValues.SODIUM_MG) >= DailyValues.HIGH_PERCENT) out += Tag(R.string.tag_high_sodium, false)
        if (item.sugar >= HIGH_SUGAR_G) out += Tag(R.string.tag_high_sugar, false)
        if (out.isEmpty()) out += if (item.calories < 200) Tag(R.string.tag_low_calorie, true) else Tag(R.string.tag_balanced, true)
        return out
    }

    /** The single most useful tag (for one-line lists). */
    @StringRes
    fun tag(item: NutritionEntity): Int = tags(item).first().label

    /** Share of energy (kcal) from each macronutrient, as percentages that sum to ~100. */
    fun macroPercents(proteinKcal: Float, carbsKcal: Float, fatKcal: Float): Triple<Int, Int, Int> {
        val total = proteinKcal + carbsKcal + fatKcal
        if (total <= 0f) return Triple(0, 0, 0)
        val p = (proteinKcal / total * 100f).roundToInt()
        val c = (carbsKcal / total * 100f).roundToInt()
        val f = (100 - p - c).coerceAtLeast(0)
        return Triple(p, c, f)
    }
}
