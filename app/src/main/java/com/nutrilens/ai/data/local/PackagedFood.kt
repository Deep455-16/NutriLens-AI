package com.nutrilens.ai.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.concurrent.ConcurrentHashMap

/**
 * A packaged food looked up by barcode in Open Food Facts, kept on the phone so it opens again
 * without internet. Nutrients are for [servingGrams] (the pack's serving, or 100 g / 100 ml).
 */
@Entity(tableName = "packaged_foods")
data class PackagedFood(
    @PrimaryKey val barcode: String,
    val name: String,
    val brand: String,
    val servingGrams: Int,
    val serving: String,
    val calories: Int,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float,
    val sugar: Float,
    val sodiumMg: Float,
    val satFat: Float,
    val fetchedAt: Long
) {
    val label: String get() = LABEL_PREFIX + barcode

    /** As a food the rest of the app understands (detail screen, portions, logging, score). */
    fun toNutrition() = NutritionEntity(
        label = label, servingGrams = servingGrams, calories = calories, protein = protein, carbs = carbs,
        fat = fat, fiber = fiber, sugar = sugar, sodiumMg = sodiumMg, satFat = satFat,
        serving = serving, source = "Open Food Facts (ODbL), barcode $barcode"
    )

    companion object {
        const val LABEL_PREFIX = "pkg_"
        fun isPackaged(label: String) = label.startsWith(LABEL_PREFIX)
        fun barcodeOf(label: String) = label.removePrefix(LABEL_PREFIX)
    }
}

@Dao
interface PackagedFoodDao {
    @Query("SELECT * FROM packaged_foods WHERE barcode = :barcode")
    suspend fun get(barcode: String): PackagedFood?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(food: PackagedFood)

    @Query("SELECT * FROM packaged_foods")
    suspend fun all(): List<PackagedFood>
}

/**
 * Display names of scanned products by label, so labels like "pkg_8901058851298" show as
 * "Maggi Noodles Masala" anywhere a food name is needed. Filled at start-up and on each lookup.
 */
object ProductNames {
    private val names = ConcurrentHashMap<String, String>()
    fun put(food: PackagedFood) { names[food.label] = food.name }
    operator fun get(label: String): String? = names[label]
}
