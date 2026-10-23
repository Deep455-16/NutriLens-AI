package com.nutrilens.ai.domain

import com.nutrilens.ai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionScoreTest {

    @Test fun zeroCaloriesMeansNoData() {
        val r = NutritionScore.evaluate(0, 0f, 0f, 0f, 0f)
        assertEquals(0, r.score)
        assertEquals(R.string.score_no_data, r.headline)
    }

    @Test fun pureFatScoresLow() {
        assertTrue(NutritionScore.evaluate(900, 0f, 0f, 100f, 0f).score <= 20)
    }

    @Test fun highProteinHighFiberScoresHigh() {
        assertTrue(NutritionScore.evaluate(300, 35f, 20f, 9f, 8f).score >= 80)
    }

    @Test fun scoreIsAlwaysWithin0To100() {
        for (cal in listOf(1, 50, 300, 700, 2000, 5000))
            for (p in listOf(0f, 10f, 80f))
                for (c in listOf(0f, 40f, 300f))
                    for (f in listOf(0f, 15f, 120f))
                        for (fiber in listOf(0f, 5f, 60f)) {
                            val s = NutritionScore.evaluate(cal, p, c, f, fiber).score
                            assertTrue("cal=$cal p=$p c=$c f=$f fiber=$fiber -> $s", s in 0..100)
                        }
    }

    @Test fun flagsHighSodiumAndSugarWithoutChangingTheScore() {
        val plain = NutritionScore.evaluate(458, 19.6f, 57.3f, 16.7f, 4f)
        val salty = NutritionScore.evaluate(458, 19.6f, 57.3f, 16.7f, 4f, sodiumMg = 929f, sugar = 6.2f)
        assertEquals(plain.score, salty.score)
        assertTrue(R.string.note_high_sodium in salty.notes)
        assertTrue(R.string.note_high_sugar !in salty.notes)
        assertTrue(R.string.note_high_sugar in NutritionScore.evaluate(604, 5.8f, 91.4f, 26.6f, 2.8f, 516f, 66f).notes)
        // 400 mg is 17% DV: not "high".
        assertTrue(R.string.note_high_sodium !in NutritionScore.evaluate(300, 10f, 40f, 10f, 2f, 400f, 5f).notes)
    }

    @Test fun tagsFollowDailyValues() {
        fun food(kcal: Int, p: Float, c: Float, f: Float, fiber: Float = 1f, sugar: Float = 2f, na: Float = 100f, sat: Float = 1f) =
            com.nutrilens.ai.data.local.NutritionEntity("x", 100, kcal, p, c, f, fiber, sugar, na, sat)
        // Caesar salad (USDA): 4.0 g sat fat (20% DV) and 551 mg sodium (24% DV) -> warnings, not "Balanced".
        assertEquals(listOf(R.string.tag_high_sat_fat, R.string.tag_high_sodium), NutritionScore.tags(food(223, 3.8f, 6.9f, 20f, na = 551f, sat = 4f)).map { it.label })
        // 3.0 g sat fat is 15% DV: not high.
        assertEquals(listOf(R.string.tag_high_sodium), NutritionScore.tags(food(223, 3.8f, 6.9f, 20f, na = 551f, sat = 3f)).map { it.label })
        // Grilled salmon: protein first, its 5.3 g saturated fat still reported.
        assertEquals(listOf(R.string.tag_high_protein, R.string.tag_high_sat_fat), NutritionScore.tags(food(363, 36.3f, 0f, 23.1f, fiber = 0f, sat = 5.3f, na = 403f)).map { it.label })
        assertEquals(R.string.tag_low_calorie, NutritionScore.tag(food(97, 4.8f, 19f, 0.3f, fiber = 4.4f, na = 192f, sat = 0f)))
        assertEquals(R.string.tag_balanced, NutritionScore.tag(food(300, 8f, 45f, 9f, fiber = 3f, na = 300f, sat = 2f)))
        assertTrue(NutritionScore.tags(food(604, 5.8f, 91f, 26.6f, sugar = 66f, sat = 7.2f, na = 516f)).none { it.positive })
    }

    @Test fun partsAddUpToTheUnclampedScore() {
        val r = NutritionScore.evaluate(458, 19.6f, 57.3f, 16.7f, 4f)
        assertEquals(r.score, r.parts.sumOf { it.points }.coerceIn(0, 100))
        assertEquals(ScorePart(R.string.part_start, null, 60), r.parts.first())
    }

    @Test fun personalDailyValuesScaleOnlyEnergyLinkedNutrients() {
        val p = DailyValues.personal(1500)
        assertEquals(58.5f, p.fat, 0.01f)
        assertEquals(37.5f, p.protein, 0.01f)
        assertEquals(DailyValues.FAT_G, DailyValues.personal(2000).fat, 0f)
    }

    @Test fun macroPercentsSumTo100() {
        val (p, c, f) = NutritionScore.macroPercents(100f, 200f, 90f)
        assertEquals(100, p + c + f)
        assertEquals(Triple(0, 0, 0), NutritionScore.macroPercents(0f, 0f, 0f))
    }
}
