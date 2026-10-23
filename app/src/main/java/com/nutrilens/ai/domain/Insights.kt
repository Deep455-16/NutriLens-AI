package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the Dashboard shows for the last [days] days (today included). */
data class Insights(
    val days: Int,
    val daysLogged: Int,
    /** Consecutive days with at least one meal, ending today (or yesterday if today is empty). */
    val streak: Int,
    /** Averages are per day that has meals, so empty days don't drag them down. */
    val avgCalories: Int,
    val avgProtein: Int,
    /** Logged days whose total kcal is within ±10% of the goal. */
    val daysOnTarget: Int,
    val avgScore: Int,
    /** (model label, times eaten), most first. */
    val topFoods: List<Pair<String, Int>>,
    val best: MealEntity?,
    val worst: MealEntity?,
    val slotCounts: Map<MealSlot, Int>
)

/** One calendar day's totals; nutrients known only for meals saved with them (else null). */
data class DayTotal(
    val dayStart: Long,
    val meals: Int,
    val calories: Int,
    val protein: Float,
    val sodiumMg: Float?,
    val sugar: Float?,
    val carbs: Float = 0f,
    val fat: Float = 0f
) {
    val logged get() = meals > 0
}

enum class DayStatus { NONE, UNDER, ON_TARGET, OVER }

object InsightsCalculator {

    /** Totals for each of the [days] days ending on [todayStart], oldest first (empty days included). */
    fun dailyTotals(allMeals: List<MealEntity>, todayStart: Long, days: Int): List<DayTotal> {
        val byDay = allMeals.groupBy { TimeUtils.startOfDayOffset(0, it.timestamp) }
        return (days - 1 downTo 0).map { back ->
            val day = TimeUtils.startOfDayOffset(-back, todayStart)
            val m = byDay[day].orEmpty()
            DayTotal(
                dayStart = day,
                meals = m.size,
                calories = m.sumOf { it.calories },
                protein = m.sumOf { it.protein.toDouble() }.toFloat(),
                sodiumMg = m.mapNotNull { it.sodiumMg }.takeIf { it.isNotEmpty() }?.sum(),
                sugar = m.mapNotNull { it.sugar }.takeIf { it.isNotEmpty() }?.sum(),
                carbs = m.sumOf { it.carbs.toDouble() }.toFloat(),
                fat = m.sumOf { it.fat.toDouble() }.toFloat()
            )
        }
    }

    /** Calendar colour for a day: within ±10% of the goal is on target. */
    fun status(day: DayTotal, calorieGoal: Int): DayStatus = when {
        !day.logged -> DayStatus.NONE
        calorieGoal > 0 && abs(day.calories - calorieGoal) <= calorieGoal * ON_TARGET_TOLERANCE -> DayStatus.ON_TARGET
        day.calories < calorieGoal -> DayStatus.UNDER
        else -> DayStatus.OVER
    }


    const val ON_TARGET_TOLERANCE = 0.10f

    /**
     * [allMeals] can span any period: the streak looks back as far as needed, everything else
     * uses only the window of [days] days ending on [todayStart].
     */
    fun compute(allMeals: List<MealEntity>, todayStart: Long, days: Int, calorieGoal: Int): Insights {
        val windowStart = TimeUtils.startOfDayOffset(-(days - 1), todayStart)
        val windowEnd = TimeUtils.startOfDayOffset(1, todayStart)
        val meals = allMeals.filter { it.timestamp in windowStart until windowEnd }
        val byDay = meals.groupBy { TimeUtils.startOfDayOffset(0, it.timestamp) }

        val dayTotals = byDay.values.map { list -> list.sumOf { it.calories } }
        val daysOnTarget = dayTotals.count { calorieGoal > 0 && abs(it - calorieGoal) <= calorieGoal * ON_TARGET_TOLERANCE }
        val scored = meals.map { it to NutritionScore.evaluate(it).score }

        return Insights(
            days = days,
            daysLogged = byDay.size,
            streak = streak(allMeals, todayStart),
            avgCalories = if (byDay.isEmpty()) 0 else (dayTotals.sum().toFloat() / byDay.size).roundToInt(),
            avgProtein = if (byDay.isEmpty()) 0 else (meals.sumOf { it.protein.toDouble() } / byDay.size).roundToInt(),
            daysOnTarget = daysOnTarget,
            avgScore = if (scored.isEmpty()) 0 else scored.map { it.second }.average().roundToInt(),
            topFoods = meals.groupingBy { it.foodLabel }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { it.key to it.value },
            best = scored.maxWithOrNull(compareBy<Pair<MealEntity, Int>> { it.second }.thenBy { it.first.timestamp })?.first,
            worst = scored.minWithOrNull(compareBy<Pair<MealEntity, Int>> { it.second }.thenByDescending { it.first.timestamp })?.first,
            slotCounts = MealSlot.entries.associateWith { slot -> meals.count { it.mealSlot == slot } }
        )
    }

    fun streak(allMeals: List<MealEntity>, todayStart: Long): Int {
        val loggedDays = allMeals.map { TimeUtils.startOfDayOffset(0, it.timestamp) }.toSet()
        var day = if (todayStart in loggedDays) todayStart else TimeUtils.startOfDayOffset(-1, todayStart)
        var count = 0
        while (day in loggedDays) {
            count++
            day = TimeUtils.startOfDayOffset(-1, day)
        }
        return count
    }
}
