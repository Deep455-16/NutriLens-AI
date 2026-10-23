package com.nutrilens.ai.ui.suggestions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.databinding.ItemSuggestionBinding
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.R
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.loadDish

class SuggestionAdapter(
    private val onChoose: (NutritionEntity) -> Unit
) : ListAdapter<NutritionEntity, SuggestionAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: NutritionEntity) {
            b.tvName.text = b.root.context.foodName(item.label)
            b.imgFood.loadDish(item.label)
            b.tvInfo.text = b.root.context.getString(R.string.kcal_tag_fmt, item.calories, b.root.context.getString(NutritionScore.tag(item)))
            b.btnChoose.setOnClickListener { onChoose(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<NutritionEntity>() {
        override fun areItemsTheSame(a: NutritionEntity, b: NutritionEntity) = a.label == b.label
        override fun areContentsTheSame(a: NutritionEntity, b: NutritionEntity) = a == b
    }
}
