package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils

/** The slot the user chose for this meal, or the one implied by the time it was eaten. */
val MealEntity.mealSlot: MealSlot
    get() = slot?.let { runCatching { MealSlot.valueOf(it) }.getOrNull() } ?: TimeUtils.mealSlot(timestamp)
