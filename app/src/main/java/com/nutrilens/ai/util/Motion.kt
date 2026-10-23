package com.nutrilens.ai.util

import android.animation.ValueAnimator
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.nutrilens.ai.R

/*
 * The app's motion vocabulary. Everything here is Animator-based, so it follows the system
 * "Animation duration scale" / "Remove animations" setting: with animations off, views simply
 * appear in their final state.
 */

private val easeOut = FastOutSlowInInterpolator()

/** Fades [view] in while it rises a little (the default way content appears). */
fun View.riseIn(delay: Long = 0, distanceDp: Float = 16f, duration: Long = 320) {
    alpha = 0f
    translationY = distanceDp * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(easeOut).start()
}

/** Rises the visible direct children in one after another, top to bottom. */
fun ViewGroup.staggerIn(step: Long = 45, startDelay: Long = 0, max: Int = 10) {
    children.filter { it.isVisible }.take(max).forEachIndexed { i, child -> child.riseIn(startDelay + i * step) }
}

/** Pops [view] in from small with a little overshoot (confirmations, badges). */
fun View.popIn(delay: Long = 0, duration: Long = 420) {
    alpha = 0f
    scaleX = 0.4f
    scaleY = 0.4f
    animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(duration)
        .setInterpolator(OvershootInterpolator(2.2f)).start()
}

/** Cross-fades [view] in or out instead of toggling it abruptly. */
fun View.fadeVisible(visible: Boolean, duration: Long = 180) {
    if (visible == isVisible && alpha == 1f) return
    animate().cancel()
    if (visible) {
        if (!isVisible) { alpha = 0f; isVisible = true }
        animate().alpha(1f).setDuration(duration).start()
    } else {
        animate().alpha(0f).setDuration(duration).withEndAction { isVisible = false; alpha = 1f }.start()
    }
}

/**
 * Counts a number shown in this TextView from its current value to [to]. The value is kept in the
 * view's tag, so re-binding the same number does nothing and changes animate from where it was.
 */
fun TextView.countTo(to: Int, duration: Long = 550, format: (Int) -> String = { it.toString() }) {
    val from = (getTag(COUNT_TAG) as? Int)
    (getTag(COUNT_ANIM_TAG) as? ValueAnimator)?.cancel()
    setTag(COUNT_TAG, to)
    if (from == null || from == to) {
        text = format(to)
        return
    }
    val anim = ValueAnimator.ofInt(from, to).apply {
        this.duration = duration
        interpolator = easeOut
        addUpdateListener { text = format(it.animatedValue as Int) }
    }
    setTag(COUNT_ANIM_TAG, anim)
    anim.start()
}

/** Cascades the first screenful of rows in, once, when the list first gets data. */
fun RecyclerView.cascadeInOnce() {
    if (getTag(CASCADE_TAG) == true || (adapter?.itemCount ?: 0) == 0) return
    setTag(CASCADE_TAG, true)
    // The item animator would also fade these first rows in; two animations on one view leave
    // rows stuck half-transparent. Pause it for this first layout only.
    val itemAnimatorBackup = itemAnimator
    itemAnimator = null
    doOnPreDraw {
        children.take(12).forEachIndexed { i, row -> row.riseIn(i * 35L, distanceDp = 24f, duration = 360) }
        post { itemAnimator = itemAnimatorBackup }
    }
}

enum class Haptic { TICK, CONFIRM, REJECT, HEAVY }

/** Short, system-standard vibration feedback (respects the user's touch-feedback setting). */
fun View.haptic(kind: Haptic) {
    val constant = when (kind) {
        Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.HEAVY -> HapticFeedbackConstants.LONG_PRESS
        Haptic.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

private val COUNT_TAG = R.id.tag_count_value
private val COUNT_ANIM_TAG = R.id.tag_count_animator
private val CASCADE_TAG = R.id.tag_cascaded
