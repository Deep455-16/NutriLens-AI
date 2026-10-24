package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.util.MealSlot
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InsightsTest {

    private lateinit var savedZone: TimeZone

    @Before fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After fun tearDown() = TimeZone.setDefault(savedZone)

    /** Oct [day], 2026 at [hour]:00. */
    private fun at(day: Int, hour: Int) = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, 0)
    }.timeInMillis

    private fun meal(day: Int, hour: Int, name: String, kcal: Int, protein: Float = 10f, carbs: Float = 30f, fat: Float = 10f, fiber: Float = 2f) =
        MealEntity(foodLabel = name.lowercase(), foodName = name, confidence = 0.9f, calories = kcal,
            protein = protein, carbs = carbs, fat = fat, fiber = fiber, servingGrams = 100, imageUri = null,
            timestamp = at(day, hour))

    /** Computed on use: a property initialiser would run before setUp() switches the time zone. */
    private val today get() = at(20, 0)

    @Test fun emptyHistory() {
        val i = InsightsCalculator.compute(emptyList(), today, 7, 2000)
        assertEquals(0, i.daysLogged)
        assertEquals(0, i.streak)
        assertEquals(0, i.avgCalories)
        assertNull(i.best)
        assertEquals(emptyList<Pair<String, Int>>(), i.topFoods)
    }

    @Test fun streakCountsBackFromTodayOrYesterday() {
        val meals = listOf(meal(20, 9, "A", 100), meal(19, 9, "A", 100), meal(18, 9, "A", 100), meal(16, 9, "A", 100))
        assertEquals(3, InsightsCalculator.streak(meals, today))
        // Nothing logged yet today: a streak running until yesterday still counts.
        assertEquals(2, InsightsCalculator.streak(meals.drop(1), today))
        assertEquals(0, InsightsCalculator.streak(listOf(meal(17, 9, "A", 100)), today))
    }

    @Test fun windowAveragesAndTargets() {
        val meals = listOf(
            meal(20, 8, "Omelette", 900), meal(20, 13, "Pizza", 1100),   // 2000: on target
            meal(19, 13, "Pizza", 1500),                                   // 1500: off target
            meal(18, 20, "Sushi", 1850),                                   // 1850: within 10%
            meal(10, 13, "Pizza", 5000)                                    // outside the 7-day window
        )
        val i = InsightsCalculator.compute(meals, today, 7, 2000)
        assertEquals(3, i.daysLogged)
        assertEquals((2000 + 1500 + 1850) / 3, i.avgCalories)
        assertEquals(2, i.daysOnTarget)
        assertEquals(listOf("pizza" to 2, "omelette" to 1, "sushi" to 1), i.topFoods)
        assertEquals(1, i.slotCounts[MealSlot.BREAKFAST])
        assertEquals(2, i.slotCounts[MealSlot.LUNCH])
        assertEquals(1, i.slotCounts[MealSlot.DINNER])
        assertEquals(0, i.slotCounts[MealSlot.SNACKS])
        // 30-day window includes the old 5000 kcal pizza.
        assertEquals(4, InsightsCalculator.compute(meals, today, 30, 2000).daysLogged)
    }

    @Test fun dailyTotalsIncludeEmptyDaysAndUnknownNutrients() {
        val meals = listOf(
            meal(20, 8, "Omelette", 900).copy(sodiumMg = 300f, sugar = 1f),
            meal(20, 13, "Pizza", 1100).copy(sodiumMg = 2100f, sugar = 8f),
            meal(18, 13, "Old", 500)                       // saved before sodium was tracked
        )
        val d = InsightsCalculator.dailyTotals(meals, today, 3)
        assertEquals(listOf(500, 0, 2000), d.map { it.calories })
        assertEquals(null, d[0].sodiumMg)
        assertEquals(2400f, d[2].sodiumMg!!, 0f)
        assertEquals(listOf(DayStatus.UNDER, DayStatus.NONE, DayStatus.ON_TARGET), d.map { InsightsCalculator.status(it, 2000) })
        assertEquals(DayStatus.OVER, InsightsCalculator.status(d[2], 1500))
    }

    @Test fun bestAndWorstByNutritionScore() {
        val lean = meal(20, 13, "Lean", 300, protein = 35f, carbs = 20f, fat = 9f, fiber = 8f)
        val greasy = meal(20, 19, "Greasy", 900, protein = 2f, carbs = 10f, fat = 90f, fiber = 0f)
        val i = InsightsCalculator.compute(listOf(greasy, lean), today, 7, 2000)
        assertEquals("Lean", i.best?.foodName)
        assertEquals("Greasy", i.worst?.foodName)
    }
}
