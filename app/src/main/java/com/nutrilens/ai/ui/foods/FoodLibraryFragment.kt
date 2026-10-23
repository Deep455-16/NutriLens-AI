package com.nutrilens.ai.ui.foods

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.databinding.FragmentFoodLibraryBinding
import com.nutrilens.ai.databinding.ItemFoodLibraryBinding
import com.nutrilens.ai.domain.FoodFilter
import com.nutrilens.ai.domain.FoodSort
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.openFood
import com.nutrilens.ai.util.showTag
import com.nutrilens.ai.util.cascadeInOnce
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.loadDish
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt

/** Browse the whole nutrition dataset: search, filter, sort; tap a food to see, compare or log it. */
@AndroidEntryPoint
class FoodLibraryFragment : Fragment(R.layout.fragment_food_library) {

    private val viewModel: FoodLibraryViewModel by viewModels()

    private var _binding: FragmentFoodLibraryBinding? = null
    private val binding get() = _binding!!

    /** The meal slot being filled when opened from a Home "+" (passed on to the food). */
    private val slot get() = arguments?.getString("slot")?.let { runCatching { MealSlot.valueOf(it) }.getOrNull() }

    private val adapter = FoodAdapter { food, photo -> openFood(food.label, photo, slot?.name) }

    private val chipFilters = mapOf(
        R.id.chipAllFoods to FoodFilter.ALL,
        R.id.chipIndian to FoodFilter.INDIAN,
        R.id.chipHighProtein to FoodFilter.HIGH_PROTEIN,
        R.id.chipLowCalorie to FoodFilter.LOW_CALORIE,
        R.id.chipHighFiber to FoodFilter.HIGH_FIBER,
        R.id.chipBestScore to FoodFilter.BEST_SCORE
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentFoodLibraryBinding.bind(view)
        binding.rvFoods.adapter = adapter
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.etSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        slot?.let { binding.tvSubtitle.text = getString(R.string.adding_to_slot_fmt, getString(it.label)) }

        val initialFilter = arguments?.getString("filter")
        if (initialFilter == "INDIAN") {
            binding.chipFilters.check(R.id.chipIndian)
            viewModel.filter.value = FoodFilter.INDIAN
        }

        binding.chipFilters.setOnCheckedStateChangeListener { _, ids ->
            viewModel.filter.value = chipFilters[ids.firstOrNull()] ?: FoodFilter.ALL
        }
        binding.btnSort.setOnClickListener { chooseSort() }

        collectOnStarted {
            viewModel.items.collect { items ->
                if (items == null) return@collect
                adapter.submitList(items) { _binding?.rvFoods?.run { scrollToPosition(0); cascadeInOnce() } }
                binding.tvEmpty.isVisible = items.isEmpty()
                binding.tvCount.text = getString(R.string.library_count_fmt,
                    resources.getQuantityString(R.plurals.foods_count, items.size, items.size),
                    getString(viewModel.sort.value.label).lowercase())
            }
        }
    }

    private fun chooseSort() {
        val sorts = FoodSort.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(sorts.map { getString(it.label) }.toTypedArray(), sorts.indexOf(viewModel.sort.value)) { d, which ->
                viewModel.sort.value = sorts[which]
                d.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        binding.rvFoods.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private class FoodAdapter(private val onClick: (NutritionEntity, ImageView) -> Unit) :
        ListAdapter<NutritionEntity, FoodAdapter.Holder>(Diff) {

        class Holder(val b: ItemFoodLibraryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFoodLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val food = getItem(position)
            holder.b.tvName.text = holder.b.root.context.foodName(food.label)
            holder.b.imgDish.loadDish(food.label)
            holder.b.tvInfo.text =
                holder.b.root.context.getString(R.string.library_item_fmt, food.calories, food.servingGrams, food.protein.roundToInt(), food.fiber.roundToInt())
            holder.b.tvScore.text = NutritionScore.evaluate(food).score.toString()
            holder.b.tvTag.showTag(NutritionScore.tags(food).first())
            holder.b.tvCuisineBadge.isVisible = false
            holder.b.root.setOnClickListener { onClick(food, holder.b.imgDish) }
        }

        private object Diff : DiffUtil.ItemCallback<NutritionEntity>() {
            override fun areItemsTheSame(a: NutritionEntity, b: NutritionEntity) = a.label == b.label
            override fun areContentsTheSame(a: NutritionEntity, b: NutritionEntity) = a == b
        }
    }
}
