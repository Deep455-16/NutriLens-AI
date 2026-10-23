package com.nutrilens.ai.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.databinding.ItemMealBinding
import com.nutrilens.ai.R
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.loadMeal

/** Shared by the Home (recent meals) and History screens. */
class MealAdapter(
    private val onClick: (MealEntity) -> Unit,
    private val onLongClick: ((MealEntity) -> Unit)? = null
) : ListAdapter<MealEntity, MealAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val b: ItemMealBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(meal: MealEntity) = b.bindMeal(meal, onClick, onLongClick)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMealBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<MealEntity>() {
        override fun areItemsTheSame(a: MealEntity, b: MealEntity) = a.id == b.id
        override fun areContentsTheSame(a: MealEntity, b: MealEntity) = a == b
    }
}

/** One meal row (photo, name, kcal and time). [showDate] is off where a day header shows it. */
fun ItemMealBinding.bindMeal(
    meal: MealEntity,
    onClick: (MealEntity) -> Unit,
    onLongClick: ((MealEntity) -> Unit)? = null,
    showDate: Boolean = true
) {
    tvName.text = root.context.mealName(meal)
    tvCuisineBadge.isVisible = false
    tvInfo.text = root.context.getString(R.string.meal_kcal_time_fmt, meal.calories, TimeUtils.timeText(meal.timestamp))
    tvDate.isVisible = showDate
    tvDate.text = TimeUtils.dateText(meal.timestamp)
    imgMeal.loadMeal(meal.imageUri, meal.foodLabel)
    root.setOnClickListener { onClick(meal) }
    root.setOnLongClickListener {
        onLongClick?.invoke(meal)
        onLongClick != null
    }
}
