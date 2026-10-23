package com.nutrilens.ai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.util.TimeUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Single DataStore instance shared by the app and the reminder worker. */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nutrilens_prefs")

data class UserSettings(
    val name: String = "",
    val age: Int = 20,
    val heightCm: Int = 175,
    val weightKg: Int = 65,
    val goal: String = "Maintain Weight",
    val calorieGoal: Int = 2000,
    val dailyReminder: Boolean = true,
    val notifications: Boolean = true,
    val darkMode: Boolean = false,
    val macroSplit: MacroSplit = MacroSplit.BALANCED,
    val onboarded: Boolean = false
)

object PrefKeys {
    val NAME = stringPreferencesKey("name")
    val AGE = intPreferencesKey("age")
    val HEIGHT = intPreferencesKey("height_cm")
    val WEIGHT = intPreferencesKey("weight_kg")
    val GOAL = stringPreferencesKey("goal")
    val CALORIE_GOAL = intPreferencesKey("calorie_goal")
    val DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
    val NOTIFICATIONS = booleanPreferencesKey("notifications")
    val DARK_MODE = booleanPreferencesKey("dark_mode")
    val NOTIF_PERMISSION_ASKED = booleanPreferencesKey("notif_permission_asked")
    val MACRO_SPLIT = stringPreferencesKey("macro_split")
    val ONBOARDED = booleanPreferencesKey("onboarded")
}

fun Preferences.toSettings(): UserSettings {
    val d = UserSettings()
    return UserSettings(
        name = this[PrefKeys.NAME] ?: d.name,
        age = this[PrefKeys.AGE] ?: d.age,
        heightCm = this[PrefKeys.HEIGHT] ?: d.heightCm,
        weightKg = this[PrefKeys.WEIGHT] ?: d.weightKg,
        goal = this[PrefKeys.GOAL] ?: d.goal,
        calorieGoal = this[PrefKeys.CALORIE_GOAL] ?: d.calorieGoal,
        dailyReminder = this[PrefKeys.DAILY_REMINDER] ?: d.dailyReminder,
        notifications = this[PrefKeys.NOTIFICATIONS] ?: d.notifications,
        darkMode = this[PrefKeys.DARK_MODE] ?: d.darkMode,
        macroSplit = MacroSplit.fromName(this[PrefKeys.MACRO_SPLIT]),
        onboarded = this[PrefKeys.ONBOARDED] ?: d.onboarded
    )
}

/** Used by the background worker, which has no Hilt graph. */
suspend fun Context.readSettings(): UserSettings = dataStore.data.first().toSettings()

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val settings: Flow<UserSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    val notifPermissionAsked: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[PrefKeys.NOTIF_PERMISSION_ASKED] ?: false }

    suspend fun setName(value: String) { context.dataStore.edit { it[PrefKeys.NAME] = value } }
    suspend fun setAge(value: Int) { context.dataStore.edit { it[PrefKeys.AGE] = value } }
    suspend fun setHeight(value: Int) { context.dataStore.edit { it[PrefKeys.HEIGHT] = value } }
    suspend fun setWeight(value: Int) { context.dataStore.edit { it[PrefKeys.WEIGHT] = value } }
    suspend fun setGoal(value: String) { context.dataStore.edit { it[PrefKeys.GOAL] = value } }
    suspend fun setCalorieGoal(value: Int) { context.dataStore.edit { it[PrefKeys.CALORIE_GOAL] = value } }
    suspend fun setDailyReminder(value: Boolean) { context.dataStore.edit { it[PrefKeys.DAILY_REMINDER] = value } }
    suspend fun setNotifications(value: Boolean) { context.dataStore.edit { it[PrefKeys.NOTIFICATIONS] = value } }
    suspend fun setDarkMode(value: Boolean) { context.dataStore.edit { it[PrefKeys.DARK_MODE] = value } }
    suspend fun setMacroSplit(value: MacroSplit) { context.dataStore.edit { it[PrefKeys.MACRO_SPLIT] = value.name } }

    /** Saves the first-launch answers in one write and marks setup as done. */
    suspend fun completeOnboarding(name: String, age: Int, heightCm: Int, weightKg: Int, goal: String, calorieGoal: Int) {
        context.dataStore.edit {
            it[PrefKeys.NAME] = name
            it[PrefKeys.AGE] = age
            it[PrefKeys.HEIGHT] = heightCm
            it[PrefKeys.WEIGHT] = weightKg
            it[PrefKeys.GOAL] = goal
            it[PrefKeys.CALORIE_GOAL] = calorieGoal
            it[PrefKeys.ONBOARDED] = true
        }
    }

    suspend fun markNotifPermissionAsked() { context.dataStore.edit { it[PrefKeys.NOTIF_PERMISSION_ASKED] = true } }
}
