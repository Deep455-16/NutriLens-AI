package com.nutrilens.ai.ui.result

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.FragmentResultBinding
import com.nutrilens.ai.databinding.ItemDraftBinding
import com.nutrilens.ai.databinding.ItemGuessCardBinding
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.ui.common.FoodPickerSheet
import com.nutrilens.ai.ui.common.PortionPicker
import com.nutrilens.ai.ui.common.ScanUiState
import com.nutrilens.ai.ui.common.ScanViewModel
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.countTo
import com.nutrilens.ai.util.haptic
import com.nutrilens.ai.util.popIn
import com.nutrilens.ai.util.riseIn
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.confidencePercent
import com.nutrilens.ai.util.dishIcon
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.bindExpand
import com.nutrilens.ai.util.embedInCard
import com.nutrilens.ai.util.loadDish
import com.nutrilens.ai.util.loadMeal
import com.nutrilens.ai.util.mealSaved
import com.nutrilens.ai.util.navigateSlide
import com.nutrilens.ai.util.render
import com.nutrilens.ai.util.show
import com.nutrilens.ai.util.showConfidence
import com.nutrilens.ai.util.showScoreBreakdownOnClick
import com.nutrilens.ai.util.toDisplayName
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Recognition result for the meal being logged: the selected item's prediction, confidence,
 * alternatives, portion and nutrition, plus the list of items in this meal and its slot.
 */
@AndroidEntryPoint
class ResultFragment : Fragment(R.layout.fragment_result) {

    private val viewModel: ScanViewModel by activityViewModels()

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!
    private lateinit var portionPicker: PortionPicker
    private var shownState: ScanUiState? = null
    /** Survives the view being recreated (fragment instance stays), unlike the binding. */
    private var revealed = false
    private var scanAnimator: AnimatorSet? = null

