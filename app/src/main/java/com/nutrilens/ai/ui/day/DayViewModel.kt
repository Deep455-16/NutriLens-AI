package com.nutrilens.ai.ui.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.domain.MacroTargets
import com.nutrilens.ai.domain.mealSlot
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DayUiState(
    val dayStart: Long,
    val isToday: Boolean,
    val calorieGoal: Int,
    val targets: MacroTargets,
    val meals: List<MealEntity>
) {
    val calories get() = meals.sumOf { it.calories }
    val protein get() = meals.sumOf { it.protein.toDouble() }.toFloat()
    val carbs get() = meals.sumOf { it.carbs.toDouble() }.toFloat()
    val fat get() = meals.sumOf { it.fat.toDouble() }.toFloat()
    val fiber get() = meals.sumOf { it.fiber.toDouble() }.toFloat()

    /** Meals grouped by slot in Breakfast → Dinner order, oldest first within a slot. */
    val bySlot: Map<MealSlot, List<MealEntity>>
        get() = MealSlot.entries.associateWith { slot ->
            meals.filter { it.mealSlot == slot }.sortedBy { it.timestamp }
        }.filterValues { it.isNotEmpty() }
}

@HiltViewModel
class DayViewModel @Inject constructor(
    savedState: SavedStateHandle,
    prefs: UserPreferences,
    meals: MealRepository
) : ViewModel() {

    private val day = MutableStateFlow(
        TimeUtils.startOfDayOffset(0, savedState.get<Long>("dayStart")?.takeIf { it > 0 } ?: System.currentTimeMillis())
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DayUiState?> = combine(
        day.flatMapLatest { start ->
            meals.observeBetween(start, TimeUtils.startOfDayOffset(1, start) - 1).map { start to it }
        },
        prefs.settings
    ) { (start, list), settings ->
        DayUiState(
            dayStart = start,
            isToday = start == TimeUtils.startOfDayOffset(0),
            calorieGoal = settings.calorieGoal,
            targets = settings.macroSplit.targets(settings.calorieGoal),
            meals = list
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Moves one day back or forward; never past today. */
    fun shift(days: Int) {
        val next = TimeUtils.startOfDayOffset(days, day.value)
        if (next <= TimeUtils.startOfDayOffset(0)) day.value = next
    }
}
