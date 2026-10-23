package com.nutrilens.ai.ui.foods

import android.graphics.Color
import android.os.Bundle
import androidx.transition.Fade
import com.google.android.material.transition.MaterialContainerTransform
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.nutrilens.ai.R
import com.nutrilens.ai.util.DishPhotos
import com.nutrilens.ai.util.loadDish
import com.nutrilens.ai.data.local.PackagedFood
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.databinding.FragmentFoodDetailBinding
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.ui.common.FoodPickerSheet
import com.nutrilens.ai.ui.common.PortionPicker
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.addRow
import com.nutrilens.ai.util.bindExpand
import com.nutrilens.ai.util.embedInCard
import com.nutrilens.ai.util.show
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.dailyValueNote
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.haptic
import com.nutrilens.ai.util.ARG_SHARED_PHOTO
import com.nutrilens.ai.util.dishTransitionName
import com.nutrilens.ai.util.mealSaved
import com.nutrilens.ai.util.pickMealTime
import com.nutrilens.ai.util.render
import com.nutrilens.ai.util.renderNutritionLabel
import com.nutrilens.ai.util.showScoreBreakdownOnClick
import com.nutrilens.ai.util.showTag
import com.nutrilens.ai.util.toDisplayName
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One food from the dataset: facts for a chosen portion, score, comparison, and manual logging. */
@AndroidEntryPoint
class FoodDetailFragment : Fragment(R.layout.fragment_food_detail) {

    private val viewModel: FoodDetailViewModel by viewModels()

    private var _binding: FragmentFoodDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var portionPicker: PortionPicker

