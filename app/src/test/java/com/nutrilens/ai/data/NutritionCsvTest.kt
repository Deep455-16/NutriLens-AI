package com.nutrilens.ai.data

import com.nutrilens.ai.data.repo.NutritionCsv
import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionCsvTest {

    @Test fun quotedFieldsKeepTheirCommas() {
        assertEquals(listOf("a", "Pizza, cheese (172 g)", "say \"hi\"", ""),
            NutritionCsv.split("a,\"Pizza, cheese (172 g)\",\"say \"\"hi\"\"\","))
    }

    @Test fun columnsAreFoundByHeaderName() {
        val rows = NutritionCsv.parse(sequenceOf(
            "label,calories,servingGrams,protein,carbs,fat,fiber,sugar,sodiumMg,satFat,ironMg,serving,source",
            "idli,97,76,4.8,19,0.3,4.4,1.4,192,0,0.7,2 idli,USDA FNDDS: Idli (76 g)"
        ))
        assertEquals(97, rows.single().calories)
        assertEquals(76, rows.single().servingGrams)
        assertEquals(0.7f, rows.single().ironMg, 0f)
        assertEquals("columns missing from an older CSV read as 0", 0f, rows.single().calciumMg, 0f)
    }

    @Test fun portionsKeepTheirOrderPerFood() {
        val p = NutritionCsv.parsePortions(sequenceOf(
            "# comment", "label,description,grams",
            "idli,2 idli,76", "idli,1 item,38", "pizza,2 slices,172", "pizza,\"1 piece, small pizza\",80"
        ))
        assertEquals(listOf(0, 1, 0, 1), p.map { it.position })
        assertEquals("1 piece, small pizza", p.last().description)
        assertEquals(38f, p[1].grams, 0f)
    }

    @Test fun parsesARowAndSkipsCommentsAndHeader() {
        val rows = NutritionCsv.parse(sequenceOf(
            "# comment",
            "label,servingGrams,calories,protein,carbs,fat,fiber,sugar,sodiumMg,satFat,serving,source",
            "pizza,172,458,19.6,57.3,16.7,4.0,6.2,929,7.7,2 slices,\"USDA FNDDS: Pizza, cheese (172 g)\""
        ))
        val p = rows.single()
        assertEquals("pizza", p.label)
        assertEquals(458, p.calories)
        assertEquals(929f, p.sodiumMg, 0f)
        assertEquals("2 slices", p.serving)
        assertEquals("USDA FNDDS: Pizza, cheese (172 g)", p.source)
    }
}
