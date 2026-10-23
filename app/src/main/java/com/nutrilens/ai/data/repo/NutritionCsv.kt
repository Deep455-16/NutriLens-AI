package com.nutrilens.ai.data.repo

import com.nutrilens.ai.data.local.FoodPortion
import com.nutrilens.ai.data.local.NutritionEntity

/**
 * Parsers for assets/nutrition.csv and assets/portions.csv (both built by ml/build_nutrition.py):
 * `#` comment lines, one header line, then rows with RFC-4180 quoting. Columns are found by
 * header name, so adding a column to the CSV can never shift values into the wrong field.
 */
object NutritionCsv {

    fun parse(lines: Sequence<String>): List<NutritionEntity> = rows(lines).map { r ->
        NutritionEntity(
            label = r.str("label"),
            servingGrams = r.str("servingGrams").toInt(),
            calories = r.str("calories").toInt(),
            protein = r.num("protein"),
            carbs = r.num("carbs"),
            fat = r.num("fat"),
            fiber = r.num("fiber"),
            sugar = r.num("sugar"),
            sodiumMg = r.num("sodiumMg"),
            satFat = r.num("satFat"),
            calciumMg = r.numOrZero("calciumMg"),
            ironMg = r.numOrZero("ironMg"),
            potassiumMg = r.numOrZero("potassiumMg"),
            vitaminCMg = r.numOrZero("vitaminCMg"),
            vitaminB12Ug = r.numOrZero("vitaminB12Ug"),
            serving = r.str("serving"),
            source = r.str("source")
        )
    }

    fun parsePortions(lines: Sequence<String>): List<FoodPortion> {
        val position = HashMap<String, Int>()
        return rows(lines).map { r ->
            val label = r.str("label")
            FoodPortion(label, r.str("description"), r.num("grams"), position.merge(label, 0) { a, _ -> a + 1 }!!)
        }
    }

    private class Row(private val header: Map<String, Int>, private val cells: List<String>, private val line: String) {
        fun str(name: String): String {
            val i = header[name] ?: error("missing column '$name'")
            return cells.getOrNull(i) ?: error("row too short for '$name': $line")
        }
        fun num(name: String) = str(name).toFloat()
        fun numOrZero(name: String) = if (name in header) num(name) else 0f
    }

    private fun rows(lines: Sequence<String>): List<Row> {
        val data = lines.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        if (data.isEmpty()) return emptyList()
        val header = split(data.first()).withIndex().associate { (i, name) -> name to i }
        return data.drop(1).map { line ->
            val cells = split(line)
            require(cells.size == header.size) { "expected ${header.size} columns, got ${cells.size}: $line" }
            Row(header, cells, line)
        }
    }

    /** Splits one CSV line; quotes may wrap a field and "" inside quotes is a literal quote. */
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += field.toString(); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        out += field.toString()
        return out.map { it.trim() }
    }
}
