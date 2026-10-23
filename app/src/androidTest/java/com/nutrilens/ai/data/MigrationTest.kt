package com.nutrilens.ai.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutrilens.ai.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1MealsSurviveV2() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO meals (id, foodLabel, foodName, confidence, calories, protein, carbs, fat, fiber, " +
                    "servingGrams, imageUri, timestamp) VALUES (7, 'pizza', 'Pizza', 0.9, 285, 12, 36, 10, 2.5, 107, NULL, 1000)"
            )
            execSQL("INSERT INTO nutrition_items VALUES ('pizza', 107, 285, 12, 36, 10, 2.5)")
            close()
        }

        // Also validates the v2 schema against what Room generated (2.json).
        val db = helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT foodName, calories, sugar, sodiumMg, serving FROM meals WHERE id = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals("Pizza", it.getString(0))
            assertEquals(285, it.getInt(1))
            assertTrue("new nutrients are unknown, not 0", it.isNull(2) && it.isNull(3) && it.isNull(4))
        }
        db.query("SELECT COUNT(*) FROM nutrition_items").use {
            it.moveToFirst()
            assertEquals("reference table is recreated empty and reloaded from assets", 0, it.getInt(0))
        }
    }

    @Test fun v2ToV3KeepsMealsAndAddsTables() {
        helper.createDatabase(DB3, 2).apply {
            execSQL(
                "INSERT INTO meals (id, foodLabel, foodName, confidence, calories, protein, carbs, fat, fiber, " +
                    "servingGrams, imageUri, timestamp, sugar, sodiumMg, satFat, serving) VALUES " +
                    "(3, 'idli', 'Idli', 0.95, 97, 4.8, 19, 0.3, 4.4, 76, NULL, 2000, 1.4, 192, 0, '2 idli')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB3, 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT foodName, sodiumMg, serving, ironMg, slot, groupId FROM meals WHERE id = 3").use {
            assertTrue(it.moveToFirst())
            assertEquals("Idli", it.getString(0))
            assertEquals(192f, it.getFloat(1))
            assertEquals("2 idli", it.getString(2))
            assertTrue("new v3 columns are unknown", it.isNull(3) && it.isNull(4) && it.isNull(5))
        }
        db.execSQL("INSERT INTO favorites VALUES ('idli', 1)")
        db.execSQL("INSERT INTO food_portions VALUES ('idli', '1 item', 38, 1)")
    }

    @Test fun v1AllTheWayToV3() {
        helper.createDatabase(DB13, 1).apply {
            execSQL(
                "INSERT INTO meals (id, foodLabel, foodName, confidence, calories, protein, carbs, fat, fiber, " +
                    "servingGrams, imageUri, timestamp) VALUES (1, 'samosa', 'Samosa', -1, 260, 5, 28, 14, 3, 100, NULL, 5)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB13, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM meals").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test fun v3ToV4KeepsMealsAndAddsPackagedFoods() {
        helper.createDatabase(DB4, 3).apply {
            execSQL(
                "INSERT INTO meals (id, foodLabel, foodName, confidence, calories, protein, carbs, fat, fiber, " +
                    "servingGrams, imageUri, timestamp, slot) VALUES (7, 'dal', 'Dal', 0.8, 348, 20, 46, 10, 18, 240, NULL, 9, 'LUNCH')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB4, 4, true, AppDatabase.MIGRATION_3_4)
        db.query("SELECT foodName, slot FROM meals WHERE id = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals("Dal", it.getString(0))
            assertEquals("LUNCH", it.getString(1))
        }
        db.execSQL(
            "INSERT INTO packaged_foods VALUES ('8901058851298', 'Maggi Noodles Masala', 'Maggi', 70, '70 g', " +
                "306, 7.3, 31.2, 11, 2.7, 3.5, 862, 4.8, 1)"
        )
        db.query("SELECT COUNT(*) FROM packaged_foods").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    @Test fun v1AllTheWayToV4() {
        helper.createDatabase(DB14, 1).apply {
            execSQL(
                "INSERT INTO meals (id, foodLabel, foodName, confidence, calories, protein, carbs, fat, fiber, " +
                    "servingGrams, imageUri, timestamp) VALUES (1, 'samosa', 'Samosa', -1, 260, 5, 28, 14, 3, 100, NULL, 5)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            DB14, 4, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4
        )
        db.query("SELECT COUNT(*) FROM meals").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
        const val DB3 = "migration-test-v3"
        const val DB13 = "migration-test-v1-v3"
        const val DB4 = "migration-test-v4"
        const val DB14 = "migration-test-v1-v4"
    }
}
