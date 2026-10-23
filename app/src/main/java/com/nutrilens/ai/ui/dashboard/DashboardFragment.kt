package com.nutrilens.ai.ui.dashboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.databinding.FragmentDashboardBinding
import com.nutrilens.ai.databinding.ItemMealBinding
import com.nutrilens.ai.databinding.LayoutStatTileBinding
import com.nutrilens.ai.domain.DailyValues
import com.nutrilens.ai.domain.DayStatus
import com.nutrilens.ai.domain.DayTotal
import com.nutrilens.ai.domain.InsightsCalculator
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.addRow
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.dp
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.loadMeal
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.navigateSlide
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Progress over the last 7 or 30 days (MPAndroidChart): streak and averages, a daily bar chart
 * against the user's goal or limit, the macro split, a 5-week calendar and eating patterns.
 */
@AndroidEntryPoint
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private val viewModel: DashboardViewModel by viewModels()

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    /** What the charts last drew, so they animate only when the data changes. */
    private var lastDrawn: Any? = null

    private val metricChips = mapOf(
        R.id.chipCalories to Metric.CALORIES, R.id.chipProtein to Metric.PROTEIN,
        R.id.chipCarbs to Metric.CARBS, R.id.chipFat to Metric.FAT,
        R.id.chipSodium to Metric.SODIUM, R.id.chipSugar to Metric.SUGAR
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDashboardBinding.bind(view)

        setupBarChart(binding.barChart)
        setupPieChart(binding.pieChart)

        binding.togglePeriod.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.setPeriod(if (id == R.id.btn30Days) 30 else 7)
        }
        binding.chipMetrics.setOnCheckedStateChangeListener { _, ids ->
            viewModel.setMetric(metricChips[ids.firstOrNull()] ?: Metric.CALORIES)
        }
        binding.btnDashScan.setOnClickListener { navigateSlide(R.id.cameraFragment) }

        binding.barChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val day = viewModel.state.value.days.getOrNull(e?.x?.toInt() ?: return) ?: return
                binding.barChart.highlightValues(null)
                navigateSlide(R.id.dayFragment, bundleOf("dayStart" to day.dayStart))
            }
            override fun onNothingSelected() = Unit
        })

        collectOnStarted { viewModel.state.collect { render(it) } }
    }

    private fun onSurface() = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)

    private fun setupBarChart(chart: BarChart) {
        val textColor = onSurface()
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.setScaleEnabled(false)
        // Only a tap opens a day; a drag over the chart scrolls the page instead.
        chart.isDragEnabled = false
        chart.isHighlightPerDragEnabled = false
        chart.setNoDataText(getString(R.string.no_meals_period))
        chart.setNoDataTextColor(textColor)
        chart.setDrawGridBackground(false)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            this.textColor = textColor
        }
        chart.axisLeft.apply {
            axisMinimum = 0f
            this.textColor = textColor
            gridColor = ColorUtils.setAlphaComponent(textColor, 40)
            setDrawAxisLine(false)
        }
    }

    private fun setupPieChart(chart: PieChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawEntryLabels(false)
        chart.setTouchEnabled(false)
        chart.isDrawHoleEnabled = true
        chart.holeRadius = 62f
        chart.transparentCircleRadius = 0f
        chart.setHoleColor(Color.TRANSPARENT)
        chart.setNoDataText(getString(R.string.no_meals_logged_yet))
        chart.setNoDataTextColor(onSurface())
    }

    private fun render(s: DashboardUiState) {
        binding.tvRange.text = s.rangeText
        val i = s.insights
        // Nothing in the period: one clear next step instead of empty charts (neither while loading).
        binding.dashContent.isVisible = i != null && s.mealCount > 0
        binding.dashEmpty.isVisible = i != null && s.mealCount == 0
        if (i == null || s.mealCount == 0) return
        val animate = lastDrawn != s
        lastDrawn = s

        binding.tvStreak.text = i.streak.toString()
        binding.tvStreakHint.text = when (i.streak) {
            0 -> getString(R.string.streak_none)
            1 -> getString(R.string.streak_one)
            else -> getString(R.string.streak_many_fmt, i.streak)
        }
        val numbers = NumberFormat.getIntegerInstance()
        tile(binding.tileDays, getString(R.string.fraction_fmt, i.daysLogged, i.days), getString(R.string.tile_days_label))
        tile(binding.tileOnTarget, "${i.daysOnTarget}", getString(R.string.tile_on_target_fmt, s.calorieGoal))
        tile(binding.tileCalories, numbers.format(i.avgCalories), getString(R.string.tile_avg_kcal))
        tile(binding.tileProtein, getString(R.string.fmt_g, i.avgProtein), getString(R.string.tile_avg_protein_fmt, s.targets.protein))

        bindChart(s, animate)
        bindMacros(s, animate)
        bindCalendar(s)

        binding.topFoods.removeAllViews()
        i.topFoods.forEachIndexed { rank, (label, count) ->
            binding.topFoods.addRow("${rank + 1}. ${requireContext().foodName(label)}", resources.getQuantityString(R.plurals.times_count, count, count))
        }
        binding.tvBestTitle.text = getString(R.string.best_meal_fmt, i.avgScore)
        mealRow(binding.bestMeal, i.best)
        val showWorst = i.worst != null && i.worst != i.best
        binding.tvWorstTitle.isVisible = showWorst
        binding.worstMeal.isVisible = showWorst
        mealRow(binding.worstMeal, i.worst.takeIf { showWorst })

        val total = i.slotCounts.values.sum().coerceAtLeast(1)
        binding.slotRows.removeAllViews()
        for ((slot, count) in i.slotCounts) binding.slotRows.addView(slotRow(getString(slot.label), count, count * 100 / total))
    }

    /** The goal or limit the chart's dashed line shows for [metric], with its label. */
    private fun limitFor(s: DashboardUiState): Pair<Float, String> = when (s.metric) {
        Metric.CALORIES -> s.calorieGoal.toFloat() to getString(R.string.limit_goal_kcal_fmt, s.calorieGoal)
        Metric.PROTEIN -> s.targets.protein.toFloat() to getString(R.string.limit_target_g_fmt, s.targets.protein)
        Metric.CARBS -> s.targets.carbs.toFloat() to getString(R.string.limit_target_g_fmt, s.targets.carbs)
        Metric.FAT -> s.targets.fat.toFloat() to getString(R.string.limit_target_g_fmt, s.targets.fat)
        Metric.SODIUM -> DailyValues.SODIUM_MG to getString(R.string.limit_sodium)
        Metric.SUGAR -> SUGAR_LIMIT_G to getString(R.string.limit_sugar)
    }

    /**
     * One bar per day of the period with the goal as a dashed line. Calorie bars are coloured
     * like the calendar (green near the goal, amber under, red over); protein is green once the
     * target is reached; sodium and sugar turn red above their limit.
     */
    private fun bindChart(s: DashboardUiState, animate: Boolean) {
        binding.tvChartTitle.text = getString(R.string.chart_title_fmt, getString(s.metric.label), s.metric.unit)
        val values = s.days.map { s.metric.value(it) }
        val chart = binding.barChart
        chart.axisLeft.removeAllLimitLines()
        val known = values.filterNotNull()
        if (known.isEmpty()) {
            chart.clear()
            binding.tvSummary.text = getString(R.string.trend_no_data_fmt, getString(s.metric.label).lowercase())
            return
        }
        val (limit, limitLabel) = limitFor(s)
        val entries = values.mapIndexed { i, v -> BarEntry(i.toFloat(), v ?: 0f) }
        val colors = s.days.zip(values).map { (day, v) ->
            when {
                v == null -> color(R.color.brand_green)
                s.metric == Metric.CALORIES -> color(when (InsightsCalculator.status(day, s.calorieGoal)) {
                    DayStatus.UNDER -> R.color.macro_carbs
                    DayStatus.OVER -> R.color.macro_fat
                    else -> R.color.brand_green
                })
                s.metric == Metric.PROTEIN -> color(if (v >= limit) R.color.brand_green else R.color.macro_carbs)
                s.metric == Metric.SODIUM || s.metric == Metric.SUGAR -> color(if (v > limit) R.color.macro_fat else R.color.brand_green)
                else -> color(R.color.brand_green)
            }
        }
        chart.axisLeft.addLimitLine(LimitLine(limit, limitLabel).apply {
            lineColor = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurfaceVariant)
            lineWidth = 1.2f
            enableDashedLine(12f, 8f, 0f)
            textColor = lineColor
            textSize = 10f
        })
        chart.axisLeft.axisMaximum = maxOf(limit, known.max()) * 1.15f
        chart.xAxis.labelCount = minOf(s.days.size, 7)
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(s.days.map {
            if (s.days.size <= 7) TimeUtils.dayShort(it.dayStart) else SimpleDateFormat("d", Locale.getDefault()).format(Date(it.dayStart))
        })
        chart.data = BarData(BarDataSet(entries, getString(s.metric.label)).apply {
            this.colors = colors
            setDrawValues(false)
        }).apply { barWidth = if (s.days.size <= 7) 0.55f else 0.7f }
        if (animate) chart.animateY(500) else chart.invalidate()

        val logged = known.size
        binding.tvSummary.text = when (s.metric) {
            Metric.CALORIES -> getString(R.string.trend_cal_fmt, known.count { it > limit * (1 + InsightsCalculator.ON_TARGET_TOLERANCE) }, logged)
            Metric.PROTEIN -> getString(R.string.trend_protein_fmt, known.count { it >= limit }, logged)
            Metric.SODIUM -> getString(R.string.trend_sodium_fmt, known.count { it > limit }, logged)
            Metric.SUGAR -> getString(R.string.trend_sugar_fmt, known.count { it > limit }, logged)
            Metric.CARBS, Metric.FAT -> getString(R.string.trend_avg_fmt, (known.sum() / logged).toInt(), limit.toInt())
        }
    }

    /** Share of energy from protein, carbs and fat over the period. */
    private fun bindMacros(s: DashboardUiState, animate: Boolean) {
        val pie = PieDataSet(listOf(
            PieEntry(s.proteinKcal, getString(R.string.protein)),
            PieEntry(s.carbsKcal, getString(R.string.carbs)),
            PieEntry(s.fatKcal, getString(R.string.fat))
        ), "").apply {
            colors = listOf(color(R.color.macro_protein), color(R.color.macro_carbs), color(R.color.macro_fat))
            setDrawValues(false)
            sliceSpace = 2f
        }
        binding.pieChart.data = PieData(pie)
        if (animate) binding.pieChart.animateY(700, Easing.EaseInOutQuad) else binding.pieChart.invalidate()
        val (p, c, f) = NutritionScore.macroPercents(s.proteinKcal, s.carbsKcal, s.fatKcal)
        binding.tvProteinPct.text = getString(R.string.percent_fmt, p)
        binding.tvCarbsPct.text = getString(R.string.percent_fmt, c)
        binding.tvFatPct.text = getString(R.string.percent_fmt, f)
        binding.tvMacroNote.text = getString(R.string.share_of_energy_fmt, s.period)
    }

    /** 5 weeks of days, coloured by how close the day's calories were to the goal. */
    private fun bindCalendar(s: DashboardUiState) {
        val grid = binding.calendarGrid
        grid.removeAllViews()
        val ctx = requireContext()
        val size = (resources.displayMetrics.widthPixels - ctx.dp(88)) / 7
        val colors = mapOf(
            DayStatus.ON_TARGET to R.color.brand_green,
            DayStatus.UNDER to R.color.macro_carbs,
            DayStatus.OVER to R.color.macro_fat
        )
        val today = TimeUtils.startOfDayOffset(0)
        val variant = MaterialColors.getColor(grid, com.google.android.material.R.attr.colorOnSurfaceVariant)
        // Weekday initials over the 7 columns (the grid starts on whatever weekday is 34 days ago).
        s.calendar.take(7).forEach { d ->
            grid.addView(TextView(ctx).apply {
                text = SimpleDateFormat("EEEEE", Locale.getDefault()).format(Date(d.dayStart))
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(variant)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, GridLayout.LayoutParams().apply { width = size; height = ctx.dp(20); setMargins(2, 2, 2, 2) })
        }
        for (d in s.calendar) {
            val status = InsightsCalculator.status(d, s.calorieGoal)
            grid.addView(TextView(ctx).apply {
                text = SimpleDateFormat("d", Locale.getDefault()).format(Date(d.dayStart))
                gravity = Gravity.CENTER
                textSize = 12f
                val bg = colors[status]
                if (bg != null) {
                    setBackgroundResource(R.drawable.bg_circle_soft)
                    backgroundTintList = ColorStateList.valueOf(color(bg))
                    setTextColor(color(R.color.on_accent))
                } else {
                    setTextColor(variant)
                }
                if (d.dayStart == today) setTypeface(typeface, Typeface.BOLD)
                contentDescription = getString(R.string.cal_day_cd_fmt, TimeUtils.dateText(d.dayStart), when (status) {
                    DayStatus.NONE -> getString(R.string.cal_none)
                    DayStatus.ON_TARGET -> getString(R.string.cal_on_target_fmt, d.calories)
                    DayStatus.UNDER -> getString(R.string.cal_under_fmt, d.calories)
                    DayStatus.OVER -> getString(R.string.cal_over_fmt, d.calories)
                })
                if (d.logged) setOnClickListener { navigateSlide(R.id.dayFragment, bundleOf("dayStart" to d.dayStart)) }
            }, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(2, 2, 2, 2) })
        }
        binding.tvCalendarLegend.text = getString(R.string.cal_legend_fmt,
            TimeUtils.rangeText(s.calendar.first().dayStart, s.calendar.last().dayStart), s.calorieGoal)
    }

    private fun tile(tile: LayoutStatTileBinding, value: String, label: String) {
        tile.tvValue.text = value
        tile.tvLabel.text = label
    }

    private fun mealRow(holder: FrameLayout, meal: MealEntity?) {
        holder.removeAllViews()
        meal ?: return
        val row = ItemMealBinding.inflate(layoutInflater, holder, true)
        row.tvName.text = requireContext().mealName(meal)
        row.tvInfo.text = getString(R.string.meal_kcal_score_fmt, meal.calories, NutritionScore.evaluate(meal).score)
        row.tvDate.text = TimeUtils.dateTimeText(meal.timestamp)
        row.imgMeal.loadMeal(meal.imageUri, meal.foodLabel)
        row.root.setOnClickListener { navigateSlide(R.id.detailsFragment, bundleOf("mealId" to meal.id)) }
    }

    private fun slotRow(label: String, count: Int, percent: Int): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            addView(TextView(ctx, null, 0, R.style.NL_Body).apply { text = label },
                LinearLayout.LayoutParams(ctx.dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearProgressIndicator(ctx).apply {
                max = 100
                trackCornerRadius = ctx.dp(3)
                trackThickness = ctx.dp(6)
                setProgressCompat(percent, false)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx, null, 0, R.style.NL_Value).apply {
                text = count.toString()
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(ctx.dp(36), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onDestroyView() {
        lastDrawn = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** Added-sugar guide used as the sugar line (WHO: under 10% of 2,000 kcal ≈ 50 g). */
        const val SUGAR_LIMIT_G = 50f
    }
}
