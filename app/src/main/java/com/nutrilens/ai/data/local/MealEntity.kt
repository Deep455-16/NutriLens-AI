package com.nutrilens.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved meal. Nutrition values are estimates copied from the local nutrition
 * dataset at the time the meal was saved.
 *
 * [confidence] is the model confidence in 0..1, or a negative value when the user
 * chose the food manually.
 */
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodLabel: String,
    val foodName: String,
    val confidence: Float,
    val calories: Int,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float,
    val servingGrams: Int,
    val imageUri: String?,
    val timestamp: Long,
    // Added in database version 2; null for meals saved before (shown as "–", not as 0).
    val sugar: Float? = null,
    val sodiumMg: Float? = null,
    val satFat: Float? = null,
    /** The dataset's description of one serving, e.g. "2 slices". */
    val serving: String? = null,
    // Added in database version 3 (null for older meals).
    val calciumMg: Float? = null,
    val ironMg: Float? = null,
    val potassiumMg: Float? = null,
    val vitaminCMg: Float? = null,
    val vitaminB12Ug: Float? = null,
    /** Meal slot the user chose (MealSlot name); null = derived from the time eaten. */
    val slot: String? = null,
    /** Items logged together as one meal (e.g. dal + 2 roti) share this id; null = single item. */
    val groupId: Long? = null
) {
    companion object {
        /** Stored as the confidence of a meal the user picked by hand. */
        const val MANUAL_CONFIDENCE = -1f
    }
}
