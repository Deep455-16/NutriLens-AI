package com.nutrilens.ai.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.PackagedFood
import com.nutrilens.ai.domain.FoodKind
import com.nutrilens.ai.domain.kindOf

/**
 * Stand-in for a food without a photo: its kind's icon (bowl, cake, cup, barcode) on a soft
 * tile in that kind's colour. The ImageView's shape appearance rounds the corners.
 */
class FoodTileDrawable(context: Context, label: String?) : Drawable() {

    private val look = when {
        label == null -> DISH
        PackagedFood.isPackaged(label) -> Style(R.drawable.ic_barcode, R.color.tile_blue, R.color.macro_protein_fixed)
        else -> when (kindOf(label)) {
            FoodKind.DRINK -> Style(R.drawable.ic_slot_breakfast, R.color.tile_amber, R.color.macro_carbs_fixed)
            FoodKind.DESSERT -> Style(R.drawable.ic_food_cake, R.color.tile_coral, R.color.macro_fat_fixed)
            FoodKind.DISH -> DISH
        }
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, look.tile) }
    private val icon = AppCompatResources.getDrawable(context, look.icon)!!.mutate()
        .apply { setTint(ContextCompat.getColor(context, look.tint)) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, bg)
        val half = (minOf(b.width(), b.height()) * 0.21f).toInt()
        icon.setBounds(b.centerX() - half, b.centerY() - half, b.centerX() + half, b.centerY() + half)
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; icon.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private data class Style(val icon: Int, val tile: Int, val tint: Int)

    private companion object {
        val DISH = Style(R.drawable.ic_food_bowl, R.color.green_light, R.color.green_dark)
    }
}
