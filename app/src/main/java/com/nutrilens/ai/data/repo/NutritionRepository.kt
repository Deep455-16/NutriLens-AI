package com.nutrilens.ai.data.repo

import android.content.Context
import com.nutrilens.ai.data.local.FoodPortion
import com.nutrilens.ai.data.local.NutritionDao
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.PackagedFood
import com.nutrilens.ai.data.local.PackagedFoodDao
import com.nutrilens.ai.data.local.ProductNames
import com.nutrilens.ai.domain.pickAlternatives
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local nutrition dataset. The CSV in assets is loaded into the Room (SQLite) table on first
 * use, so nutrition lookup never needs a network connection.
 */
@Singleton
class NutritionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NutritionDao,
    private val packaged: PackagedFoodDao
) {
    private val mutex = Mutex()
    @Volatile private var seeded = false

    /**
     * Loads the bundled CSV into Room once per process. It always replaces the table, so a
     * corrected dataset shipped in an app update reaches existing installs too.
     */
    private suspend fun ensureSeeded() {
        if (seeded) return
        mutex.withLock {
            if (seeded) return
            val (items, portions) = withContext(Dispatchers.IO) {
                context.assets.open(CSV_FILE).bufferedReader().useLines { NutritionCsv.parse(it) } to
                    context.assets.open(PORTIONS_FILE).bufferedReader().useLines { NutritionCsv.parsePortions(it) }
            }
            dao.replaceAll(items, portions)
            seeded = true
        }
    }

    suspend fun find(label: String): NutritionEntity? {
        if (PackagedFood.isPackaged(label)) {
            return packaged.get(PackagedFood.barcodeOf(label))?.also(ProductNames::put)?.toNutrition()
        }
        ensureSeeded()
        return dao.getByLabel(label)
    }

    /** Ways to measure [label] (standard serving first). */
    suspend fun portions(label: String): List<FoodPortion> {
        ensureSeeded()
        return dao.portionsFor(label)
    }

    suspend fun all(): List<NutritionEntity> {
        ensureSeeded()
        return dao.getAll()
    }

    /**
     * Suggests foods from the dataset that score better on the simple nutrition score
     * and, where possible, have fewer calories than the current food.
     */
    suspend fun alternativesFor(label: String, limit: Int = 3): List<NutritionEntity> {
        ensureSeeded()
        val everything = dao.getAll()
        return pickAlternatives(everything.firstOrNull { it.label == label }, everything, limit, MIN_SUGGESTION_KCAL)
    }

    private companion object {
        const val CSV_FILE = "nutrition.csv"
        const val PORTIONS_FILE = "portions.csv"
        const val MIN_SUGGESTION_KCAL = 120
    }
}
