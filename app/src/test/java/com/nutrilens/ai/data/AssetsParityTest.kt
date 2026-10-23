package com.nutrilens.ai.data

import com.nutrilens.ai.data.repo.NutritionCsv
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled data: every model label must have exactly one sane nutrition row,
 * parsed by the same code the app uses.
 */
class AssetsParityTest {

    private val assets = File("src/main/assets")
    private val labels = File(assets, "labels.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    private val rows = File(assets, "nutrition.csv").useLines { NutritionCsv.parse(it) }

    @Test fun labelsAreUnique() {
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun everyLabelHasExactlyOneRow() {
        val csvLabels = rows.map { it.label }
        assertEquals("duplicate CSV labels", csvLabels.size, csvLabels.toSet().size)
        assertEquals(labels.toSet(), csvLabels.toSet())
    }

    @Test fun valuesArePlausible() {
        for (r in rows) {
            assertTrue("${r.label} grams", r.servingGrams > 0)
            assertTrue("${r.label} kcal", r.calories > 0)
            assertTrue("${r.label} nutrients", listOf(r.protein, r.carbs, r.fat, r.fiber, r.sugar, r.sodiumMg, r.satFat).all { it >= 0f })
            assertTrue("${r.label} sat fat <= fat", r.satFat <= r.fat + 0.1f)
            assertTrue("${r.label} sugar <= carbs", r.sugar <= r.carbs + 0.1f)
            assertTrue("${r.label} serving", r.serving.isNotBlank())
            assertTrue("${r.label} source", r.source.isNotBlank())
        }
    }

    @Test fun everyFoodHasPortionsStartingWithItsServing() {
        val portions = File(assets, "portions.csv").useLines { NutritionCsv.parsePortions(it) }.groupBy { it.label }
        for (r in rows) {
            val list = portions[r.label]
            assertTrue("${r.label} has portions", !list.isNullOrEmpty())
            assertEquals("${r.label} first portion", r.serving, list!!.first().description)
            assertTrue("${r.label} grams", list.all { it.grams > 0f })
        }
    }

    @Test fun caloriesMatchMacrosWithin15Percent() {
        for (r in rows) {
            val fromMacros = 4 * r.protein + 4 * r.carbs + 9 * r.fat
            assertTrue("${r.label}: ${r.calories} kcal vs $fromMacros", abs(fromMacros - r.calories) / r.calories <= 0.15f)
        }
    }
}
