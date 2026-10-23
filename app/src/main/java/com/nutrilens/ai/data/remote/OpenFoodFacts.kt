package com.nutrilens.ai.data.remote

import com.nutrilens.ai.data.local.PackagedFood
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Outcome of a barcode lookup, each with its own message in the app. */
sealed interface ProductLookup {
    data class Found(val food: PackagedFood) : ProductLookup
    /** Not in Open Food Facts. */
    data object NotFound : ProductLookup
    /** In Open Food Facts, but without usable nutrition values. */
    data class NoNutrition(val name: String) : ProductLookup
    /** No connection (or it timed out). */
    data object Offline : ProductLookup
    /** The service answered with an error or something that isn't its JSON (e.g. a maintenance page). */
    data object Unavailable : ProductLookup
}

/**
 * Turns an Open Food Facts v2 product response into a [ProductLookup].
 * Values are per 100 g (per 100 ml for drinks); they are scaled to the pack's serving when it
 * states one. Energy falls back from kcal to kJ; sodium falls back to salt / 2.5.
 */
object OpenFoodFactsParser {

    fun parse(barcode: String, body: String, now: Long = System.currentTimeMillis()): ProductLookup {
        val root = try { JSONObject(body) } catch (e: JSONException) { return ProductLookup.Unavailable }
        if (root.optInt("status", 0) != 1) return ProductLookup.NotFound
        val p = root.optJSONObject("product") ?: return ProductLookup.NotFound
        val name = p.optString("product_name").trim()
        val brand = p.optString("brands").split(',').firstOrNull()?.trim().orEmpty()
        val n = p.optJSONObject("nutriments") ?: JSONObject()

        val kcal100 = n.number("energy-kcal_100g") ?: n.number("energy_100g")?.let { it / 4.184 }
        if (kcal100 == null || kcal100 < 0 || kcal100 > 950 || name.isEmpty()) {
            return ProductLookup.NoNutrition(name.ifEmpty { barcode })
        }
        val serving = p.number("serving_quantity")?.takeIf { it in 1.0..2000.0 }
        val grams = serving ?: 100.0
        val f = grams / 100.0
        val servingText = p.optString("serving_size").trim().takeIf { serving != null && it.isNotEmpty() }
            ?: "${grams.roundToInt()} ${if (p.optString("serving_quantity_unit") == "ml") "ml" else "g"}"
        val sodiumG = n.number("sodium_100g") ?: n.number("salt_100g")?.let { it / 2.5 }

        return ProductLookup.Found(
            PackagedFood(
                barcode = barcode, name = name, brand = brand,
                servingGrams = grams.roundToInt(), serving = servingText,
                calories = (kcal100 * f).roundToInt(),
                protein = n.grams("proteins_100g", f), carbs = n.grams("carbohydrates_100g", f),
                fat = n.grams("fat_100g", f), fiber = n.grams("fiber_100g", f),
                sugar = n.grams("sugars_100g", f), satFat = n.grams("saturated-fat_100g", f),
                sodiumMg = ((sodiumG ?: 0.0) * 1000 * f).toFloat(),
                fetchedAt = now
            )
        )
    }

    private fun JSONObject.number(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.grams(key: String, factor: Double): Float =
        ((number(key) ?: 0.0).coerceAtLeast(0.0) * factor).toFloat()
}

/** Looks a barcode up in Open Food Facts (https://world.openfoodfacts.org, open data, ODbL). */
@Singleton
class OpenFoodFactsClient @Inject constructor() {

    suspend fun lookup(barcode: String): ProductLookup = withContext(Dispatchers.IO) {
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            // Open Food Facts asks every app to identify itself.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            // An unknown barcode is a 404 with a normal JSON body ("product not found").
            if (code !in 200..299 && code != 404) return@withContext ProductLookup.Unavailable
            val stream = if (code == 404) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: return@withContext ProductLookup.Unavailable
            OpenFoodFactsParser.parse(barcode, body)
        } catch (e: IOException) {
            Log.w(TAG, "Lookup of $barcode failed: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                // No route to the internet at all
                is UnknownHostException, is ConnectException -> ProductLookup.Offline
                // Connected, but the server is slow or dropped us: it is "not responding", not "offline"
                else -> ProductLookup.Unavailable
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val FIELDS = "product_name,brands,serving_quantity,serving_quantity_unit,serving_size,nutriments"
        const val USER_AGENT = "NutriLensAI/1.0 (Android; college project)"
        const val TIMEOUT_MS = 10_000
        const val TAG = "OpenFoodFacts"
    }
}
