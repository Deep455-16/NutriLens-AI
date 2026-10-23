package com.nutrilens.ai.ui.foods

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.domain.FoodFilter
import com.nutrilens.ai.domain.FoodSort
import com.nutrilens.ai.domain.browse
import com.nutrilens.ai.util.DishPhotos
import com.nutrilens.ai.util.foodName
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FoodLibraryViewModel @Inject constructor(
    nutrition: NutritionRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val foods = flow { emit(nutrition.all()) }
    private val query = MutableStateFlow("")
    val filter = MutableStateFlow(FoodFilter.ALL)
    val sort = MutableStateFlow(FoodSort.FEATURED)

    /** null while the dataset is loading. */
    val items: StateFlow<List<NutritionEntity>?> = combine(foods, query, filter, sort) { all, q, f, s ->
        all.browse(q, f, s, name = { context.foodName(it) }, hasPhoto = { DishPhotos.has(context, it) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) { query.value = value }
}
