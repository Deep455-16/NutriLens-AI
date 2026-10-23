package com.nutrilens.ai.ui.details

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.databinding.FragmentDetailsBinding
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.domain.PortionChoice
import com.nutrilens.ai.domain.mealSlot
import com.nutrilens.ai.ui.common.FoodPickerSheet
import com.nutrilens.ai.ui.common.PortionPicker
import com.nutrilens.ai.ui.common.ScanUiState
import com.nutrilens.ai.ui.common.ScanViewModel
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.dailyValueNote
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.loadMeal
import com.nutrilens.ai.util.pickMealTime
import com.nutrilens.ai.util.render
import com.nutrilens.ai.util.renderNutritionLabel
import com.nutrilens.ai.util.showConfidence
import com.nutrilens.ai.util.showScoreBreakdownOnClick
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Nutrition details for either a stored meal (argument `mealId` >= 0)
 * or the meal that was just scanned (`mealId` = -1).
 */
@AndroidEntryPoint
class DetailsFragment : Fragment(R.layout.fragment_details) {

    private val detailsViewModel: DetailsViewModel by viewModels()
    private val scanViewModel: ScanViewModel by activityViewModels()

    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!

    private var shownMeal: MealEntity? = null
    private var storedMeal = false
    private var portion: PortionChoice? = null
    private lateinit var portionPicker: PortionPicker

    private val slotChips by lazy {
        mapOf(
            MealSlot.BREAKFAST to binding.slotBreakfast, MealSlot.LUNCH to binding.slotLunch,
            MealSlot.SNACKS to binding.slotSnacks, MealSlot.DINNER to binding.slotDinner
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDetailsBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnShare.setOnClickListener { shareMeal() }
        binding.btnSuggestions.setOnClickListener { openSuggestions() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        val mealId = arguments?.getLong("mealId", -1L) ?: -1L
        storedMeal = mealId >= 0
        binding.btnDelete.isVisible = storedMeal
        binding.editCard.isVisible = storedMeal
        portionPicker = PortionPicker(
            binding.portionPicker,
            onPortion = { i -> portion?.let { applyPortion(it.select(i)) } },
            onStep = { up -> portion?.let { applyPortion(it.step(up)) } },
            onGrams = { g -> portion?.let { applyPortion(it.withGrams(g)) } }
        )
        slotChips.forEach { (slot, chip) -> chip.setOnClickListener { applySlot(slot) } }
        binding.btnChangeFood.setOnClickListener { FoodPickerSheet.open(this) }
        FoodPickerSheet.listen(this) { label -> changeFood(label) }
        if (storedMeal) {
            binding.tvScanned.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_edit, 0)
            TextViewCompat.setCompoundDrawableTintList(
                binding.tvScanned,
                ColorStateList.valueOf(MaterialColors.getColor(binding.tvScanned, com.google.android.material.R.attr.colorOnSurfaceVariant))
            )
            binding.tvScanned.setOnClickListener { editTime() }
            viewLifecycleOwner.lifecycleScope.launch {
                val meal = detailsViewModel.load(mealId)
                if (meal == null) {
                    findNavController().popBackStack()
                } else {
                    portion = detailsViewModel.portionOf(meal)
                    bindMeal(meal)
                    val mates = detailsViewModel.mealMates(meal)
                    _binding?.tvGroup?.isVisible = mates.isNotEmpty()
                    _binding?.tvGroup?.text = getString(R.string.group_fmt, mates.joinToString(", "))
                }
            }
        } else {
            collectOnStarted {
                scanViewModel.state.collect { state ->
                    if (state is ScanUiState.Success) {
                        portion = state.item.portion
                        bindMeal(state.meal)
                    }
                }
            }
        }
    }

    private fun bindMeal(meal: MealEntity) {
        shownMeal = meal
        binding.imgMeal.loadMeal(meal.imageUri, meal.foodLabel)
        binding.tvName.text = requireContext().mealName(meal)
        binding.chipConfidence.showConfidence(meal.confidence)
        binding.tvScanned.text = getString(R.string.eaten_at_fmt, TimeUtils.dateTimeText(meal.timestamp), getString(meal.mealSlot.label))

        val dv = detailsViewModel.dailyValues.value
        binding.factsLabel.renderNutritionLabel(meal, dv)
        binding.tvServingInfo.text = getString(R.string.portion_fmt, meal.serving ?: getString(R.string.standard_serving), meal.servingGrams)
        if (meal.sodiumMg == null) {
            // Saved before the USDA dataset (DB v1): its numbers came from the older estimates.
            binding.tvSource.setText(R.string.old_meal_note)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val source = detailsViewModel.sourceOf(meal.foodLabel)
                _binding?.tvSource?.text = (source?.let { getString(R.string.source_fmt, it) + " " } ?: "") +
                    getString(R.string.typical_values) + " " + requireContext().dailyValueNote(dv)
            }
        }

        val score = NutritionScore.evaluate(meal)
        binding.scoreCard.render(score)
        binding.scoreCard.showScoreBreakdownOnClick(score)
        portion?.let(portionPicker::bind)
        slotChips[meal.mealSlot]?.isChecked = true
    }

    private fun editTime() {
        val meal = shownMeal ?: return
        pickMealTime(meal.timestamp) { time ->
            viewLifecycleOwner.lifecycleScope.launch {
                val updated = detailsViewModel.setTime(meal, time)
                _binding?.let {
                    bindMeal(updated)
                    Snackbar.make(it.root, R.string.time_updated, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Saves a new portion for the stored meal and shows the result. */
    private fun applyPortion(newPortion: PortionChoice) {
        val meal = shownMeal ?: return
        if (newPortion == portion) return
        viewLifecycleOwner.lifecycleScope.launch {
            val updated = detailsViewModel.edit(meal, newPortion) ?: return@launch
            portion = newPortion
            _binding?.let {
                bindMeal(updated)
                Snackbar.make(it.root, R.string.meal_updated, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun changeFood(label: String) {
        val meal = shownMeal ?: return
        if (label == meal.foodLabel) return
        viewLifecycleOwner.lifecycleScope.launch {
            val (updated, newPortion) = detailsViewModel.changeFood(meal, label) ?: return@launch
            portion = newPortion
            _binding?.let {
                bindMeal(updated)
                Snackbar.make(it.root, R.string.meal_updated, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySlot(slot: MealSlot) {
        val meal = shownMeal ?: return
        if (!storedMeal || meal.mealSlot == slot) return
        viewLifecycleOwner.lifecycleScope.launch {
            val updated = detailsViewModel.setSlot(meal, slot)
            _binding?.let { bindMeal(updated) }
        }
    }

    private fun openSuggestions() {
        val meal = shownMeal ?: return
        findNavController().navigate(
            R.id.action_details_to_suggestions,
            bundleOf("label" to meal.foodLabel, "imageUri" to meal.imageUri)
        )
    }

    private fun confirmDelete() {
        val meal = shownMeal ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_meal_title)
            .setMessage(getString(R.string.delete_meal_msg_fmt, requireContext().mealName(meal)))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    detailsViewModel.delete(meal)
                    findNavController().popBackStack()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareMeal() {
        val meal = shownMeal ?: return
        val name = requireContext().mealName(meal)
        val score = NutritionScore.evaluate(meal)
        val text = getString(
            R.string.share_meal_text,
            name,
            meal.calories,
            meal.protein.roundToInt(),
            meal.carbs.roundToInt(),
            meal.fat.roundToInt(),
            score.score
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NutriLens AI: $name")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.cd_share_meal)))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
