package com.nutrilens.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nutrilens.ai.util.toDisplayName
import kotlin.math.roundToInt

/**
 * One row of the local nutrition dataset (per typical serving), keyed by the model label.
 * Loaded from assets/nutrition.csv, which ml/build_nutrition.py builds from USDA FNDDS.
 */
@Entity(tableName = "nutrition_items")
data class NutritionEntity(
    @PrimaryKey val label: String,
    val servingGrams: Int,
    val calories: Int,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float,
    val sugar: Float = 0f,
    val sodiumMg: Float = 0f,
    val satFat: Float = 0f,
    val calciumMg: Float = 0f,
    val ironMg: Float = 0f,
    val potassiumMg: Float = 0f,
    val vitaminCMg: Float = 0f,
    val vitaminB12Ug: Float = 0f,
    /** What one serving looks like, e.g. "2 slices" or "1 bowl". */
    val serving: String = "1 serving",
    /** Where the numbers come from, e.g. "USDA FNDDS: Pizza, cheese, … (172 g)". */
    val source: String = ""
)

/**
 * Builds a meal from this standard serving scaled by [servings] (e.g. 1.5 = one and a half
 * servings). Values are stored already scaled, so totals and history need no extra logic.
 */
fun NutritionEntity.toMeal(
    confidence: Float,
    imageUri: String?,
    timestamp: Long,
    servings: Float = 1f
): MealEntity {
    // Open Food Facts rarely has vitamins and minerals: store them as unknown, not as 0.
    val micros = !PackagedFood.isPackaged(label)
    return MealEntity(
    foodLabel = label,
    foodName = ProductNames[label] ?: label.toDisplayName(),
    confidence = confidence,
    calories = (calories * servings).roundToInt(),
    protein = protein * servings,
    carbs = carbs * servings,
    fat = fat * servings,
    fiber = fiber * servings,
    servingGrams = (servingGrams * servings).roundToInt(),
    imageUri = imageUri,
    timestamp = timestamp,
    sugar = sugar * servings,
    sodiumMg = sodiumMg * servings,
    satFat = satFat * servings,
    calciumMg = (calciumMg * servings).takeIf { micros },
    ironMg = (ironMg * servings).takeIf { micros },
    potassiumMg = (potassiumMg * servings).takeIf { micros },
    vitaminCMg = (vitaminCMg * servings).takeIf { micros },
    vitaminB12Ug = (vitaminB12Ug * servings).takeIf { micros },
    serving = serving
    )
}
