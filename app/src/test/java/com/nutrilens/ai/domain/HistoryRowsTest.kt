package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRowsTest {

    private val day = 86_400_000L

    /** Days are plain 24 h buckets here, so the test does not depend on the time zone. */
    private val dayOf: (Long) -> Long = { it / day * day }

    private fun meal(id: Long, ts: Long, kcal: Int) = MealEntity(
        id = id, foodLabel = "x", foodName = "X", confidence = 0.9f, calories = kcal, protein = 1f, carbs = 1f,
        fat = 1f, fiber = 0f, servingGrams = 100, imageUri = null, timestamp = ts
    )

    @Test fun headerBeforeEachDayWithThatDaysTotals() {
        val meals = listOf(meal(1, 2 * day + 50, 300), meal(2, 2 * day + 10, 200), meal(3, day + 5, 500))
        val rows = meals.groupedByDay(dayOf)
        assertEquals(
            listOf(
                HistoryRow.Day(2 * day, 2, 500), HistoryRow.Meal(meals[0]), HistoryRow.Meal(meals[1]),
                HistoryRow.Day(day, 1, 500), HistoryRow.Meal(meals[2])
            ),
            rows
        )
    }

    @Test fun keepsOldestFirstOrder() {
        val meals = listOf(meal(3, day + 5, 500), meal(2, 2 * day + 10, 200))
        assertEquals(listOf(day, 2 * day), meals.groupedByDay(dayOf).filterIsInstance<HistoryRow.Day>().map { it.dayStart })
    }

    @Test fun emptyListHasNoHeaders() {
        assertEquals(emptyList<HistoryRow>(), emptyList<MealEntity>().groupedByDay(dayOf))
    }
}
