package com.nutrilens.ai.data

import com.nutrilens.ai.data.remote.OpenFoodFactsParser
import com.nutrilens.ai.data.remote.ProductLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real Open Food Facts v2 responses (trimmed), saved on 2026-10-23. */
class OpenFoodFactsParserTest {

    private fun fixture(name: String) = javaClass.getResource("/off/$name.json")!!.readText()

    @Test fun productWithServingIsScaledToTheServing() {
        val r = OpenFoodFactsParser.parse("8901058851298", fixture("maggi_masala"), now = 1L)
        val food = (r as ProductLookup.Found).food
        assertEquals("Maggi", food.brand)
        assertEquals(70, food.servingGrams)
        assertEquals(306, food.calories)             // 437 kcal/100 g x 0.7
        assertEquals(7.28f, food.protein, 0.01f)     // 10.4 x 0.7
        assertEquals(10.99f, food.fat, 0.01f)        // 15.7 x 0.7
        assertEquals(862.5f, food.sodiumMg, 1f)      // 1.2322 g/100 g x 0.7 x 1000
        assertEquals("pkg_8901058851298", food.label)
        assertTrue(food.name.isNotBlank())
    }

    @Test fun drinkUsesItsMlServing() {
        val food = (OpenFoodFactsParser.parse("8902080104581", fixture("pepsi")) as ProductLookup.Found).food
        assertEquals(200, food.servingGrams)
        assertEquals(86, food.calories)              // 43 kcal/100 ml x 2
        assertEquals(21.8f, food.sugar, 0.01f)
    }

    @Test fun productWithoutEnergyIsNotLoggable() {
        val r = OpenFoodFactsParser.parse("8901058000290", fixture("maggi_incomplete"))
        assertEquals(ProductLookup.NoNutrition("maggi"), r)
    }

    @Test fun unknownBarcode() =
        assertEquals(ProductLookup.NotFound, OpenFoodFactsParser.parse("8901719110016", fixture("not_found")))

    @Test fun maintenancePageIsUnavailable() =
        assertEquals(ProductLookup.Unavailable, OpenFoodFactsParser.parse("1", "<!DOCTYPE html><title>Page temporarily unavailable</title>"))

    @Test fun noServingFallsBackTo100g() {
        val body = """{"status":1,"product":{"product_name":"Nutella","brands":"Nutella, Ferrero",
            "nutriments":{"energy-kcal_100g":539,"proteins_100g":6.3,"fat_100g":30.9,"carbohydrates_100g":57.5,"salt_100g":0.107}}}"""
        val food = (OpenFoodFactsParser.parse("3017620422003", body) as ProductLookup.Found).food
        assertEquals(100, food.servingGrams)
        assertEquals("100 g", food.serving)
        assertEquals(539, food.calories)
        assertEquals("Nutella", food.brand)
        assertEquals(42.8f, food.sodiumMg, 0.1f)    // salt / 2.5
    }

    @Test fun kilojoulesOnlyAreConverted() {
        val body = """{"status":1,"product":{"product_name":"X","nutriments":{"energy_100g":418.4}}}"""
        assertEquals(100, (OpenFoodFactsParser.parse("1", body) as ProductLookup.Found).food.calories)
    }
}
