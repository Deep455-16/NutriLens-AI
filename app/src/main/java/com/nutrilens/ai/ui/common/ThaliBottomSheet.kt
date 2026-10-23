package com.nutrilens.ai.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.nutrilens.ai.MainActivity
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.local.toMeal
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.databinding.SheetThaliBuilderBinding
import com.nutrilens.ai.domain.ThaliDish
import com.nutrilens.ai.domain.ThaliPreset
import com.nutrilens.ai.domain.ThaliPresets
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.haptic
import dagger.hilt.android.AndroidEntryPoint
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Logs a common thali (several dishes) in one go. Each dish uses the dataset's own standard
 * serving, so the labels and the totals always agree. The dishes share one meal group.
 */
@AndroidEntryPoint
class ThaliBottomSheet : BottomSheetDialogFragment() {

    @Inject lateinit var nutrition: NutritionRepository
    @Inject lateinit var mealRepo: MealRepository

    private var _binding: SheetThaliBuilderBinding? = null
    private val binding get() = _binding!!

    private var preset: ThaliPreset = ThaliPresets.ALL[0]
    private val included = mutableSetOf<String>()
    private val foods = mutableMapOf<String, NutritionEntity>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetThaliBuilderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED

        ThaliPresets.ALL.forEachIndexed { i, p ->
            binding.chipsThaliPresets.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                setText(p.name)
                isCheckable = true
                isChecked = i == 0
                setOnClickListener { select(p) }
            })
        }
        binding.btnLogThali.setOnClickListener { log() }
        select(ThaliPresets.ALL[0])
    }

    private fun select(p: ThaliPreset) {
        preset = p
        included.clear()
        included.addAll(p.dishes.map { it.label })
        viewLifecycleOwner.lifecycleScope.launch {
            p.dishes.forEach { d -> if (d.label !in foods) nutrition.find(d.label)?.let { foods[d.label] = it } }
            renderDishes()
            renderTotal()
        }
    }

    private fun renderDishes() {
        val list = binding.layoutThaliDishes
        list.removeAllViews()
        preset.dishes.forEach { dish ->
            val food = foods[dish.label] ?: return@forEach
            list.addView(MaterialCheckBox(requireContext()).apply {
                text = getString(
                    R.string.thali_dish_fmt,
                    context.foodName(dish.label) + " — " + food.serving,
                    servings.format(dish.servings),
                    kcal(food, dish)
                )
                isChecked = dish.label in included
                minHeight = (56 * resources.displayMetrics.density).roundToInt()
                setPadding(paddingLeft, paddingTop, (16 * resources.displayMetrics.density).roundToInt(), paddingBottom)
                setOnCheckedChangeListener { v, checked ->
                    if (checked) included += dish.label else included -= dish.label
                    v.haptic(Haptic.TICK)
                    renderTotal()
                }
            })
        }
    }

    private fun renderTotal() {
        val chosen = preset.dishes.filter { it.label in included }.mapNotNull { d -> foods[d.label]?.let { it to d } }
        val kcal = chosen.sumOf { (f, d) -> kcal(f, d) }
        binding.tvThaliCalories.text = kcal.toString()
        binding.tvThaliMacros.text = getString(
            R.string.thali_macros_fmt,
            chosen.sumOf { (f, d) -> (f.protein * d.servings).toDouble() }.roundToInt(),
            chosen.sumOf { (f, d) -> (f.carbs * d.servings).toDouble() }.roundToInt(),
            chosen.sumOf { (f, d) -> (f.fat * d.servings).toDouble() }.roundToInt()
        )
        binding.btnLogThali.text = getString(
            R.string.thali_log_fmt, resources.getQuantityString(R.plurals.dishes_count, chosen.size, chosen.size), kcal
        )
        binding.btnLogThali.isEnabled = chosen.isNotEmpty()
    }

    private fun log() {
        val chosen = preset.dishes.filter { it.label in included }.mapNotNull { d -> foods[d.label]?.let { it to d } }
        if (chosen.isEmpty()) return
        binding.btnLogThali.isEnabled = false
        val now = System.currentTimeMillis()
        val slot = when (binding.chipsThaliSlot.checkedChipId) {
            R.id.thaliSlotBreakfast -> MealSlot.BREAKFAST
            R.id.thaliSlotDinner -> MealSlot.DINNER
            else -> MealSlot.LUNCH
        }
        val meals = chosen.map { (food, dish) ->
            food.toMeal(confidence = MealEntity.MANUAL_CONFIDENCE, imageUri = null, timestamp = now, servings = dish.servings)
                .copy(slot = slot.name, groupId = now)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = mealRepo.insertAll(meals)
            (activity as? MainActivity)?.showMealSaved(resources.getQuantityString(R.plurals.thali_logged, meals.size, meals.size), ids)
            dismiss()
        }
    }

    private fun kcal(food: NutritionEntity, dish: ThaliDish) = (food.calories * dish.servings).roundToInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private val servings = DecimalFormat("0.#")

        fun open(fragment: Fragment) {
            ThaliBottomSheet().show(fragment.parentFragmentManager, "ThaliBottomSheet")
        }
    }
}
