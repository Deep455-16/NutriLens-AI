package com.nutrilens.ai.ui.day

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.FragmentDayBinding
import com.nutrilens.ai.databinding.ItemMealBinding
import com.nutrilens.ai.domain.InsightsCalculator
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.addRow
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.dp
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.loadMeal
import com.nutrilens.ai.util.navigateSlide
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything eaten on one day, grouped by meal slot, with totals against the user's goals. */
@AndroidEntryPoint
class DayFragment : Fragment(R.layout.fragment_day) {

    private val viewModel: DayViewModel by viewModels()

    private var _binding: FragmentDayBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDayBinding.bind(view)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnPrev.setOnClickListener { viewModel.shift(-1) }
        binding.btnNext.setOnClickListener { viewModel.shift(1) }
        collectOnStarted { viewModel.state.collect { it?.let(::render) } }
    }

    private fun render(s: DayUiState) {
        binding.tvDay.text = when {
            s.isToday -> getString(R.string.today)
            s.dayStart == TimeUtils.startOfDayOffset(-1) -> getString(R.string.yesterday)
            else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(s.dayStart))
        }
        binding.btnNext.isEnabled = !s.isToday
        binding.btnNext.alpha = if (s.isToday) 0.3f else 1f

        binding.tvCalories.text = getString(R.string.fmt_kcal, s.calories)
        binding.tvGoal.text = getString(R.string.of_goal_fmt, s.calorieGoal)
        val pct = if (s.calorieGoal > 0) s.calories * 100 / s.calorieGoal else 0
        binding.progressCalories.setProgressCompat(pct.coerceIn(0, 100), true)
        binding.tvStatus.isVisible = s.meals.isNotEmpty()
        binding.tvStatus.text = when {
            abs(s.calories - s.calorieGoal) <= s.calorieGoal * InsightsCalculator.ON_TARGET_TOLERANCE -> getString(R.string.on_target)
            s.calories < s.calorieGoal -> getString(R.string.kcal_left_fmt, s.calorieGoal - s.calories)
            else -> getString(R.string.kcal_over_fmt, s.calories - s.calorieGoal)
        }

        binding.totalsRows.removeAllViews()
        binding.totalsRows.addRow("", getString(R.string.eaten), getString(R.string.target), header = true)
        binding.totalsRows.addRow(getString(R.string.protein), getString(R.string.fmt_g, s.protein.roundToInt()), getString(R.string.fmt_g, s.targets.protein))
        binding.totalsRows.addRow(getString(R.string.carbs), getString(R.string.fmt_g, s.carbs.roundToInt()), getString(R.string.fmt_g, s.targets.carbs))
        binding.totalsRows.addRow(getString(R.string.fat), getString(R.string.fmt_g, s.fat.roundToInt()), getString(R.string.fmt_g, s.targets.fat))
        binding.totalsRows.addRow(getString(R.string.fiber), getString(R.string.fmt_g, s.fiber.roundToInt()), "–")

        val container = binding.slotsContainer
        container.removeAllViews()
        for ((slot, meals) in s.bySlot) {
            container.addView(TextView(requireContext(), null, 0, R.style.NL_Section).apply {
                text = getString(R.string.slot_kcal_fmt, getString(slot.label), meals.sumOf { it.calories })
                updatePadding(top = requireContext().dp(18), bottom = requireContext().dp(8))
            })
            for (meal in meals) {
                val row = ItemMealBinding.inflate(layoutInflater, container, false)
                row.tvName.text = requireContext().mealName(meal)
                row.tvInfo.text = getString(R.string.meal_kcal_time_fmt, meal.calories, TimeUtils.timeText(meal.timestamp))
                row.tvDate.text = getString(R.string.score_fmt, NutritionScore.evaluate(meal).score)
                row.imgMeal.loadMeal(meal.imageUri, meal.foodLabel)
                row.root.setOnClickListener {
                    navigateSlide(R.id.detailsFragment, bundleOf("mealId" to meal.id))
                }
                container.addView(row.root)
            }
        }
        binding.tvEmpty.isVisible = s.meals.isEmpty()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
