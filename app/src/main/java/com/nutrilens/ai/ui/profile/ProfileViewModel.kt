package com.nutrilens.ai.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.prefs.UserSettings
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.util.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    private val meals: MealRepository
) : ViewModel() {

    val settings: StateFlow<UserSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setName(value: String) { viewModelScope.launch { prefs.setName(value) } }
    fun setAge(value: Int) { viewModelScope.launch { prefs.setAge(value) } }
    fun setHeight(value: Int) { viewModelScope.launch { prefs.setHeight(value) } }
    fun setWeight(value: Int) { viewModelScope.launch { prefs.setWeight(value) } }
    fun setGoal(value: String) { viewModelScope.launch { prefs.setGoal(value) } }
    fun setCalorieGoal(value: Int) { viewModelScope.launch { prefs.setCalorieGoal(value) } }
    fun setDailyReminder(value: Boolean) { viewModelScope.launch { prefs.setDailyReminder(value) } }
    fun setNotifications(value: Boolean) { viewModelScope.launch { prefs.setNotifications(value) } }
    fun setDarkMode(value: Boolean) { viewModelScope.launch { prefs.setDarkMode(value) } }
    fun setMacroSplit(value: MacroSplit) { viewModelScope.launch { prefs.setMacroSplit(value) } }

    /** Suspends until saved: the caller navigates away right after, which clears this ViewModel. */
    suspend fun completeOnboarding(name: String, age: Int, heightCm: Int, weightKg: Int, goal: String, calorieGoal: Int) =
        prefs.completeOnboarding(name, age, heightCm, weightKg, goal, calorieGoal)

    fun clearAllMeals() {
        viewModelScope.launch {
            meals.clearAll()
            withContext(Dispatchers.IO) { ImageUtils.deleteAllMealImages(context) }
        }
    }
}
