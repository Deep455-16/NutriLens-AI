package com.nutrilens.ai.ui.region

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nutrilens.ai.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows a photo (fit to the view) with a box the user drags to move and resizes from its
 * corners. [region] is in 0..1 fractions of the photo, ready for cropping.
 */
class RegionSelectorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) { field = value; requestLayout(); invalidate() }

    var region = RectF(0.15f, 0.15f, 0.85f, 0.85f)
        set(value) { field = RectF(value); invalidate() }

    private val image = RectF()
    private val box = RectF()
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
        color = ContextCompat.getColor(context, R.color.brand_green)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border.color }
    private val touchSlop = resources.displayMetrics.density * 36
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Drag { NONE, MOVE, TL, TR, BL, BR }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * scale
        val h = bmp.height * scale
        image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bmp, null, image, null)

        box.set(
            image.left + region.left * image.width(), image.top + region.top * image.height(),
            image.left + region.right * image.width(), image.top + region.bottom * image.height()
        )
        canvas.drawRect(image.left, image.top, image.right, box.top, dim)
        canvas.drawRect(image.left, box.bottom, image.right, image.bottom, dim)
        canvas.drawRect(image.left, box.top, box.left, box.bottom, dim)
        canvas.drawRect(box.right, box.top, image.right, box.bottom, dim)
        canvas.drawRect(box, border)
        val r = border.strokeWidth * 3
        for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)) {
            canvas.drawCircle(x, y, r, handle)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    near(e, box.left, box.top) -> Drag.TL
                    near(e, box.right, box.top) -> Drag.TR
                    near(e, box.left, box.bottom) -> Drag.BL
                    near(e, box.right, box.bottom) -> Drag.BR
                    box.contains(e.x, e.y) -> Drag.MOVE
                    else -> Drag.NONE
                }
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(drag != Drag.NONE)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / image.width()
                val dy = (e.y - lastY) / image.height()
                lastX = e.x; lastY = e.y
                val r = RectF(region)
                when (drag) {
                    Drag.MOVE -> r.offset(
                        dx.coerceIn(-r.left, 1f - r.right), dy.coerceIn(-r.top, 1f - r.bottom)
                    )
                    Drag.TL -> { r.left = (r.left + dx).coerceIn(0f, r.right - MIN); r.top = (r.top + dy).coerceIn(0f, r.bottom - MIN) }
                    Drag.TR -> { r.right = (r.right + dx).coerceIn(r.left + MIN, 1f); r.top = (r.top + dy).coerceIn(0f, r.bottom - MIN) }
                    Drag.BL -> { r.left = (r.left + dx).coerceIn(0f, r.right - MIN); r.bottom = (r.bottom + dy).coerceIn(r.top + MIN, 1f) }
                    Drag.BR -> { r.right = (r.right + dx).coerceIn(r.left + MIN, 1f); r.bottom = (r.bottom + dy).coerceIn(r.top + MIN, 1f) }
                    Drag.NONE -> return false
                }
                region = r
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                return true
            }
        }
        return false
    }

    private fun near(e: MotionEvent, x: Float, y: Float) = abs(e.x - x) < touchSlop && abs(e.y - y) < touchSlop

    private companion object { const val MIN = 0.1f }
}
