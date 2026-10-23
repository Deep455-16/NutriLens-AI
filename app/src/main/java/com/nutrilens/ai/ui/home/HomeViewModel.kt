package com.nutrilens.ai.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.prefs.UserSettings
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.data.repo.PersonalRepository
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.domain.MacroTargets
import com.nutrilens.ai.domain.NextMealSuggester
import com.nutrilens.ai.domain.NextMealSuggestion
import com.nutrilens.ai.domain.isIndianDish
import com.nutrilens.ai.util.DishPhotos
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val name: String = "",
    val calorieGoal: Int = 2000,
    val calories: Int = 0,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val targets: MacroTargets = MacroSplit.BALANCED.targets(2000),
    val dailyReminder: Boolean = true,
    /** Today's meals, shown grouped by slot. */
    val today: List<MealEntity> = emptyList(),
    /** Favourites first, then the most-logged foods: one tap to log again. */
    val quickAdd: List<QuickFood> = emptyList(),
    /** Foods that fit what is left of today's goals. */
    val nextMeal: List<NextMealSuggestion> = emptyList(),
    val featuredIndian: List<NutritionEntity> = emptyList()
)

data class QuickFood(val label: String, val favorite: Boolean)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    meals: MealRepository,
    personal: PersonalRepository,
    nutrition: NutritionRepository
) : ViewModel() {

    private val foods = flow { emit(nutrition.all()) }

    private val quickAdd = combine(personal.favorites(), personal.frequent()) { favs, frequent ->
        val favLabels = favs.map { it.label }
        (favLabels.map { QuickFood(it, true) } +
            frequent.map { it.label }.filter { it !in favLabels }.map { QuickFood(it, false) }).take(8)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> = combine(
        prefs.settings,
        TimeUtils.dayStarts().flatMapLatest { start ->
            meals.observeBetween(start, TimeUtils.startOfDayOffset(1, start) - 1)
        },
        meals.observeRecent(3)
    ) { settings, today, recent -> Base(settings, today, recent) }
        .combine(quickAdd) { b, quick -> b to quick }
        .combine(foods) { (b, quick), allFoods ->
            val settings = b.s
            val today = b.t
            val recent = b.r
            val targets = settings.macroSplit.targets(settings.calorieGoal)
            val eatenKcal = today.sumOf { it.calories }
            val eatenProtein = today.sumOf { it.protein.toDouble() }.toFloat()
            val indian = mostlyIndian(today + recent)
            HomeUiState(
                name = settings.name,
                calorieGoal = settings.calorieGoal,
                calories = today.sumOf { it.calories },
                protein = today.sumOf { it.protein.toDouble() }.toFloat(),
                carbs = today.sumOf { it.carbs.toDouble() }.toFloat(),
                fat = today.sumOf { it.fat.toDouble() }.toFloat(),
                targets = targets,
                dailyReminder = settings.dailyReminder,
                today = today,
                quickAdd = quick,
                nextMeal = NextMealSuggester.suggest(
                    allFoods, settings.calorieGoal - eatenKcal, targets.protein - eatenProtein,
                    // The cuisine the user actually eats (Indian until the log says otherwise), and
                    // dishes with a photo, when enough of those fit.
                    prefer = { f ->
                        (!indian || isIndianDish(f.label)) && DishPhotos.has(context, f.label)
                    }
                ),
                featuredIndian = allFoods.filter { isIndianDish(it.label) }
                    .sortedByDescending { if (it.label in FEATURED_INDIAN_HEROES) 1 else 0 }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    companion object {
        private val FEATURED_INDIAN_HEROES = setOf(
            "biryani", "dal_makhani", "paneer_tikka", "masala_dosa",
            "butter_chicken", "chole_bhature", "palak_paneer", "tandoori_chicken",
            "sambar", "idli", "rajma_chawal", "pav_bhaji"
        )
    }

    fun setDailyReminder(on: Boolean) { viewModelScope.launch { prefs.setDailyReminder(on) } }

    /** True when at least half of these meals are Indian, or there are none yet. */
    private fun mostlyIndian(meals: List<MealEntity>): Boolean {
        val distinct = meals.distinctBy { it.id }
        return distinct.isEmpty() || distinct.count { isIndianDish(it.foodLabel) } * 2 >= distinct.size
    }

    private data class Base(val s: UserSettings, val t: List<MealEntity>, val r: List<MealEntity>)
}
