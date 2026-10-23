package com.nutrilens.ai.ui.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.R
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.domain.DayTotal
import com.nutrilens.ai.domain.Insights
import com.nutrilens.ai.domain.InsightsCalculator
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.domain.MacroTargets
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the bar chart shows per day; [value] is null for a day with no data for it. */
enum class Metric(@StringRes val label: Int, val unit: String, val value: (DayTotal) -> Float?) {
    CALORIES(R.string.calories, "kcal", { d -> d.calories.toFloat().takeIf { d.logged } }),
    PROTEIN(R.string.protein, "g", { d -> d.protein.takeIf { d.logged } }),
    CARBS(R.string.carbs, "g", { d -> d.carbs.takeIf { d.logged } }),
    FAT(R.string.fat, "g", { d -> d.fat.takeIf { d.logged } }),
    SODIUM(R.string.sodium, "mg", { d -> d.sodiumMg }),
    SUGAR(R.string.sugar, "g", { d -> d.sugar })
}

data class DashboardUiState(
    val period: Int = 7,
    val metric: Metric = Metric.CALORIES,
    val rangeText: String = "",
    /** Per-day totals for the period, oldest first (bar chart). */
    val days: List<DayTotal> = emptyList(),
    /** Per-day totals for the last 35 days, ending today (calendar). */
    val calendar: List<DayTotal> = emptyList(),
    /** null until the meals have loaded. */
    val insights: Insights? = null,
    val proteinKcal: Float = 0f,
    val carbsKcal: Float = 0f,
    val fatKcal: Float = 0f,
    val mealCount: Int = 0,
    val calorieGoal: Int = 2000,
    val targets: MacroTargets = MacroSplit.BALANCED.targets(2000)
)

/** Progress over the last 7 or 30 days: chart, macro split, streak, calendar and patterns. */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    meals: MealRepository,
    prefs: UserPreferences
) : ViewModel() {

    private val period = MutableStateFlow(7)
    private val metric = MutableStateFlow(Metric.CALORIES)

    val state: StateFlow<DashboardUiState> = combine(
        meals.observeAll(), prefs.settings, period, metric, TimeUtils.dayStarts()
    ) { all, settings, days, selected, today ->
        val from = TimeUtils.startOfDayOffset(-(days - 1), today)
        val until = TimeUtils.startOfDayOffset(1, today)
        val inPeriod = all.filter { it.timestamp in from until until }
        DashboardUiState(
            period = days,
            metric = selected,
            rangeText = TimeUtils.rangeText(from, until - 1),
            days = InsightsCalculator.dailyTotals(all, today, days),
            calendar = InsightsCalculator.dailyTotals(all, today, 35),
            insights = InsightsCalculator.compute(all, today, days, settings.calorieGoal),
            proteinKcal = inPeriod.sumOf { it.protein.toDouble() }.toFloat() * 4f,
            carbsKcal = inPeriod.sumOf { it.carbs.toDouble() }.toFloat() * 4f,
            fatKcal = inPeriod.sumOf { it.fat.toDouble() }.toFloat() * 9f,
            mealCount = inPeriod.size,
            calorieGoal = settings.calorieGoal,
            targets = settings.macroSplit.targets(settings.calorieGoal)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun setMetric(value: Metric) { metric.value = value }
    fun setPeriod(days: Int) { period.value = days }
}
