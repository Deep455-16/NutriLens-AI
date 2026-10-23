package com.nutrilens.ai.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.nutrilens.ai.R

/**
 * Today's calories as a ring: a soft track and a green gradient arc with round ends that fills
 * clockwise from the top. Over the goal the arc turns amber. The text inside is laid out by the
 * parent layout (this view only draws the ring). Animated changes follow the system animation scale.
 */
class CalorieRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stroke = 16f * resources.displayMetrics.density
    private val bounds = RectF()
    private val rotate = Matrix()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val ringStart = ContextCompat.getColor(context, R.color.ring_start)
    private val ringEnd = ContextCompat.getColor(context, R.color.ring_end)
    private val over = ContextCompat.getColor(context, R.color.warning)

    private var shown = 0f
    private var overGoal = false
    private var animator: ValueAnimator? = null

    /** [fraction] of the goal eaten (may be above 1); the ring itself caps at a full circle. */
    fun setProgress(fraction: Float, isOver: Boolean) {
        overGoal = isOver
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 900
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val half = stroke / 2
        val size = minOf(w, h).toFloat()
        val left = (w - size) / 2 + half
        val top = (h - size) / 2 + half
        bounds.set(left, top, left + size - stroke, top + size - stroke)
        // Gradient runs light → deep along the arc, starting at 12 o'clock.
        arc.shader = SweepGradient(bounds.centerX(), bounds.centerY(), intArrayOf(ringStart, ringEnd, ringStart), floatArrayOf(0f, 0.75f, 1f))
            .also { s -> rotate.setRotate(-90f, bounds.centerX(), bounds.centerY()); s.setLocalMatrix(rotate) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(bounds, track)
        if (shown <= 0f) return
        if (overGoal) { arc.shader = null; arc.color = over } else if (arc.shader == null) onSizeChanged(width, height, width, height)
        canvas.drawArc(bounds, -90f, 360f * shown.coerceAtLeast(0.01f), false, arc)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
