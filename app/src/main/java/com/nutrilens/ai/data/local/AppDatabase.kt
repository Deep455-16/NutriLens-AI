package com.nutrilens.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MealEntity::class, NutritionEntity::class, FoodPortion::class, Favorite::class, Correction::class, PackagedFood::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun nutritionDao(): NutritionDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun packagedFoodDao(): PackagedFoodDao

    companion object {
        /**
         * v2 adds sugar, sodium, saturated fat and serving text (USDA FNDDS dataset).
         * Saved meals keep their rows; the new columns stay NULL for them. nutrition_items is
         * reference data that is reloaded from assets on start, so it is simply recreated.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `sugar` REAL")
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `sodiumMg` REAL")
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `satFat` REAL")
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `serving` TEXT")
                db.execSQL("DROP TABLE IF EXISTS `nutrition_items`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `nutrition_items` (`label` TEXT NOT NULL, " +
                        "`servingGrams` INTEGER NOT NULL, `calories` INTEGER NOT NULL, " +
                        "`protein` REAL NOT NULL, `carbs` REAL NOT NULL, `fat` REAL NOT NULL, " +
                        "`fiber` REAL NOT NULL, `sugar` REAL NOT NULL, `sodiumMg` REAL NOT NULL, " +
                        "`satFat` REAL NOT NULL, `serving` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`label`))"
                )
            }
        }

        /**
         * v3: micronutrients, meal slot and multi-item meal groups on saved meals; new tables for
         * USDA portions, favourites and the personal correction memory. nutrition_items is
         * reference data again recreated and reloaded from assets.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (col in listOf("calciumMg", "ironMg", "potassiumMg", "vitaminCMg", "vitaminB12Ug")) {
                    db.execSQL("ALTER TABLE `meals` ADD COLUMN `$col` REAL")
                }
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `slot` TEXT")
                db.execSQL("ALTER TABLE `meals` ADD COLUMN `groupId` INTEGER")
                db.execSQL("DROP TABLE IF EXISTS `nutrition_items`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `nutrition_items` (`label` TEXT NOT NULL, " +
                        "`servingGrams` INTEGER NOT NULL, `calories` INTEGER NOT NULL, `protein` REAL NOT NULL, " +
                        "`carbs` REAL NOT NULL, `fat` REAL NOT NULL, `fiber` REAL NOT NULL, `sugar` REAL NOT NULL, " +
                        "`sodiumMg` REAL NOT NULL, `satFat` REAL NOT NULL, `calciumMg` REAL NOT NULL, " +
                        "`ironMg` REAL NOT NULL, `potassiumMg` REAL NOT NULL, `vitaminCMg` REAL NOT NULL, " +
                        "`vitaminB12Ug` REAL NOT NULL, `serving` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`label`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `food_portions` (`label` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`grams` REAL NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`label`, `description`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`label` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`label`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `corrections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, `embedding` BLOB NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v4: packaged foods looked up by barcode (Open Food Facts), cached on the phone. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `packaged_foods` (`barcode` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`brand` TEXT NOT NULL, `servingGrams` INTEGER NOT NULL, `serving` TEXT NOT NULL, " +
                        "`calories` INTEGER NOT NULL, `protein` REAL NOT NULL, `carbs` REAL NOT NULL, `fat` REAL NOT NULL, " +
                        "`fiber` REAL NOT NULL, `sugar` REAL NOT NULL, `sodiumMg` REAL NOT NULL, `satFat` REAL NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`barcode`))"
                )
            }
        }
    }
}
