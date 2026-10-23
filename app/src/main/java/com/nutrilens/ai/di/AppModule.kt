package com.nutrilens.ai.di

import android.content.Context
import androidx.room.Room
import com.nutrilens.ai.data.local.AppDatabase
import com.nutrilens.ai.data.local.CorrectionDao
import com.nutrilens.ai.data.local.FavoriteDao
import com.nutrilens.ai.data.local.MealDao
import com.nutrilens.ai.data.local.NutritionDao
import com.nutrilens.ai.data.local.PackagedFoodDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        // No destructive fallback: a schema change must ship a Migration (schemas are exported
        // to app/schemas) instead of silently wiping the user's meal history.
        Room.databaseBuilder(context, AppDatabase::class.java, "nutrilens.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideMealDao(db: AppDatabase): MealDao = db.mealDao()

    @Provides
    fun provideNutritionDao(db: AppDatabase): NutritionDao = db.nutritionDao()

    @Provides
    fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideCorrectionDao(db: AppDatabase): CorrectionDao = db.correctionDao()

    @Provides
    fun providePackagedFoodDao(db: AppDatabase): PackagedFoodDao = db.packagedFoodDao()
}
