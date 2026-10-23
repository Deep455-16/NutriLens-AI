package com.nutrilens.ai.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.nutrilens.ai.R
import androidx.lifecycle.viewModelScope
import com.nutrilens.ai.ai.FoodClassifier
import com.nutrilens.ai.ai.ModelNotFoundException
import com.nutrilens.ai.data.local.FoodPortion
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.toMeal
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.data.repo.PersonalRepository
import com.nutrilens.ai.domain.PersonalReranker
import com.nutrilens.ai.domain.PortionChoice
import com.nutrilens.ai.util.ImageUtils
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.vision.ImageAnalyzer
import com.nutrilens.ai.vision.PhotoQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One food in the meal being logged (a photo can hold several, e.g. dal + roti). */
data class DraftItem(
    val food: NutritionEntity,
    /** Calibrated confidence, or [MealEntity.MANUAL_CONFIDENCE] when picked by hand. */
    val confidence: Float,
    /** The model's top guesses for the photo (best first); empty when picked by hand. */
    val guesses: List<Pair<String, Float>>,
    val portion: PortionChoice,
    /** Model image embedding of the photo/region, remembered on save for personalization. */
    val embedding: FloatArray? = null,
    /** True when the user's past corrections changed the model's top answer. */
    val personalized: Boolean = false,
    /** True when the item came from a region the user marked on the photo. */
    val fromRegion: Boolean = false,
    /** Cooking oil/ghee preparation style. */
    val cookingStyle: com.nutrilens.ai.domain.CookingStyle = com.nutrilens.ai.domain.CookingStyle.STANDARD
) {
    val lowConfidence get() = confidence >= 0f && confidence < ScanViewModel.LOW_CONFIDENCE

    /** The model's best guess was weak: offer its top guesses as a choice. */
    val unsure get() = guesses.isNotEmpty() && guesses.first().second < ScanViewModel.UNSURE

    /** The other guesses, so the original top guess is still offered after a correction. */
    val alternatives get() = guesses.filter { it.first != food.label && it.second >= ScanViewModel.MIN_ALTERNATIVE }

    fun toMeal(imageUri: String?, timestamp: Long, slot: MealSlot?, groupId: Long?): MealEntity {
        val base = cookingStyle.applyTo(food.toMeal(confidence, imageUri, timestamp, portion.servings))
        return base.copy(
            serving = portion.text,
            slot = slot?.name,
            groupId = groupId
        )
    }
}

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Loading : ScanUiState

    /**
     * The meal being logged: [items] (at least one) with [selected] being edited. [meal] is the
     * selected item as it would be saved; the other fields describe it for the Result screen.
     */
    data class Success(
        val items: List<DraftItem>,
        val selected: Int,
        val slot: MealSlot,
        /** OpenCV photo check; null for manual picks or if OpenCV is unavailable. */
        val quality: PhotoQuality?,
        val meal: MealEntity
    ) : ScanUiState {
        val item get() = items[selected]
        val servings get() = item.portion.servings
        val alternatives get() = item.alternatives
        val lowConfidence get() = item.lowConfidence
        val totalCalories get() = items.sumOf { it.toMeal(null, 0, null, null).calories }
    }

    /** [message] is a string resource; [arg] fills its placeholder, if any. */
    data class Error(@StringRes val message: Int, val modelMissing: Boolean, val arg: String? = null) : ScanUiState
}

