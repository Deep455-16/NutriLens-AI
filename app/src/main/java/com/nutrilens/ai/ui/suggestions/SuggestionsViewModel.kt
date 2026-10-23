package com.nutrilens.ai.ui.suggestions

import androidx.lifecycle.ViewModel
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.toMeal
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    private val nutrition: NutritionRepository,
    private val meals: MealRepository
) : ViewModel() {

    suspend fun current(label: String): NutritionEntity? = nutrition.find(label)

    suspend fun alternatives(label: String): List<NutritionEntity> = nutrition.alternativesFor(label)

    /** Logs a suggested food as a meal (no photo, manual selection). */
    suspend fun log(item: NutritionEntity): Long =
        meals.insert(item.toMeal(confidence = MealEntity.MANUAL_CONFIDENCE, imageUri = null, timestamp = System.currentTimeMillis()))
}
