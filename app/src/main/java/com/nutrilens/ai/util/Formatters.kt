package com.nutrilens.ai.util

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.dispose
import coil.imageLoader
import coil.load
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.PackagedFood
import com.nutrilens.ai.data.local.ProductNames
import com.nutrilens.ai.domain.NutritionScore
import kotlin.math.roundToInt

/**
 * The dish name for a model label in the user's language: the `food_<label>` string resource
 * (translated in values-hi), falling back to the label itself for unknown labels.
 */
fun Context.foodName(label: String): String {
    if (PackagedFood.isPackaged(label)) return ProductNames[label] ?: getString(R.string.packaged_food)
    @Suppress("DiscouragedApi") // labels come from the model at runtime, so the id is looked up by name
    val id = resources.getIdentifier("food_$label", "string", packageName)
    return if (id != 0) getString(id) else label.toDisplayName()
}

/** A saved meal's name: like [foodName], but a scanned product keeps the name it was saved with. */
fun Context.mealName(meal: MealEntity): String =
    if (PackagedFood.isPackaged(meal.foodLabel)) ProductNames[meal.foodLabel] ?: meal.foodName else foodName(meal.foodLabel)

/** "grilled_salmon" -> "Grilled Salmon", "fish_and_chips" -> "Fish and Chips". */
fun String.toDisplayName(): String =
    split('_').joinToString(" ") { word ->
        if (word == "and") word else word.replaceFirstChar { it.uppercase() }
    }

/** Sets the confidence pill text and colours: green 70%+, amber 40–69%, red below, grey if manual. */
fun TextView.showConfidence(confidence: Float) {
    text = context.confidenceText(confidence)
    val (fg, bg) = when {
        confidence < 0f -> R.color.conf_manual_text to R.color.conf_manual_bg
        confidence >= 0.70f -> R.color.brand_green to R.color.green_light
        confidence >= 0.40f -> R.color.conf_mid_text to R.color.conf_mid_bg
        else -> R.color.conf_low_text to R.color.conf_low_bg
    }
    setTextColor(ContextCompat.getColor(context, fg))
    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bg))
}

/** A nutrition tag pill: green for good news, warning colours for "high in" tags. */
fun TextView.showTag(tag: NutritionScore.Tag) {
    text = context.getString(tag.label)
    val (fg, bg) = if (tag.positive) R.color.brand_green to R.color.green_light else R.color.conf_low_text to R.color.conf_low_bg
    setTextColor(ContextCompat.getColor(context, fg))
    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bg))
}

/** Shown percentage for a model confidence, capped at 99 (a model is never certain); null = manual pick. */
fun confidencePercent(confidence: Float): Int? =
    if (confidence < 0f) null else (confidence * 100).roundToInt().coerceAtMost(99)

fun Context.confidenceText(confidence: Float): String =
    confidencePercent(confidence)?.let { getString(R.string.confidence_fmt, it) } ?: getString(R.string.manual_selection)

fun Float.gramsText(): String = "${roundToInt()} g"

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Bundled photos of the recognised dishes (assets/dish_photos/<label>.webp), listed once. */
object DishPhotos {
    @Volatile private var labels: Set<String>? = null

    fun has(context: Context, label: String): Boolean {
        val known = labels ?: (context.assets.list("dish_photos").orEmpty()
            .filter { it.endsWith(".webp") }.map { it.removeSuffix(".webp") }.toSet()).also { labels = it }
        return label in known
    }

    fun uri(label: String) = "file:///android_asset/dish_photos/$label.webp"
}

/**
 * A dish's photo, or its kind's icon on a soft tile when the dish has no bundled photo.
 * [onShown] runs once the picture is on screen (or failed), e.g. to start a shared transition.
 */
fun ImageView.loadDish(label: String, onShown: (() -> Unit)? = null) {
    imageTintList = null
    if (DishPhotos.has(context, label)) {
        scaleType = ImageView.ScaleType.CENTER_CROP
        load(DishPhotos.uri(label)) {
            crossfade(onShown == null)
            error(FoodTileDrawable(context, label))
            if (onShown != null) listener(onSuccess = { _, _ -> onShown() }, onError = { _, _ -> onShown() })
        }
    } else {
        // A recycled row may still be loading another dish's photo: stop it before showing the tile.
        dispose()
        scaleType = ImageView.ScaleType.FIT_XY
        setImageDrawable(FoodTileDrawable(context, label))
        onShown?.invoke()
    }
}

/**
 * A saved meal's picture: the user's own photo; for manual logs, the dish's photo
 * (or its kind's icon when there is none).
 */
fun ImageView.loadMeal(uri: String?, label: String? = null) {
    if (uri.isNullOrBlank()) {
        if (label != null) return loadDish(label)
        dispose()
        scaleType = ImageView.ScaleType.FIT_XY
        imageTintList = null
        setImageDrawable(FoodTileDrawable(context, null))
    } else {
        scaleType = ImageView.ScaleType.CENTER_CROP
        imageTintList = null
        load(Uri.parse(uri)) {
            crossfade(true)
            error(FoodTileDrawable(context, label))
        }
    }
}

/** Shows the dish's photo as a small round icon at the start of a chip (when it has one). */
fun com.google.android.material.chip.Chip.dishIcon(label: String) {
    if (!DishPhotos.has(context, label)) return
    chipIconSize = resources.displayMetrics.density * 26
    context.imageLoader.enqueue(
        coil.request.ImageRequest.Builder(context).data(DishPhotos.uri(label)).size(96)
            .transformations(coil.transform.CircleCropTransformation())
            .target { d -> chipIcon = d; isChipIconVisible = true }.build()
    )
}
