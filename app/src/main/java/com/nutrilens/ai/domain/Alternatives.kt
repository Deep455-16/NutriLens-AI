package com.nutrilens.ai.domain

import com.nutrilens.ai.data.local.NutritionEntity

/** Coarse kind of food, so a drink is swapped for a drink and a dessert for a dessert. */
enum class FoodKind { DRINK, DESSERT, DISH }

private val DRINKS = setOf("chai", "lassi", "buttermilk")
private val DESSERTS = setOf(
    "apple_pie", "baklava", "beignets", "bread_pudding", "cannoli", "carrot_cake", "cheesecake",
    "chocolate_cake", "chocolate_mousse", "churros", "creme_brulee", "cup_cakes", "donuts", "frozen_yogurt",
    "ice_cream", "macarons", "panna_cotta", "red_velvet_cake", "strawberry_shortcake", "tiramisu",
    "jalebi", "kulfi", "gulab_jamun", "rasgulla", "kheer", "gajar_halwa"
)

fun kindOf(label: String): FoodKind = when (label) {
    in DRINKS -> FoodKind.DRINK
    in DESSERTS -> FoodKind.DESSERT
    else -> FoodKind.DISH
}

/**
 * Healthier swaps for [current]: same kind of food, same cuisine and no more calories first,
 * then widening step by step. Ranked by Nutrition Score, then fewer calories. Dishes below
 * [minKcal] are skipped (a side salad is not a swap for a meal); drinks have no minimum.
 */
fun pickAlternatives(current: NutritionEntity?, all: List<NutritionEntity>, limit: Int = 3, minKcal: Int = 120): List<NutritionEntity> {
    val ranked = compareByDescending<NutritionEntity> { NutritionScore.evaluate(it).score }.thenBy { it.calories }
    val pool = all.filter { it.label != current?.label }
    if (current == null) return pool.filter { it.calories >= minKcal }.sortedWith(ranked).take(limit)

    val kind = kindOf(current.label)
    val indian = isIndianDish(current.label)
    val bigEnough = { f: NutritionEntity -> kind == FoodKind.DRINK || f.calories >= minKcal }
    val tiers = listOf<(NutritionEntity) -> Boolean>(
        { kindOf(it.label) == kind && isIndianDish(it.label) == indian && it.calories <= current.calories },
        { kindOf(it.label) == kind && it.calories <= current.calories },
        { kindOf(it.label) == kind && isIndianDish(it.label) == indian },
        { kindOf(it.label) == kind }
    )
    val picked = LinkedHashSet<NutritionEntity>()
    for (tier in tiers) {
        if (picked.size >= limit) break
        pool.filter { tier(it) && bigEnough(it) && it !in picked }.sortedWith(ranked).take(limit - picked.size).forEach { picked += it }
    }
    return picked.toList()
}
