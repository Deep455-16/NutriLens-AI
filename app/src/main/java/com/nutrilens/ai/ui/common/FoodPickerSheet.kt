package com.nutrilens.ai.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.data.repo.NutritionRepository
import com.nutrilens.ai.databinding.ItemFoodBinding
import com.nutrilens.ai.databinding.SheetFoodPickerBinding
import com.nutrilens.ai.R
import com.nutrilens.ai.domain.FoodFilter
import com.nutrilens.ai.domain.isIndianDish
import com.nutrilens.ai.domain.matchesQuery
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.loadDish
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Bottom sheet listing every food in the nutrition dataset with a search box and quick filters.
 * Hosts call [listen] in onViewCreated and [open] to show it; the picked label comes back
 * through the Fragment Result API, so it survives rotation.
 */
@AndroidEntryPoint
class FoodPickerSheet : BottomSheetDialogFragment() {

    @Inject lateinit var nutrition: NutritionRepository

    private var _binding: SheetFoodPickerBinding? = null
    private val binding get() = _binding!!

    private var all: List<NutritionEntity> = emptyList()
    private var activeFilter: FoodFilter = FoodFilter.ALL
    private val adapter = FoodAdapter { pick(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetFoodPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        binding.rvFoods.adapter = adapter
        binding.etSearch.doAfterTextChanged { filter(it?.toString().orEmpty()) }

        val requestedFilter = arguments?.getString(ARG_FILTER)
        if (requestedFilter == "INDIAN") {
            binding.pickerFilterChips.check(R.id.chipPickerIndian)
            activeFilter = FoodFilter.INDIAN
        }

        binding.pickerFilterChips.setOnCheckedStateChangeListener { _, ids ->
            activeFilter = when (ids.firstOrNull()) {
                R.id.chipPickerIndian -> FoodFilter.INDIAN
                R.id.chipPickerProtein -> FoodFilter.HIGH_PROTEIN
                R.id.chipPickerLight -> FoodFilter.LOW_CALORIE
                else -> FoodFilter.ALL
            }
            filter(binding.etSearch.text?.toString().orEmpty())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            all = nutrition.all().sortedBy { it.label }
            filter(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun filter(query: String) {
        val ctx = context ?: return
        val shown = all.filter { matchesQuery(it.label, ctx.foodName(it.label), query) && activeFilter.matches(it) }
        adapter.submitList(shown)
        binding.tvNoMatch.isVisible = shown.isEmpty() && all.isNotEmpty()
    }

    private fun pick(item: NutritionEntity) {
        setFragmentResult(requireArguments().getString(ARG_KEY, REQUEST_KEY), bundleOf(KEY_LABEL to item.label))
        dismiss()
    }

    override fun onDestroyView() {
        binding.rvFoods.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private class FoodAdapter(private val onClick: (NutritionEntity) -> Unit) :
        ListAdapter<NutritionEntity, FoodAdapter.Holder>(Diff) {

        class Holder(val b: ItemFoodBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFoodBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val ctx = holder.b.root.context
            holder.b.tvFood.text = ctx.foodName(item.label)
            holder.b.ivPhoto.isVisible = true
            holder.b.ivPhoto.loadDish(item.label)
            holder.b.tvFoodInfo.text = ctx.getString(
                R.string.library_item_fmt,
                item.calories,
                item.servingGrams,
                item.protein.roundToInt(),
                item.fiber.roundToInt()
            )
            holder.b.tvCuisine.isVisible = false
            holder.b.root.setOnClickListener { onClick(item) }
        }

        private object Diff : DiffUtil.ItemCallback<NutritionEntity>() {
            override fun areItemsTheSame(a: NutritionEntity, b: NutritionEntity) = a.label == b.label
            override fun areContentsTheSame(a: NutritionEntity, b: NutritionEntity) = a == b
        }
    }

    companion object {
        private const val REQUEST_KEY = "food_picker"
        private const val KEY_LABEL = "label"
        private const val TAG = "FoodPickerSheet"
        private const val ARG_KEY = "request_key"
        private const val ARG_FILTER = "filter"

        /**
         * Registers [onPicked] for results; call from the host's onViewCreated. Use a different
         * [key] per purpose when one screen opens the picker for different things.
         */
        fun listen(host: Fragment, key: String = REQUEST_KEY, onPicked: (String) -> Unit) {
            host.childFragmentManager.setFragmentResultListener(key, host.viewLifecycleOwner) { _, result ->
                result.getString(KEY_LABEL)?.let(onPicked)
            }
        }

        fun open(host: Fragment, key: String = REQUEST_KEY, filter: String? = null) {
            if (host.childFragmentManager.findFragmentByTag(TAG) == null) {
                FoodPickerSheet().apply {
                    arguments = bundleOf(ARG_KEY to key, ARG_FILTER to filter)
                }.show(host.childFragmentManager, TAG)
            }
        }
    }
}