    private val slotChips by lazy {
        mapOf(
            MealSlot.BREAKFAST to binding.slotBreakfast, MealSlot.LUNCH to binding.slotLunch,
            MealSlot.SNACKS to binding.slotSnacks, MealSlot.DINNER to binding.slotDinner
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentResultBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnRetake.setOnClickListener { findNavController().popBackStack() }
        binding.btnRetakeQuality.setOnClickListener { findNavController().popBackStack() }
        binding.btnSave.setOnClickListener { saveMeal() }
        binding.btnDetails.setOnClickListener { findNavController().navigate(R.id.action_result_to_details) }
        binding.tvChange.setOnClickListener { FoodPickerSheet.open(this, KEY_CHANGE) }
        binding.btnPickFood.setOnClickListener { FoodPickerSheet.open(this, KEY_CHANGE) }
        binding.btnAddFood.setOnClickListener { FoodPickerSheet.open(this, KEY_ADD) }
        binding.btnMarkRegion.setOnClickListener { navigateSlide(R.id.regionFragment) }
        bindExpand(binding.rowCooking, binding.cookingContent, binding.chevCooking)
        bindExpand(binding.rowScore, binding.scoreCard.root, binding.chevScore)
        binding.scoreCard.embedInCard()
        slotChips.forEach { (slot, chip) -> chip.setOnClickListener { viewModel.setSlot(slot) } }
        portionPicker = PortionPicker(
            binding.portionPicker,
            onPortion = viewModel::choosePortion,
            onStep = viewModel::stepQuantity,
            onGrams = viewModel::setGrams
        )

        binding.chipStyleHomestyle.setOnClickListener {
            binding.root.haptic(Haptic.TICK)
            viewModel.setCookingStyle(com.nutrilens.ai.domain.CookingStyle.HOMESTYLE)
        }
        binding.chipStyleStandard.setOnClickListener {
            binding.root.haptic(Haptic.TICK)
            viewModel.setCookingStyle(com.nutrilens.ai.domain.CookingStyle.STANDARD)
        }
        binding.chipStyleRestaurant.setOnClickListener {
            binding.root.haptic(Haptic.TICK)
            viewModel.setCookingStyle(com.nutrilens.ai.domain.CookingStyle.RESTAURANT)
        }

        FoodPickerSheet.listen(this, KEY_CHANGE) { viewModel.selectFood(it) }
        FoodPickerSheet.listen(this, KEY_ADD) { viewModel.addFood(it) }

        collectOnStarted {
            launch { viewModel.state.collect { render(it) } }
            launch { viewModel.busy.collect { _binding?.regionProgress?.isVisible = it } }
        }
    }

    private fun render(state: ScanUiState) {
        // Idle comes right after saving, while this screen is leaving: keep showing the result
        // instead of blanking it for a frame.
        if (state is ScanUiState.Idle && shownState != null) return
        val previous = shownState
        shownState = state
        binding.loading.isVisible = state is ScanUiState.Loading
        binding.contentScroll.isVisible = state is ScanUiState.Success
        binding.saveBar.isVisible = state is ScanUiState.Success
        binding.errorPanel.isVisible = state is ScanUiState.Error
        if (state is ScanUiState.Loading) { startScanning(); revealed = false } else stopScanning()

        when (state) {
            is ScanUiState.Success -> {
                bind(state)
                // First result of this scan: the photo stays where it was, the rest rises in.
                // (Not again when coming back from Details, or when the portion changes.)
                if (!revealed) { revealResult(); revealed = true }
            }
            is ScanUiState.Error -> {
                binding.tvError.text = if (state.arg != null) getString(state.message, state.arg) else getString(state.message)
                if (previous !is ScanUiState.Error) binding.errorPanel.riseIn()
            }
            else -> Unit
        }
    }

    /** The photo being analysed, with a scanning beam sweeping over it. */
    private fun startScanning() {
        if (scanAnimator != null) return
        viewModel.photoUri?.let { binding.imgScanning.loadMeal(it.toString()) }
        binding.tvAnalyzing.alpha = 1f
        binding.scanFrame.doOnLayout { frame ->
            val line = _binding?.scanLine ?: return@doOnLayout
            scanAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(line, View.TRANSLATION_Y, -line.height.toFloat(), (frame.height - line.height).toFloat()).apply {
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                    },
                    ObjectAnimator.ofFloat(binding.tvAnalyzing, View.ALPHA, 1f, 0.45f).apply {
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                    }
                )
                duration = 1100
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopScanning() {
        scanAnimator?.cancel()
        scanAnimator = null
    }

    private fun revealResult() {
        binding.contentScroll.scrollTo(0, 0)
        binding.contentColumn.children.drop(1).filter { it.isVisible }.take(8)
            .forEachIndexed { i, v -> v.riseIn(delay = 60L + i * 50) }
        binding.chipConfidence.popIn(delay = 160)
        binding.root.haptic(Haptic.TICK)
    }

    private fun bind(state: ScanUiState.Success) {
        val meal = state.meal
        val item = state.item
        binding.imgMeal.loadMeal(meal.imageUri, meal.foodLabel)
        val name = requireContext().mealName(meal)
        binding.tvName.text = if (state.lowConfidence) getString(R.string.not_sure_name_fmt, name) else name
        binding.tvCuisineBadge.isVisible = false
        binding.chipConfidence.showConfidence(meal.confidence)
        binding.tvPersonalized.isVisible = item.personalized
        val issues = state.quality?.issues.orEmpty()
        binding.qualityBanner.isVisible = issues.isNotEmpty()
        binding.tvQuality.text = issues.joinToString("\n") { getString(it.message) }
        // Weak guess: pick from photo cards; otherwise the other guesses as small chips.
        binding.guessPanel.isVisible = item.unsure
        if (item.unsure) bindGuesses(item.guesses, item.food.label)
        bindAlternatives(if (item.unsure) emptyList() else state.alternatives)
        bindItems(state)
        slotChips[state.slot]?.isChecked = true
        binding.btnMarkRegion.isVisible = viewModel.photoUri != null

        portionPicker.bind(item.portion)
        val styleChipId = when (item.cookingStyle) {
            com.nutrilens.ai.domain.CookingStyle.HOMESTYLE -> R.id.chipStyleHomestyle
            com.nutrilens.ai.domain.CookingStyle.STANDARD -> R.id.chipStyleStandard
            com.nutrilens.ai.domain.CookingStyle.RESTAURANT -> R.id.chipStyleRestaurant
        }
        binding.chipsCookingStyle.check(styleChipId)
        binding.tvCookingSummary.text = binding.root.findViewById<Chip>(styleChipId).text

        binding.tvServing.text = getString(R.string.serving_info_fmt, item.portion.text, meal.servingGrams)
        binding.tvCal.countTo(meal.calories)
        binding.macroProtein.show(R.color.macro_protein, getString(R.string.fmt_g, meal.protein.roundToInt()), R.string.protein)
        binding.macroCarbs.show(R.color.macro_carbs, getString(R.string.fmt_g, meal.carbs.roundToInt()), R.string.carbs)
        binding.macroFat.show(R.color.macro_fat, getString(R.string.fmt_g, meal.fat.roundToInt()), R.string.fat)
        binding.macroFiber.show(R.color.macro_fiber, getString(R.string.fmt_g, meal.fiber.roundToInt()), R.string.fiber)
        binding.tvExtraNutrients.text = listOfNotNull(
            meal.sugar?.let { getString(R.string.sugar_fmt, it.roundToInt()) },
            meal.satFat?.let { getString(R.string.sat_fat_fmt, "%.1f".format(it)) },
            meal.sodiumMg?.let { getString(R.string.sodium_fmt, it.roundToInt()) }
        ).joinToString("  ·  ")

        val score = NutritionScore.evaluate(meal)
        binding.scoreCard.render(score)
        binding.tvScoreSummary.text = getString(R.string.score_summary_fmt, score.score, getString(score.headline))
        binding.scoreCard.showScoreBreakdownOnClick(score)
        binding.btnSave.isEnabled = true
        binding.btnSave.text = if (state.items.size > 1) getString(R.string.save_meal_items_fmt, state.items.size) else getString(R.string.save_meal)
    }

    /** The meal's items; the selected one is edited above. Tap to select, × to remove. */
    private fun bindItems(state: ScanUiState.Success) {
        binding.tvMealTotal.countTo(state.totalCalories) { getString(R.string.fmt_kcal, it) }
        binding.tvBarTotal.countTo(state.totalCalories) { getString(R.string.fmt_kcal, it) }
        binding.tvBarItems.text = state.items.joinToString(", ") { requireContext().foodName(it.food.label) }
        binding.itemsList.removeAllViews()
        state.items.forEachIndexed { i, it ->
            val row = ItemDraftBinding.inflate(layoutInflater, binding.itemsList, false)
            val itemName = requireContext().foodName(it.food.label)
            row.tvItemName.text = if (it.fromRegion) getString(R.string.item_marked_fmt, itemName) else itemName
            row.tvItemInfo.text = getString(R.string.item_info_fmt, it.portion.text, it.toMeal(null, 0, null, null).calories)
            row.root.isSelected = i == state.selected
            row.root.alpha = if (i == state.selected) 1f else 0.75f
            row.indicator.isVisible = i == state.selected
            row.root.setOnClickListener { viewModel.selectItem(i) }
            row.btnRemove.isVisible = state.items.size > 1
            row.btnRemove.setOnClickListener { viewModel.removeItem(i) }
            binding.itemsList.addView(row.root)
        }
    }

    /** The top guesses as photo cards; the one currently chosen is outlined. */
    private fun bindGuesses(guesses: List<Pair<String, Float>>, selected: String) {
        val row = binding.guessRow
        row.removeAllViews()
        guesses.forEachIndexed { i, (label, score) ->
            val card = ItemGuessCardBinding.inflate(layoutInflater, row, false)
            val name = requireContext().foodName(label)
            card.name.text = name
            card.pct.text = getString(R.string.guess_pct_fmt, confidencePercent(score) ?: 0)
            card.img.loadDish(label)
            val chosen = label == selected
            card.card.strokeWidth = resources.getDimensionPixelSize(if (chosen) R.dimen.stroke_selected else R.dimen.stroke_hairline)
            card.card.strokeColor = ContextCompat.getColor(requireContext(), if (chosen) R.color.brand_green else R.color.outline)
            card.card.isSelected = chosen
            card.card.setOnClickListener { if (!chosen) { it.haptic(Haptic.TICK); viewModel.selectFood(label) } }
            if (i > 0) (card.root.layoutParams as ViewGroup.MarginLayoutParams).marginStart = resources.getDimensionPixelSize(R.dimen.guess_gap)
            row.addView(card.root)
        }
    }

    /** The model's other top guesses as one-tap corrections. */
    private fun bindAlternatives(alternatives: List<Pair<String, Float>>) {
        val group = binding.chipsAlternatives
        group.removeAllViews()
        group.isVisible = alternatives.isNotEmpty()
        alternatives.forEach { (label, score) ->
            group.addView(Chip(requireContext()).apply {
                text = getString(R.string.alt_chip_fmt, context.foodName(label), confidencePercent(score) ?: 0)
                dishIcon(label)
                setOnClickListener { viewModel.selectFood(label) }
            })
        }
    }

    private fun saveMeal() {
        val meal = viewModel.state.value as? ScanUiState.Success ?: return
        binding.btnSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ids = viewModel.save()
                mealSaved(meal.items.map { it.food.label }, meal.totalCalories, ids)
            } catch (e: Exception) {
                _binding?.btnSave?.isEnabled = true
                context?.let { Toast.makeText(it, R.string.save_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() {
        stopScanning()
        shownState = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_CHANGE = "result_change_food"
        const val KEY_ADD = "result_add_food"
    }
}
