package com.nutrilens.ai.ui.onboarding

import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.textfield.TextInputLayout
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.FragmentOnboardingBinding
import com.nutrilens.ai.domain.Activity
import com.nutrilens.ai.domain.CalorieGoal
import com.nutrilens.ai.domain.GoalNames
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.domain.Sex
import com.nutrilens.ai.ui.profile.ProfileViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * First-launch setup in three steps: about you, activity and goal, then the suggested daily
 * calorie goal (which updates as you change things and can be edited).
 */
@AndroidEntryPoint
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private val viewModel: ProfileViewModel by viewModels()

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    /** Once the user types their own goal, stop overwriting it with suggestions. */
    private var caloriesEditedByUser = false
    private var settingCalories = false
    private var step = 1

    /** System Back goes to the previous step instead of leaving setup. */
    private val backToPreviousStep = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showStep(step - 1)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentOnboardingBinding.bind(view)

        listOf(binding.etAge, binding.etHeight, binding.etWeight).forEach { field ->
            field.doAfterTextChanged { updateSuggestion() }
        }
        binding.toggleSex.addOnButtonCheckedListener { _, _, checked -> if (checked) updateSuggestion() }
        binding.toggleActivity.addOnButtonCheckedListener { _, _, checked -> if (checked) updateSuggestion() }
        binding.toggleGoal.addOnButtonCheckedListener { _, _, checked -> if (checked) updateSuggestion() }
        binding.etCalories.doAfterTextChanged {
            if (!settingCalories) caloriesEditedByUser = true
            binding.caloriesLayout.error = null
            showGoal()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToPreviousStep)
        binding.btnStepBack.setOnClickListener { showStep(step - 1) }
        binding.btnStart.setOnClickListener {
            when {
                step == 1 -> if (aboutYouValid()) showStep(2)
                step < STEPS -> showStep(step + 1)
                else -> finish()
            }
        }
        binding.btnSkip.setOnClickListener {
            saveThenGoHome { viewModel.completeOnboarding("", 25, 170, 65, GoalNames.MAINTAIN, 2000) }
        }
        updateSuggestion()
        showStep(savedInstanceState?.getInt(KEY_STEP) ?: 1, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    private fun showStep(n: Int, animate: Boolean = true) {
        step = n.coerceIn(1, STEPS)
        if (animate) TransitionManager.beginDelayedTransition(binding.steps)
        binding.step1.isVisible = step == 1
        binding.step2.isVisible = step == 2
        binding.step3.isVisible = step == 3
        binding.progressSteps.setProgressCompat(step, animate)
        binding.tvStep.text = getString(R.string.step_fmt, step, STEPS)
        binding.btnStepBack.visibility = if (step > 1) View.VISIBLE else View.INVISIBLE
        binding.btnStart.setText(if (step == STEPS) R.string.get_started else R.string.continue_label)
        backToPreviousStep.isEnabled = step > 1
    }

    /** Age, height and weight are needed for the suggestion; marks the ones that are missing. */
    private fun aboutYouValid(): Boolean {
        val age = number(binding.ageLayout, AGE)
        val height = number(binding.heightLayout, HEIGHT)
        val weight = number(binding.weightLayout, WEIGHT)
        binding.ageLayout.error = if (age == null) getString(R.string.range_fmt, AGE.first, AGE.last) else null
        binding.heightLayout.error = if (height == null) getString(R.string.range_fmt, HEIGHT.first, HEIGHT.last) else null
        binding.weightLayout.error = if (weight == null) getString(R.string.range_fmt, WEIGHT.first, WEIGHT.last) else null
        return age != null && height != null && weight != null
    }

    /** The goal as a big number, with the protein/carbs/fat it works out to (balanced split). */
    private fun showGoal() {
        val kcal = number(binding.caloriesLayout, CalorieGoal.MIN..CalorieGoal.MAX)
        binding.tvGoalBig.text = kcal?.let { NumberFormat.getIntegerInstance().format(it) } ?: "–"
        binding.tvMacroTargets.isVisible = kcal != null
        kcal?.let { MacroSplit.BALANCED.targets(it) }?.let { t ->
            binding.tvMacroTargets.text = getString(R.string.macro_targets_fmt, t.protein, t.carbs, t.fat)
        }
    }

    private fun sex() = if (binding.toggleSex.checkedButtonId == R.id.btnFemale) Sex.FEMALE else Sex.MALE

    private fun activity() = when (binding.toggleActivity.checkedButtonId) {
        R.id.btnActivityLow -> Activity.LOW
        R.id.btnActivityHigh -> Activity.HIGH
        else -> Activity.MODERATE
    }

    private fun goal() = when (binding.toggleGoal.checkedButtonId) {
        R.id.btnGoalLose -> GoalNames.LOSE
        R.id.btnGoalGain -> GoalNames.GAIN
        else -> GoalNames.MAINTAIN
    }

    private fun number(layout: TextInputLayout, range: IntRange): Int? =
        layout.editText?.text?.toString()?.toIntOrNull()?.takeIf { it in range }

    private fun updateSuggestion() {
        binding.tvActivityHint.setText(activity().label)
        val age = number(binding.ageLayout, AGE)
        val height = number(binding.heightLayout, HEIGHT)
        val weight = number(binding.weightLayout, WEIGHT)
        if (age == null || height == null || weight == null) return

        val suggested = CalorieGoal.suggest(sex(), age, height, weight, activity(), goal())
        binding.tvCaloriesHint.text =
            getString(R.string.suggested_kcal_fmt, suggested)
        if (!caloriesEditedByUser) {
            settingCalories = true
            binding.etCalories.setText(suggested.toString())
            settingCalories = false
        }
    }

    private fun finish() {
        val age = number(binding.ageLayout, AGE)
        val height = number(binding.heightLayout, HEIGHT)
        val weight = number(binding.weightLayout, WEIGHT)
        val calories = number(binding.caloriesLayout, CalorieGoal.MIN..CalorieGoal.MAX)

        binding.ageLayout.error = if (age == null) getString(R.string.range_fmt, AGE.first, AGE.last) else null
        binding.heightLayout.error = if (height == null) getString(R.string.range_fmt, HEIGHT.first, HEIGHT.last) else null
        binding.weightLayout.error = if (weight == null) getString(R.string.range_fmt, WEIGHT.first, WEIGHT.last) else null
        binding.caloriesLayout.error =
            if (calories == null) getString(R.string.enter_kcal_range_fmt, CalorieGoal.MIN, CalorieGoal.MAX) else null
        if (age == null || height == null || weight == null || calories == null) return

        val name = binding.etName.text?.toString()?.trim().orEmpty()
        saveThenGoHome { viewModel.completeOnboarding(name, age, height, weight, goal(), calories) }
    }

    /** Navigating away clears this screen's scope, so wait for the write before leaving. */
    private fun saveThenGoHome(save: suspend () -> Unit) {
        binding.btnStart.isEnabled = false
        binding.btnSkip.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            save()
            findNavController().navigate(R.id.homeFragment, null, navOptions {
                popUpTo(R.id.onboardingFragment) { inclusive = true }
            })
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STEPS = 3
        const val KEY_STEP = "step"
        val AGE = 13..100
        val HEIGHT = 100..250
        val WEIGHT = 30..300
    }
}
