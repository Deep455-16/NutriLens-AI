package com.nutrilens.ai.util

import android.text.format.DateFormat
import androidx.fragment.app.Fragment
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.TimeZone

/**
 * Material date picker, then time picker; calls [onPicked] with the chosen local time.
 * Future dates are disabled, and a time later than now on today's date is clamped to now.
 */
fun Fragment.pickMealTime(initial: Long, onPicked: (Long) -> Unit) {
    val local = Calendar.getInstance().apply { timeInMillis = initial }
    // MaterialDatePicker works in UTC midnights: hand it the same calendar date in UTC.
    val utcSelection = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis

    val datePicker = MaterialDatePicker.Builder.datePicker()
        .setTitleText(com.nutrilens.ai.R.string.when_eaten)
        .setSelection(utcSelection)
        .setCalendarConstraints(
            CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build()
        )
        .build()

    datePicker.addOnPositiveButtonClickListener { utcMidnight ->
        val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        val timePicker = MaterialTimePicker.Builder()
            .setTitleText(com.nutrilens.ai.R.string.what_time)
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(local.get(Calendar.HOUR_OF_DAY))
            .setMinute(local.get(Calendar.MINUTE))
            .build()
        timePicker.addOnPositiveButtonClickListener {
            val picked = Calendar.getInstance().apply {
                clear()
                set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH),
                    timePicker.hour, timePicker.minute)
            }.timeInMillis
            onPicked(minOf(picked, System.currentTimeMillis()))
        }
        timePicker.show(childFragmentManager, "meal_time")
    }
    datePicker.show(childFragmentManager, "meal_date")
}
