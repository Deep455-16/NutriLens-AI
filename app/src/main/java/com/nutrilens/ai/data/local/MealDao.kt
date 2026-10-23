package com.nutrilens.ai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class LabelCount(val label: String, val count: Int)

@Dao
interface MealDao {

    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Update
    suspend fun update(meal: MealEntity)

    @Delete
    suspend fun delete(meal: MealEntity)

    @Query("SELECT * FROM meals ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun getById(id: Long): MealEntity?

    @Query("SELECT imageUri FROM meals WHERE imageUri IS NOT NULL")
    suspend fun imageUris(): List<String>

    /** How often each food was logged since [since], most frequent first. */
    @Query("SELECT foodLabel AS label, COUNT(*) AS count FROM meals WHERE timestamp >= :since GROUP BY foodLabel ORDER BY count DESC, MAX(timestamp) DESC LIMIT :limit")
    fun observeFrequent(since: Long, limit: Int): Flow<List<LabelCount>>

    @Query("SELECT * FROM meals WHERE groupId = :groupId ORDER BY id")
    suspend fun byGroup(groupId: Long): List<MealEntity>

    @Insert
    suspend fun insertAll(meals: List<MealEntity>): List<Long>

    @Query("DELETE FROM meals")
    suspend fun clear()
}
