package com.nutrilens.ai.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.toMeal
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.domain.DailyValues
import com.nutrilens.ai.domain.PortionChoice
import com.nutrilens.ai.util.MealSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val meals: MealRepository,
    private val nutrition: NutritionRepository,
    prefs: UserPreferences
) : ViewModel() {

    val dailyValues: StateFlow<DailyValues.Personal> = prefs.settings.map { DailyValues.personal(it.calorieGoal) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DailyValues.personal(DailyValues.REFERENCE_KCAL))

    suspend fun load(id: Long): MealEntity? = meals.getById(id)

    suspend fun delete(meal: MealEntity) = meals.delete(meal)

    /** Where the dataset's numbers for [label] come from (e.g. "USDA FNDDS: Samosa (75 g)"). */
    suspend fun sourceOf(label: String): String? = nutrition.find(label)?.source?.takeIf { it.isNotBlank() }

    /** The portion picker state for a saved meal, rebuilt from its stored grams. */
    suspend fun portionOf(meal: MealEntity): PortionChoice? {
        val food = nutrition.find(meal.foodLabel) ?: return null
        return PortionChoice.forSavedGrams(nutrition.portions(meal.foodLabel), food.servingGrams.toFloat(), meal.servingGrams.toFloat())
    }

    /** Names of the other items saved with this one as a single meal (e.g. a thali). */
    suspend fun mealMates(meal: MealEntity): List<String> =
        meal.groupId?.let { id -> meals.group(id).filter { it.id != meal.id }.map { it.foodName } }.orEmpty()

    /** Moves a saved meal to another date/time (e.g. it was logged later than eaten). */
    suspend fun setTime(meal: MealEntity, timestamp: Long): MealEntity =
        meal.copy(timestamp = timestamp).also { meals.update(it) }

    suspend fun setSlot(meal: MealEntity, slot: MealSlot): MealEntity =
        meal.copy(slot = slot.name).also { meals.update(it) }

    /**
     * Rebuilds a saved meal from the dataset with a new [portion] and stores it under the same
     * id, photo, time, slot and meal group. Returns the new meal and its portion.
     */
    suspend fun edit(meal: MealEntity, portion: PortionChoice): MealEntity? {
        val food = nutrition.find(meal.foodLabel) ?: return null
        val updated = food.toMeal(meal.confidence, meal.imageUri, meal.timestamp, portion.servings)
            .copy(id = meal.id, serving = portion.text, slot = meal.slot, groupId = meal.groupId)
        meals.update(updated)
        return updated
    }

    /** Swaps the food (a manual correction) at its standard serving. */
    suspend fun changeFood(meal: MealEntity, label: String): Pair<MealEntity, PortionChoice>? {
        val food = nutrition.find(label) ?: return null
        val portion = PortionChoice(nutrition.portions(label), food.servingGrams.toFloat())
        val updated = food.toMeal(MealEntity.MANUAL_CONFIDENCE, meal.imageUri, meal.timestamp, portion.servings)
            .copy(id = meal.id, serving = portion.text, slot = meal.slot, groupId = meal.groupId)
        meals.update(updated)
        return updated to portion
    }
}
