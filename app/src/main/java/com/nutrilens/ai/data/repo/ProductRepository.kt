package com.nutrilens.ai.data.repo

import com.nutrilens.ai.data.local.PackagedFood
import com.nutrilens.ai.data.local.PackagedFoodDao
import com.nutrilens.ai.data.local.ProductNames
import com.nutrilens.ai.data.remote.OpenFoodFactsClient
import com.nutrilens.ai.data.remote.ProductLookup
import javax.inject.Inject
import javax.inject.Singleton

/** Packaged foods by barcode: products already looked up come from the phone, others from Open Food Facts. */
@Singleton
class ProductRepository @Inject constructor(
    private val dao: PackagedFoodDao,
    private val api: OpenFoodFactsClient
) {
    suspend fun lookup(barcode: String): ProductLookup {
        dao.get(barcode)?.let { ProductNames.put(it); return ProductLookup.Found(it) }
        val result = api.lookup(barcode)
        if (result is ProductLookup.Found) {
            dao.upsert(result.food)
            ProductNames.put(result.food)
        }
        return result
    }

    suspend fun get(barcode: String): PackagedFood? = dao.get(barcode)?.also(ProductNames::put)

    /** Loads the names of products scanned before, for lists of saved meals. */
    suspend fun loadNames() = dao.all().forEach(ProductNames::put)
}
