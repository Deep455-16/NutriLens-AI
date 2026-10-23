package com.nutrilens.ai.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.domain.mealSlot
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: MealRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val slot = MutableStateFlow<MealSlot?>(null)
    private val newestFirst = MutableStateFlow(true)

    val meals: StateFlow<List<MealEntity>> = combine(
        repository.observeAll(), query, slot, newestFirst
    ) { all, q, s, newest ->
        val filtered = all.filter { meal ->
            (q.isBlank() || meal.foodName.contains(q.trim(), ignoreCase = true) || meal.foodLabel.contains(q.trim(), ignoreCase = true)) &&
                (s == null || meal.mealSlot == s)
        }
        if (newest) filtered else filtered.reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { query.value = value }

    fun setSlot(value: MealSlot?) { slot.value = value }

    /** Returns true when the list is now sorted newest-first. */
    fun toggleSort(): Boolean {
        newestFirst.value = !newestFirst.value
        return newestFirst.value
    }

    fun remove(meal: MealEntity) {
        viewModelScope.launch { repository.deleteKeepingPhoto(meal) }
    }

    fun restore(meal: MealEntity) {
        viewModelScope.launch { repository.restore(meal) }
    }

    /** The undo window has passed: the photo can go too. */
    fun purgePhoto(meal: MealEntity) = repository.purgePhoto(meal)
}
