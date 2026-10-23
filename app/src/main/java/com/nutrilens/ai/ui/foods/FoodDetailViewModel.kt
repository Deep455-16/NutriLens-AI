package com.nutrilens.ai.ui.foods

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.toMeal
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.data.repo.PersonalRepository
import com.nutrilens.ai.domain.DailyValues
import com.nutrilens.ai.domain.PortionChoice
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FoodDetailState(
    val food: NutritionEntity? = null,
    val portion: PortionChoice? = null,
    /** When it was eaten; null means "now" at the moment of logging. */
    val eatenAt: Long? = null,
    /** Chosen slot; null = from the time eaten. */
    val slot: MealSlot? = null,
    val compareWith: NutritionEntity? = null,
    val cookingStyle: com.nutrilens.ai.domain.CookingStyle = com.nutrilens.ai.domain.CookingStyle.STANDARD
) {
    val effectiveSlot: MealSlot get() = slot ?: TimeUtils.mealSlot(eatenAt ?: System.currentTimeMillis())

    /** The meal that would be logged, already scaled to the chosen portion. */
    val preview: MealEntity?
        get() {
            val f = food ?: return null
            val p = portion ?: return null
            val base = cookingStyle.applyTo(f.toMeal(MealEntity.MANUAL_CONFIDENCE, null, eatenAt ?: System.currentTimeMillis(), p.servings))
            return base.copy(
                serving = p.text,
                slot = effectiveSlot.name
            )
        }
}

@HiltViewModel
class FoodDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val nutrition: NutritionRepository,
    private val meals: MealRepository,
    private val personal: PersonalRepository,
    prefs: UserPreferences
) : ViewModel() {

    private val label = savedState.get<String>("label").orEmpty()

    private val _state = MutableStateFlow(FoodDetailState(
        // Opened from a Home slot's "+": start in that slot.
        slot = savedState.get<String>("slot")?.let { runCatching { MealSlot.valueOf(it) }.getOrNull() }
    ))
    val state: StateFlow<FoodDetailState> = _state.asStateFlow()

    val isFavorite: StateFlow<Boolean> = personal.isFavorite(label)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val dailyValues: StateFlow<DailyValues.Personal> = prefs.settings.map { DailyValues.personal(it.calorieGoal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyValues.personal(DailyValues.REFERENCE_KCAL))

    init {
        viewModelScope.launch {
            val food = nutrition.find(label) ?: return@launch
            _state.update { it.copy(food = food, portion = PortionChoice(nutrition.portions(label), food.servingGrams.toFloat())) }
        }
    }

    fun choosePortion(index: Int) = _state.update { it.copy(portion = it.portion?.select(index)) }
    fun stepQuantity(up: Boolean) = _state.update { it.copy(portion = it.portion?.step(up)) }
    fun setGrams(grams: Float) = _state.update { it.copy(portion = it.portion?.withGrams(grams)) }
    fun setSlot(slot: MealSlot) = _state.update { it.copy(slot = slot) }
    fun setEatenAt(time: Long) = _state.update { it.copy(eatenAt = time) }
    fun setCookingStyle(style: com.nutrilens.ai.domain.CookingStyle) = _state.update { it.copy(cookingStyle = style) }

    fun toggleFavorite() = viewModelScope.launch { personal.setFavorite(label, !isFavorite.value) }

    fun compareWith(label: String) {
        viewModelScope.launch { _state.update { it.copy(compareWith = nutrition.find(label)) } }
    }

    /** Saves the meal (no photo, marked as a manual choice). Returns its id, or null if nothing to log. */
    suspend fun log(): Long? {
        val meal = _state.value.preview ?: return null
        return meals.insert(meal)
    }
}
