package com.nutrilens.ai.util

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import android.transition.TransitionManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nutrilens.ai.MainActivity
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.LayoutScoreCardBinding
import com.nutrilens.ai.domain.ScoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs [block] while the fragment's view is at least STARTED. Launch collectors inside it. */
fun Fragment.collectOnStarted(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED, block)
    }
}

/** Fills the shared nutrition-score card layout. */
fun LayoutScoreCardBinding.render(result: ScoreResult) {
    val ctx = root.context
    tvScore.text = result.score.toString()
    ringScore.setProgressCompat(result.score, true)
    tvScoreHeadline.setText(result.headline)
    tvScoreDetail.text = result.detailText(ctx)
    root.contentDescription = ctx.getString(R.string.score_cd_fmt, result.score, ctx.getString(result.headline))
}

/** For a score card placed inside another card's expandable row: flat, and no second title. */
fun LayoutScoreCardBinding.embedInCard() {
    root.cardElevation = 0f
    root.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
    (root.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
    tvScoreTitle.isVisible = false
}

/** The score's explanation sentences, e.g. "Good source of protein. High in sodium." */
fun ScoreResult.detailText(context: Context): String = notes.joinToString(" ") { context.getString(it) }

/** Tapping [row] opens or closes [content] (animated), turning [chevron] to point the right way. */
fun bindExpand(row: View, content: View, chevron: View) {
    fun show(open: Boolean) {
        content.isVisible = open
        chevron.animate().rotation(if (open) -90f else 90f).setDuration(200).start()
        ViewCompat.setStateDescription(row, row.context.getString(if (open) R.string.state_expanded else R.string.state_collapsed))
    }
    show(content.isVisible)
    row.setOnClickListener {
        (row.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it) }
        show(!content.isVisible)
    }
}

/** After logging: back to Home with "Logged Samosa, Chai · 312 kcal" and Undo. */
fun Fragment.mealSaved(labels: List<String>, kcal: Int, mealIds: List<Long>) {
    val names = labels.joinToString(", ") { requireContext().foodName(it) }
    (requireActivity() as MainActivity).showMealSaved(getString(R.string.meal_logged_fmt, names, kcal), mealIds)
}

/** Navigates to [destination] with the app's slide transition (for destinations without an XML action). */
fun Fragment.navigateSlide(destination: Int, args: Bundle? = null, popUpToHome: Boolean = false) {
    findNavController().navigate(destination, args, navOptions {
        anim {
            enter = R.anim.nl_enter
            exit = R.anim.nl_exit
            popEnter = R.anim.nl_pop_enter
            popExit = R.anim.nl_pop_exit
        }
        if (popUpToHome) popUpTo(R.id.homeFragment)
    })
}

/**
 * Opens a food's page. With [photo] (that dish's photo on screen) the photo grows into the
 * page's header (shared-element container transform); otherwise the usual slide.
 */
fun Fragment.openFood(label: String, photo: ImageView? = null, slot: String? = null) {
    val args = bundleOf("label" to label, "slot" to slot)
    if (photo == null || !DishPhotos.has(requireContext(), label)) return navigateSlide(R.id.foodDetailFragment, args)
    photo.transitionName = dishTransitionName(label)
    args.putBoolean(ARG_SHARED_PHOTO, true)
    findNavController().navigate(R.id.foodDetailFragment, args, null, FragmentNavigatorExtras(photo to photo.transitionName))
}

fun dishTransitionName(label: String) = "dish_$label"

/** Set by [openFood] when the food page opens with the photo transition. */
const val ARG_SHARED_PHOTO = "sharedPhoto"

/** Tapping the score card explains how the score was built (each part, in points). */
fun LayoutScoreCardBinding.showScoreBreakdownOnClick(result: ScoreResult) {
    root.isClickable = true
    root.setOnClickListener {
        val ctx = root.context
        val lines = result.parts.joinToString("\n") { p ->
            val reason = if (p.arg == null) ctx.getString(p.reason) else ctx.getString(p.reason, p.arg)
            "${if (p.points > 0) "+" else if (p.points < 0) "−" else " "}${kotlin.math.abs(p.points)}  $reason"
        }
        val clamped = if (result.parts.sumOf { it.points } != result.score) "\n\n" + ctx.getString(R.string.score_clamped) else ""
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.score_title_fmt, result.score))
            .setMessage("$lines\n\n= ${result.score}$clamped\n\n${ctx.getString(R.string.score_disclaimer)}")
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}

/** One cell of a macro row: coloured bar, amount, name (layout_macro_cell.xml). */
fun com.nutrilens.ai.databinding.LayoutMacroCellBinding.show(
    @androidx.annotation.ColorRes color: Int, value: String, @androidx.annotation.StringRes label: Int
) {
    bar.backgroundTintList = android.content.res.ColorStateList.valueOf(
        androidx.core.content.ContextCompat.getColor(root.context, color)
    )
    tvValue.text = value
    tvLabel.setText(label)
}
