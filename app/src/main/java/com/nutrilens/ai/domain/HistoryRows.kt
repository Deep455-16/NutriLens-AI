package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.util.TimeUtils

/** One row of the History list: a day header (with that day's totals) or a meal. */
sealed interface HistoryRow {
    data class Day(val dayStart: Long, val meals: Int, val calories: Int) : HistoryRow
    data class Meal(val meal: MealEntity) : HistoryRow
}

/**
 * Inserts a header before each day's meals, keeping the list's order (newest or oldest first).
 * The totals count the meals shown, so they follow the search and slot filter.
 */
fun List<MealEntity>.groupedByDay(dayOf: (Long) -> Long = { TimeUtils.startOfDayOffset(0, it) }): List<HistoryRow> {
    val rows = ArrayList<HistoryRow>(size + 8)
    var i = 0
    while (i < size) {
        val day = dayOf(this[i].timestamp)
        var j = i
        while (j < size && dayOf(this[j].timestamp) == day) j++
        val meals = subList(i, j)
        rows += HistoryRow.Day(day, meals.size, meals.sumOf { it.calories })
        meals.mapTo(rows) { HistoryRow.Meal(it) }
        i = j
    }
    return rows
}
