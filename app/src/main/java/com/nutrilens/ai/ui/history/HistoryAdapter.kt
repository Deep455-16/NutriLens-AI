package com.nutrilens.ai.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.databinding.ItemDayHeaderBinding
import com.nutrilens.ai.databinding.ItemMealBinding
import com.nutrilens.ai.domain.HistoryRow
import com.nutrilens.ai.ui.common.bindMeal
import com.nutrilens.ai.util.TimeUtils
import java.text.NumberFormat

/** History list: a header per day ("Today · 3 meals · 1,005 kcal") followed by that day's meals. */
class HistoryAdapter(private val onClick: (MealEntity) -> Unit) :
    ListAdapter<HistoryRow, RecyclerView.ViewHolder>(Diff) {

    class DayHolder(val b: ItemDayHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class MealHolder(val b: ItemMealBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is HistoryRow.Day) DAY else MEAL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == DAY) DayHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        else MealHolder(ItemMealBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Day -> (holder as DayHolder).b.run {
                val ctx = root.context
                tvDay.text = when (row.dayStart) {
                    TimeUtils.startOfDayOffset(0) -> ctx.getString(R.string.today)
                    TimeUtils.startOfDayOffset(-1) -> ctx.getString(R.string.yesterday)
                    else -> TimeUtils.dateText(row.dayStart)
                }
                tvDayTotal.text = ctx.getString(
                    R.string.day_total_fmt,
                    ctx.resources.getQuantityString(R.plurals.meals_count, row.meals, row.meals),
                    NumberFormat.getIntegerInstance().format(row.calories)
                )
            }
            is HistoryRow.Meal -> (holder as MealHolder).b.bindMeal(row.meal, onClick, showDate = false)
        }
    }

    /** Only meal rows can be swiped away. */
    fun mealAt(position: Int): MealEntity? = (currentList.getOrNull(position) as? HistoryRow.Meal)?.meal

    private object Diff : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(a: HistoryRow, b: HistoryRow) = when {
            a is HistoryRow.Day && b is HistoryRow.Day -> a.dayStart == b.dayStart
            a is HistoryRow.Meal && b is HistoryRow.Meal -> a.meal.id == b.meal.id
            else -> false
        }
        override fun areContentsTheSame(a: HistoryRow, b: HistoryRow) = a == b
    }

    private companion object {
        const val DAY = 0
        const val MEAL = 1
    }
}