/**
 * Holds the meal currently being logged. It is scoped to the activity so the Camera, Result,
 * region and Details screens all see the same state.
 *
 * Pipeline per photo or marked region: bitmap -> OpenCV quality check (+ auto-gamma if dark)
 * -> TFLite classifier (calibrated) -> re-ranking with the user's remembered corrections ->
 * nutrition + USDA portions -> [DraftItem].
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: FoodClassifier,
    private val analyzer: ImageAnalyzer,
    private val nutritionRepo: NutritionRepository,
    private val mealRepo: MealRepository,
    private val personal: PersonalRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a marked region is being recognised (the Result screen stays visible). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var currentUri: Uri? = null
    private var scannedAt = 0L
    private var items: List<DraftItem> = emptyList()
    private var selected = 0
    private var slot: MealSlot? = null
    private var quality: PhotoQuality? = null

    val photoUri: Uri? get() = currentUri

    fun analyze(uri: Uri) {
        currentUri = uri
        scannedAt = System.currentTimeMillis()
        items = emptyList()
        selected = 0
        slot = null
        quality = null
        _state.value = ScanUiState.Loading

        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(context, uri) }
                quality = withContext(Dispatchers.Default) { analyzer.analyze(bitmap) }
                quality?.let {
                    Log.d(TAG, "sharpness=%.1f brightness=%.1f issues=%s".format(Locale.US, it.sharpness, it.brightness, it.issues))
                }
                val item = recognise(bitmap, fromRegion = false)
                bitmap.recycle()
                if (item == null) return@launch
                items = listOf(item)
                publish()
            } catch (e: ModelNotFoundException) {
                Log.w(TAG, "Model unavailable", e)
                _state.value = ScanUiState.Error(R.string.err_model_missing, modelMissing = true)
            } catch (e: Exception) {
                Log.w(TAG, "Recognition failed", e)
                _state.value = ScanUiState.Error(R.string.err_analyze, modelMissing = false)
            }
        }
    }

    /**
     * Recognises the food inside [region] (0..1 fractions of the photo) and adds it as another
     * item of this meal, e.g. each part of a thali.
     */
    fun analyzeRegion(region: RectF) {
        val uri = currentUri ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(context, uri) }
                val crop = withContext(Dispatchers.Default) {
                    val x = (region.left * full.width).roundToInt().coerceIn(0, full.width - 1)
                    val y = (region.top * full.height).roundToInt().coerceIn(0, full.height - 1)
                    val w = (region.width() * full.width).roundToInt().coerceIn(1, full.width - x)
                    val h = (region.height() * full.height).roundToInt().coerceIn(1, full.height - y)
                    Bitmap.createBitmap(full, x, y, w, h)
                }
                val item = recognise(crop, fromRegion = true)
                if (crop !== full) crop.recycle()
                full.recycle()
                if (item != null) {
                    items = items + item
                    selected = items.lastIndex
                    publish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Region recognition failed", e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Suggested region for the region screen: a detected plate, else the central area. */
    suspend fun suggestRegion(): RectF? {
        val uri = currentUri ?: return null
        return withContext(Dispatchers.Default) {
            val bmp = ImageUtils.loadBitmap(context, uri, maxDim = 800)
            analyzer.suggestRegion(bmp).also { bmp.recycle() }
        }
    }

    private suspend fun recognise(bitmap: Bitmap, fromRegion: Boolean): DraftItem? = withContext(Dispatchers.Default) {
        val q = if (fromRegion) analyzer.analyze(bitmap) else quality
        val forModel = analyzer.enhanceForModel(bitmap, q)
        val prediction = classifier.classify(forModel)
        if (forModel !== bitmap) forModel.recycle()

        val memory = withContext(Dispatchers.IO) { personal.memory() }
        val scores = PersonalReranker.rerank(prediction.scores, prediction.embedding, memory)
        val ranked = scores.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val label = ranked.first().first
        val food = withContext(Dispatchers.IO) { nutritionRepo.find(label) }
        if (food == null) {
            if (!fromRegion) {
                withContext(Dispatchers.Main) {
                    _state.value = ScanUiState.Error(R.string.err_no_nutrition_fmt, modelMissing = false, arg = label)
                }
            }
            return@withContext null
        }
        val portions = withContext(Dispatchers.IO) { nutritionRepo.portions(label) }
        DraftItem(
            food = food,
            confidence = ranked.first().second,
            guesses = ranked,
            portion = PortionChoice(portions, food.servingGrams.toFloat()),
            embedding = prediction.embedding,
            personalized = label != prediction.label,
            fromRegion = fromRegion
        )
    }

    /** Replaces the selected item's food (a correction, or a pick when no model is available). */
    fun selectFood(label: String) = viewModelScope.launch {
        val food = withContext(Dispatchers.IO) { nutritionRepo.find(label) } ?: return@launch
        val portions = withContext(Dispatchers.IO) { nutritionRepo.portions(label) }
        val portion = PortionChoice(portions, food.servingGrams.toFloat())
        if (scannedAt <= 0) scannedAt = System.currentTimeMillis()
        val old = items.getOrNull(selected)
        val item = old?.copy(food = food, confidence = MealEntity.MANUAL_CONFIDENCE, portion = portion, personalized = false)
            ?: DraftItem(food, MealEntity.MANUAL_CONFIDENCE, emptyList(), portion)
        items = if (old == null) listOf(item) else items.toMutableList().also { it[selected] = item }
        publish()
    }

    /** Adds another food to this meal, picked from the list. */
    fun addFood(label: String) = viewModelScope.launch {
        val food = withContext(Dispatchers.IO) { nutritionRepo.find(label) } ?: return@launch
        val portions = withContext(Dispatchers.IO) { nutritionRepo.portions(label) }
        if (scannedAt <= 0) scannedAt = System.currentTimeMillis()
        items = items + DraftItem(food, MealEntity.MANUAL_CONFIDENCE, emptyList(),
            PortionChoice(portions, food.servingGrams.toFloat()))
        selected = items.lastIndex
        publish()
    }

    fun selectItem(index: Int) {
        if (index in items.indices) { selected = index; publish() }
    }

    fun removeItem(index: Int) {
        if (items.size <= 1 || index !in items.indices) return
        items = items.filterIndexed { i, _ -> i != index }
        selected = selected.coerceAtMost(items.lastIndex)
        publish()
    }

    fun choosePortion(index: Int) = updatePortion { it.select(index) }
    fun stepQuantity(up: Boolean) = updatePortion { it.step(up) }
    fun setGrams(grams: Float) = updatePortion { it.withGrams(grams) }

    fun setSlot(value: MealSlot) { slot = value; publish() }

    fun setCookingStyle(style: com.nutrilens.ai.domain.CookingStyle) {
        val item = items.getOrNull(selected) ?: return
        items = items.toMutableList().also { it[selected] = item.copy(cookingStyle = style) }
        publish()
    }

    private fun updatePortion(change: (PortionChoice) -> PortionChoice) {
        val item = items.getOrNull(selected) ?: return
        items = items.toMutableList().also { it[selected] = item.copy(portion = change(item.portion)) }
        publish()
    }

    private fun publish() {
        val item = items.getOrNull(selected) ?: return
        val effectiveSlot = slot ?: TimeUtils.mealSlot(scannedAt)
        _state.value = ScanUiState.Success(
            items = items,
            selected = selected,
            slot = effectiveSlot,
            quality = quality,
            meal = item.toMeal(currentUri?.toString(), scannedAt, effectiveSlot, null)
        )
    }

    /**
     * Saves every item (one meal: same time, slot and group id when there are several) and
     * remembers each recognised photo's embedding under the label the user kept, so the next
     * photo of the same dish is recognised better. Returns the new meal ids.
     */
    suspend fun save(): List<Long> {
        val s = _state.value as? ScanUiState.Success ?: error("Nothing to save")
        val groupId = if (s.items.size > 1) scannedAt else null
        val ids = mealRepo.insertAll(s.items.map { it.toMeal(currentUri?.toString(), scannedAt, s.slot, groupId) })
        s.items.forEach { item -> item.embedding?.let { personal.remember(item.food.label, it) } }
        _state.value = ScanUiState.Idle
        return ids
    }

    companion object {
        private const val TAG = "ScanViewModel"

        /** Below this calibrated confidence the top guess is shown as "not sure". */
        const val LOW_CONFIDENCE = 0.40f
        /** Below this the top guesses are shown as a "Which one is it?" choice. */
        const val UNSURE = 0.60f
        const val MIN_ALTERNATIVE = 0.02f
    }
}
