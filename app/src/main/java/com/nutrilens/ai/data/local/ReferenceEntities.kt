package com.nutrilens.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One way to measure a food, e.g. "1 medium dosa" = 267 g (USDA FNDDS portions). */
@Entity(tableName = "food_portions", primaryKeys = ["label", "description"])
data class FoodPortion(
    val label: String,
    val description: String,
    val grams: Float,
    /** Order in the list; 0 is the food's standard serving. */
    val position: Int
)

/** A food the user starred for one-tap logging. */
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val label: String,
    val addedAt: Long
)

/**
 * A photo the user labelled (by saving a scan, or by correcting the model), kept as the model's
 * image embedding only (no picture). Used to re-rank future predictions for this user.
 */
@Entity(tableName = "corrections")
data class Correction(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** L2-normalised float32 embedding, little-endian. */
    val embedding: ByteArray,
    val createdAt: Long
)
