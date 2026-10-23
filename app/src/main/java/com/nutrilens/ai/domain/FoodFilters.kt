package com.nutrilens.ai.domain

import androidx.annotation.StringRes
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.NutritionEntity

val INDIAN_DISH_LABELS: Set<String> = setOf(
    "butter_naan", "chai", "chapati", "chole_bhature", "dal_makhani", "dhokla", "samosa", "chicken_curry",
    "idli", "jalebi", "kaathi_rolls", "kadai_paneer", "kulfi", "masala_dosa",
    "momos", "paani_puri", "pakode", "pav_bhaji", "biryani", "dabeli", "dal",
    "kofta", "vada_pav", "paneer_tikka", "palak_paneer", "matar_paneer",
    "shahi_paneer", "dal_tadka", "rajma_chawal", "chana_masala", "aloo_paratha",
    "paneer_paratha", "poha", "upma", "sambar", "rasam", "medu_vada", "uttapam",
    "khichdi", "aloo_gobi", "bhindi_masala", "baingan_bharta", "butter_chicken",
    "chicken_tikka", "tandoori_chicken", "fish_curry", "mutton_curry", "egg_curry",
    "gulab_jamun", "rasgulla", "kheer", "gajar_halwa", "lassi", "buttermilk",
    "bhel_puri", "sev_puri", "ragda_pattice", "misal_pav", "jeera_rice", "pulao",
    "curd_rice", "raita", "thepla", "appam", "poori_bhaji"
)

fun isIndianDish(label: String): Boolean = label in INDIAN_DISH_LABELS

/** Filters offered in the food library; thresholds are per standard serving. */
enum class FoodFilter(@StringRes val label: Int, val matches: (NutritionEntity) -> Boolean) {
    ALL(R.string.all, { true }),
    INDIAN(R.string.filter_indian, { isIndianDish(it.label) }),
    HIGH_PROTEIN(R.string.high_protein, { it.protein * 4f / it.calories.coerceAtLeast(1) >= 0.25f }),
    LOW_CALORIE(R.string.under_250_kcal, { it.calories < 250 }),
    HIGH_FIBER(R.string.high_fiber, { it.fiber >= 4f }),
    BEST_SCORE(R.string.score_70, { NutritionScore.evaluate(it).score >= 70 })
}

enum class FoodSort(@StringRes val label: Int, val comparator: Comparator<NutritionEntity>) {
    /** Indian dishes first (those with a photo first, see [browse]), then the rest; by name within. */
    FEATURED(R.string.sort_featured, compareBy<NutritionEntity> { !isIndianDish(it.label) }.thenBy { it.label }),
    NAME(R.string.sort_name, compareBy { it.label }),
    CALORIES_LOW(R.string.sort_calories_low, compareBy<NutritionEntity> { it.calories }.thenBy { it.label }),
    PROTEIN_HIGH(R.string.sort_protein_high, compareByDescending<NutritionEntity> { it.protein }.thenBy { it.label }),
    SCORE_HIGH(R.string.sort_score_high, compareByDescending<NutritionEntity> { NutritionScore.evaluate(it).score }.thenBy { it.label })
}

/** Search matches the label ("grilled sal") or the name shown in the app's language ("इडली"). */
fun matchesQuery(label: String, name: String, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q.replace(' ', '_'), ignoreCase = true) || name.contains(q, ignoreCase = true)
}

fun List<NutritionEntity>.browse(
    query: String, filter: FoodFilter, sort: FoodSort, name: (String) -> String = { it },
    hasPhoto: (String) -> Boolean = { false }
): List<NutritionEntity> {
    val order = if (sort == FoodSort.FEATURED) {
        compareBy<NutritionEntity> { !isIndianDish(it.label) }.thenBy { !hasPhoto(it.label) }.thenBy { it.label }
    } else sort.comparator
    return filter { matchesQuery(it.label, name(it.label), query) && filter.matches(it) }.sortedWith(order)
}
