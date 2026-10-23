package com.nutrilens.ai

import android.app.Application
import android.util.Log
import com.nutrilens.ai.ai.FoodClassifier
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.ProductRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class NutriLensApp : Application() {

    @Inject lateinit var meals: MealRepository
    @Inject lateinit var classifier: FoodClassifier
    @Inject lateinit var products: ProductRepository

    override fun onCreate() {
        super.onCreate()
        // Clean up photos from scans that were never saved, and load scanned products' names.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { products.loadNames() }.onFailure { Log.w("NutriLens", "Product names not loaded", it) }
            runCatching { meals.pruneOrphanImages(this@NutriLensApp) }
                .onSuccess { if (it > 0) Log.i("NutriLens", "Deleted $it unsaved meal photo(s)") }
                .onFailure { Log.w("NutriLens", "Photo cleanup failed", it) }
        }
        // Load the model and run it once now, so the user's first scan isn't the slow one.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { classifier.warmUp() }.onFailure { Log.i("NutriLens", "Model warm-up skipped: ${it.message}") }
        }
    }
}
