package com.nutrilens.ai.data.repo

import android.content.Context
import com.nutrilens.ai.data.local.MealDao
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.util.ImageUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Single source of truth for saved meals (Repository pattern over Room). */
@Singleton
class MealRepository @Inject constructor(private val dao: MealDao) {

    fun observeAll(): Flow<List<MealEntity>> = dao.observeAll()

    fun observeRecent(limit: Int): Flow<List<MealEntity>> = dao.observeRecent(limit)

    fun observeBetween(start: Long, end: Long): Flow<List<MealEntity>> = dao.observeBetween(start, end)

    suspend fun getById(id: Long): MealEntity? = dao.getById(id)

    /**
     * Writes finish even if the caller's screen closes mid-way (save, then navigate away;
     * edit, then Back). Without this, cancelling the coroutine could silently drop the change.
     */
    private suspend fun <T> write(block: suspend () -> T): T = withContext(NonCancellable) { block() }

    suspend fun insert(meal: MealEntity): Long = write { dao.insert(meal) }

    suspend fun update(meal: MealEntity) = write { dao.update(meal) }

    suspend fun insertAll(meals: List<MealEntity>): List<Long> = write { dao.insertAll(meals) }

    suspend fun group(groupId: Long): List<MealEntity> = dao.byGroup(groupId)

    suspend fun delete(meal: MealEntity) = write {
        dao.delete(meal)
        ImageUtils.deleteInternalImage(meal.imageUri)
    }

    /** Removes the row but keeps its photo, so [restore] can undo it. Call [purgePhoto] after. */
    suspend fun deleteKeepingPhoto(meal: MealEntity) = write { dao.delete(meal) }

    /** Puts a meal removed with [deleteKeepingPhoto] back, with the same id. */
    suspend fun restore(meal: MealEntity) { write { dao.insert(meal) } }

    fun purgePhoto(meal: MealEntity) = ImageUtils.deleteInternalImage(meal.imageUri)

    suspend fun clearAll() = write { dao.clear() }

    /** Removes meal photos that no saved meal references. */
    suspend fun pruneOrphanImages(context: Context): Int =
        withContext(Dispatchers.IO) { ImageUtils.deleteUnreferenced(context, dao.imageUris()) }
}
