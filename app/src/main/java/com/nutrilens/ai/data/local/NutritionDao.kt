package com.nutrilens.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class NutritionDao {

    @Query("SELECT * FROM nutrition_items WHERE label = :label LIMIT 1")
    abstract suspend fun getByLabel(label: String): NutritionEntity?

    @Query("SELECT * FROM nutrition_items ORDER BY label")
    abstract suspend fun getAll(): List<NutritionEntity>

    @Query("SELECT COUNT(*) FROM nutrition_items")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<NutritionEntity>)

    @Query("DELETE FROM nutrition_items")
    abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPortions(items: List<FoodPortion>)

    @Query("DELETE FROM food_portions")
    abstract suspend fun clearPortions()

    @Query("SELECT * FROM food_portions WHERE label = :label ORDER BY position")
    abstract suspend fun portionsFor(label: String): List<FoodPortion>

    /** Swaps in a fresh copy of the bundled dataset atomically (never half-loaded). */
    @Transaction
    open suspend fun replaceAll(items: List<NutritionEntity>, portions: List<FoodPortion>) {
        clear()
        insertAll(items)
        clearPortions()
        insertPortions(portions)
    }
}
