package com.nutrilens.ai.util

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TimeUtilsTest {

    private lateinit var savedZone: TimeZone

    @Before fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // has DST
    }

    @After fun tearDown() = TimeZone.setDefault(savedZone)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test fun mealSlotBoundaries() {
        val cases = mapOf(
            3 to 59 to MealSlot.DINNER,
            4 to 0 to MealSlot.BREAKFAST,
            10 to 59 to MealSlot.BREAKFAST,
            11 to 0 to MealSlot.LUNCH,
            15 to 59 to MealSlot.LUNCH,
            16 to 0 to MealSlot.SNACKS,
            18 to 59 to MealSlot.SNACKS,
            19 to 0 to MealSlot.DINNER,
            23 to 59 to MealSlot.DINNER
        )
        for ((time, expected) in cases) {
            val ts = at(2026, Calendar.OCTOBER, 17, time.first, time.second)
            assertEquals("${time.first}:${time.second}", expected, TimeUtils.mealSlot(ts))
        }
    }

    @Test fun startOfDayIsMidnightAcrossDstChange() {
        // US clocks spring forward on 2026-03-08, so that day is 23 hours long.
        val noonBefore = at(2026, Calendar.MARCH, 7, 12)
        val dstDay = TimeUtils.startOfDayOffset(1, noonBefore)
        val dayAfter = TimeUtils.startOfDayOffset(2, noonBefore)
        assertEquals(at(2026, Calendar.MARCH, 8, 0), dstDay)
        assertEquals(at(2026, Calendar.MARCH, 9, 0), dayAfter)
        assertEquals(23L * 60 * 60 * 1000, dayAfter - dstDay)
    }

    @Test fun startOfDayOffsetGoesBackwards() {
        val now = at(2026, Calendar.OCTOBER, 17, 21, 30)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 0), TimeUtils.startOfDayOffset(-6, now))
    }

    @Test fun dayStartsFirstEmitsToday() = runBlocking {
        assertEquals(TimeUtils.startOfDayOffset(0), TimeUtils.dayStarts().first())
    }
}
