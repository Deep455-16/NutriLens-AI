package com.nutrilens.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE label = :label)")
    fun observeIsFavorite(label: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE label = :label")
    suspend fun remove(label: String)
}

@Dao
interface CorrectionDao {
    @Insert
    suspend fun insert(correction: Correction)

    @Query("SELECT * FROM corrections ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Correction>

    /** Keeps only the newest [keep] rows so the memory stays small and fast. */
    @Query("DELETE FROM corrections WHERE id NOT IN (SELECT id FROM corrections ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM corrections")
    suspend fun count(): Int

    @Query("DELETE FROM corrections")
    suspend fun clear()
}
