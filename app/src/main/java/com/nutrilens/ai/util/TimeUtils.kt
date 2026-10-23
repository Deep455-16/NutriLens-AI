package com.nutrilens.ai.util

import androidx.annotation.StringRes
import com.nutrilens.ai.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class MealSlot(@StringRes val label: Int) {
    BREAKFAST(R.string.breakfast),
    LUNCH(R.string.lunch),
    SNACKS(R.string.snacks),
    DINNER(R.string.dinner)
}

object TimeUtils {

    /** Midnight at the start of the day that is [days] away from [from] (negative = past). */
    fun startOfDayOffset(days: Int = 0, from: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

    /**
     * Emits the start of today now, then again just after each local midnight, so screens
     * showing "today" roll over without the app being restarted. The delay pauses during deep
     * sleep, but screens collect this only while visible (WhileSubscribed), and collection
     * restarts with a fresh value when they come back.
     */
    fun dayStarts(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(startOfDayOffset(0, now))
            delay(startOfDayOffset(1, now) - now + 1_000)
        }
    }

    private fun fmt(pattern: String, ts: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))

    fun timeText(ts: Long) = fmt("h:mm a", ts)
    fun dateText(ts: Long) = fmt("d MMM yyyy", ts)
    fun dateTimeText(ts: Long) = fmt("h:mm a, d MMM yyyy", ts)
    fun dayShort(ts: Long) = fmt("EEE", ts)

    fun rangeText(start: Long, end: Long) = "${fmt("MMM d", start)} – ${fmt("MMM d, yyyy", end)}"

    @StringRes
    fun greeting(now: Long = System.currentTimeMillis()): Int {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> R.string.greeting_morning
            hour < 17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        }
    }

    /** Meal slot is derived from the time the meal was logged. */
    fun mealSlot(ts: Long): MealSlot {
        val hour = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 4..10 -> MealSlot.BREAKFAST
            in 11..15 -> MealSlot.LUNCH
            in 16..18 -> MealSlot.SNACKS
            else -> MealSlot.DINNER
        }
    }
}
