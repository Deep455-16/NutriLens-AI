package com.nutrilens.ai.data.repo

import com.nutrilens.ai.data.local.Correction
import com.nutrilens.ai.data.local.CorrectionDao
import com.nutrilens.ai.data.local.Favorite
import com.nutrilens.ai.data.local.FavoriteDao
import com.nutrilens.ai.data.local.LabelCount
import com.nutrilens.ai.data.local.MealDao
import com.nutrilens.ai.domain.PersonalReranker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** What the app learns about this user: favourites, frequent foods and the correction memory. */
@Singleton
class PersonalRepository @Inject constructor(
    private val favorites: FavoriteDao,
    private val corrections: CorrectionDao,
    private val meals: MealDao
) {
    fun favorites(): Flow<List<Favorite>> = favorites.observeAll()
    fun isFavorite(label: String): Flow<Boolean> = favorites.observeIsFavorite(label)

    suspend fun setFavorite(label: String, on: Boolean) =
        if (on) favorites.add(Favorite(label, System.currentTimeMillis())) else favorites.remove(label)

    /** Foods logged most often in the last [days] days. */
    fun frequent(days: Int = 14, limit: Int = 8): Flow<List<LabelCount>> =
        meals.observeFrequent(System.currentTimeMillis() - days * 86_400_000L, limit)

    /** Remembers a labelled photo (as its embedding only) for future re-ranking. */
    suspend fun remember(label: String, embedding: FloatArray) {
        val bytes = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { bytes.putFloat(it) }
        corrections.insert(Correction(label = label, embedding = bytes.array(), createdAt = System.currentTimeMillis()))
        corrections.trim(MAX_MEMORY)
    }

    suspend fun memory(): List<PersonalReranker.Memory> = corrections.recent(MAX_MEMORY).map { c ->
        val buf = ByteBuffer.wrap(c.embedding).order(ByteOrder.LITTLE_ENDIAN)
        PersonalReranker.Memory(c.label, FloatArray(c.embedding.size / 4) { buf.float })
    }

    suspend fun memorySize(): Int = corrections.count()

    suspend fun forget() = corrections.clear()

    private companion object { const val MAX_MEMORY = 500 }
}