    private fun slotChips() = mapOf(
        MealSlot.BREAKFAST to binding.slotBreakfast, MealSlot.LUNCH to binding.slotLunch,
        MealSlot.SNACKS to binding.slotSnacks, MealSlot.DINNER to binding.slotDinner
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from a dish photo: that photo grows into the header, the rest fades in.
        if (arguments?.getBoolean(ARG_SHARED_PHOTO) == true) {
            sharedElementEnterTransition = MaterialContainerTransform().apply {
                drawingViewId = R.id.nav_host
                scrimColor = Color.TRANSPARENT
                duration = 380
            }
            enterTransition = Fade()
            returnTransition = Fade()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentFoodDetailBinding.bind(view)
        val label = requireArguments().getString("label").orEmpty()
        if (DishPhotos.has(requireContext(), label)) {
            // Show the photo now (not after the data loads) so the transition has its target.
            binding.imgHero.transitionName = dishTransitionName(label)
            binding.imgHero.isVisible = true
            postponeEnterTransition()
            binding.imgHero.loadDish(label) { startPostponedEnterTransition() }
            view.postDelayed({ startPostponedEnterTransition() }, MAX_TRANSITION_WAIT_MS)
        }

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        portionPicker = PortionPicker(binding.portionPicker, viewModel::choosePortion, viewModel::stepQuantity, viewModel::setGrams)
        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
        slotChips().forEach { (slot, chip) -> chip.setOnClickListener { viewModel.setSlot(slot) } }
        binding.btnCompare.setOnClickListener { FoodPickerSheet.open(this) }
        FoodPickerSheet.listen(this) { viewModel.compareWith(it) }
        binding.rowTime.setOnClickListener {
            pickMealTime(viewModel.state.value.eatenAt ?: System.currentTimeMillis()) { viewModel.setEatenAt(it) }
        }
        binding.btnLog.setOnClickListener { logMeal() }
        bindExpand(binding.rowCooking, binding.cookingContent, binding.chevCooking)
        bindExpand(binding.rowNutrients, binding.nutrientsContent, binding.chevNutrients)
        bindExpand(binding.rowScore, binding.scoreCard.root, binding.chevScore)
        binding.scoreCard.embedInCard()

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

        collectOnStarted {
            launch { viewModel.state.collect { render(it) } }
            launch {
                viewModel.isFavorite.collect { fav ->
                    _binding?.btnFavorite?.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
                    _binding?.btnFavorite?.contentDescription = getString(if (fav) R.string.fav_remove else R.string.fav_add)
                }
            }
            launch { viewModel.dailyValues.collect { viewModel.state.value.let(::render) } }
        }
    }

    private fun render(state: FoodDetailState) {
        val food = state.food ?: return
        val meal = state.preview ?: return

        binding.tvTitle.text = requireContext().foodName(food.label)
        val hasPhoto = DishPhotos.has(requireContext(), food.label)
        binding.imgHero.isVisible = hasPhoto
        if (hasPhoto && binding.imgHero.drawable == null) binding.imgHero.loadDish(food.label)
        binding.tvGrams.text = getString(R.string.approx_g_fmt, meal.servingGrams)
        binding.tvCal.text = meal.calories.toString()
        binding.tvServing.text = getString(R.string.serving_info_fmt, state.portion?.text ?: meal.serving.orEmpty(), meal.servingGrams)
        binding.macroProtein.show(R.color.macro_protein, getString(R.string.fmt_g, meal.protein.roundToInt()), R.string.protein)
        binding.macroCarbs.show(R.color.macro_carbs, getString(R.string.fmt_g, meal.carbs.roundToInt()), R.string.carbs)
        binding.macroFat.show(R.color.macro_fat, getString(R.string.fmt_g, meal.fat.roundToInt()), R.string.fat)
        binding.macroFiber.show(R.color.macro_fiber, getString(R.string.fmt_g, meal.fiber.roundToInt()), R.string.fiber)
        val tags = NutritionScore.tags(food)
        binding.tvTag.showTag(tags.first())
        binding.tvTags.text = tags.drop(1).joinToString(" · ") { getString(it.label) }
        binding.tvTags.isVisible = tags.size > 1
        // A packet's nutrition is what its label says: no oil/ghee adjustment, and say where it came from.
        val packaged = PackagedFood.isPackaged(food.label)
        binding.rowCooking.isVisible = !packaged
        binding.dividerCooking.isVisible = !packaged
        if (packaged) binding.cookingContent.isVisible = false
        binding.tvPackagedNote.isVisible = packaged
        state.portion?.let(portionPicker::bind)
        slotChips()[state.effectiveSlot]?.isChecked = true

        val styleChipId = when (state.cookingStyle) {
            com.nutrilens.ai.domain.CookingStyle.HOMESTYLE -> R.id.chipStyleHomestyle
            com.nutrilens.ai.domain.CookingStyle.STANDARD -> R.id.chipStyleStandard
            com.nutrilens.ai.domain.CookingStyle.RESTAURANT -> R.id.chipStyleRestaurant
        }
        binding.chipsCookingStyle.check(styleChipId)
        binding.tvCookingSummary.text = binding.root.findViewById<Chip>(styleChipId).text

        val dv = viewModel.dailyValues.value
        binding.factsRows.renderNutritionLabel(meal, dv)
        binding.tvSource.text = getString(R.string.source_fmt, food.source.ifBlank { getString(R.string.nutrition_dataset) }) + " " + requireContext().dailyValueNote(dv)

        val (p, c, f) = NutritionScore.macroPercents(meal.protein * 4f, meal.carbs * 4f, meal.fat * 9f)
        setWeight(binding.barProtein, p)
        setWeight(binding.barCarbs, c)
        setWeight(binding.barFat, f)
        binding.tvMacroLegend.text = getString(R.string.macro_legend_fmt, p, c, f)

        val score = NutritionScore.evaluate(meal)
        binding.scoreCard.render(score)
        binding.scoreCard.showScoreBreakdownOnClick(score)
        binding.tvScoreSummary.text = getString(R.string.score_summary_fmt, score.score, getString(score.headline))

        bindComparison(food, state.compareWith)
        binding.tvEatenAt.text = state.eatenAt?.let { TimeUtils.dateTimeText(it) } ?: getString(R.string.now)
        binding.logBar.isVisible = true
        binding.tvBarTotal.text = getString(R.string.fmt_kcal, meal.calories)
        binding.tvBarPortion.text = meal.serving?.let { getString(R.string.portion_option_fmt, it, meal.servingGrams) }
            ?: getString(R.string.fmt_g, meal.servingGrams)
    }

    private fun bindComparison(food: NutritionEntity, other: NutritionEntity?) {
        val table = binding.compareTable
        table.isVisible = other != null
        binding.btnCompare.setText(if (other == null) R.string.compare_another else R.string.compare_different)
        table.removeAllViews()
        if (other == null) return

        fun per100(v: Float, item: NutritionEntity) = v * 100f / item.servingGrams
        // Index 0 = this food, 1 = the other one; mark the better value of each row.
        fun better(a: Float, b: Float, higherIsBetter: Boolean) = when {
            a == b -> emptySet()
            (a > b) == higherIsBetter -> setOf(0)
            else -> setOf(1)
        }
        val rows = listOf(
            Triple(getString(R.string.cmp_calories), { e: NutritionEntity -> per100(e.calories.toFloat(), e) }, false),
            Triple(getString(R.string.cmp_protein), { e: NutritionEntity -> per100(e.protein, e) }, true),
            Triple(getString(R.string.cmp_carbs), { e: NutritionEntity -> per100(e.carbs, e) }, null),
            Triple(getString(R.string.cmp_fat), { e: NutritionEntity -> per100(e.fat, e) }, false),
            Triple(getString(R.string.cmp_fiber), { e: NutritionEntity -> per100(e.fiber, e) }, true)
        )
        table.addRow(getString(R.string.per_100_g), requireContext().foodName(food.label), requireContext().foodName(other.label), header = true)
        for ((label, value, higherIsBetter) in rows) {
            val a = value(food)
            val b = value(other)
            table.addRow(label, fmt(a), fmt(b), highlight = higherIsBetter?.let { better(a, b, it) } ?: emptySet())
        }
        val sa = NutritionScore.evaluate(food).score.toFloat()
        val sb = NutritionScore.evaluate(other).score.toFloat()
        table.addRow(getString(R.string.nutrition_score), sa.roundToInt().toString(), sb.roundToInt().toString(), highlight = better(sa, sb, true))
    }

    private fun logMeal() {
        binding.btnLog.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val meal = viewModel.state.value.preview
            val id = viewModel.log()
            if (meal != null && id != null) {
                mealSaved(listOf(meal.foodLabel), meal.calories, listOf(id))
            } else {
                _binding?.btnLog?.isEnabled = true
            }
        }
    }

    private fun setWeight(view: View, percent: Int) {
        view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply { weight = percent.toFloat() }
    }

    /** One decimal for small amounts (2.5 g), none for zero or large ones (0, 22). */
    private fun fmt(v: Float) = when {
        v == 0f -> "0"
        v < 10f -> "%.1f".format(v)
        else -> v.roundToInt().toString()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** Start the transition even if the photo is slow to load. */
        const val MAX_TRANSITION_WAIT_MS = 400L
    }
}
