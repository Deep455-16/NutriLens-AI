package com.nutrilens.ai.ui.history

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.databinding.FragmentHistoryBinding
import com.nutrilens.ai.domain.groupedByDay
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.MealSlot
import com.nutrilens.ai.util.cascadeInOnce
import com.nutrilens.ai.util.haptic
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val viewModel: HistoryViewModel by viewModels()

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = HistoryAdapter(
        onClick = { meal ->
            findNavController().navigate(R.id.action_history_to_details, bundleOf("mealId" to meal.id))
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHistoryBinding.bind(view)

        binding.rvMeals.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (vh is HistoryAdapter.MealHolder) super.getSwipeDirs(rv, vh) else 0
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val meal = adapter.mealAt(vh.bindingAdapterPosition) ?: return
                vh.itemView.haptic(Haptic.REJECT)
                deleteWithUndo(meal)
            }
        }).attachToRecyclerView(binding.rvMeals)

        binding.etSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val slot = when (checkedIds.firstOrNull()) {
                R.id.chipBreakfast -> MealSlot.BREAKFAST
                R.id.chipLunch -> MealSlot.LUNCH
                R.id.chipSnacks -> MealSlot.SNACKS
                R.id.chipDinner -> MealSlot.DINNER
                else -> null
            }
            viewModel.setSlot(slot)
        }

        binding.btnSort.setOnClickListener {
            val newest = viewModel.toggleSort()
            Toast.makeText(
                requireContext(),
                getString(if (newest) R.string.newest_first else R.string.oldest_first),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnEmptyScan.setOnClickListener {
            findNavController().navigate(R.id.cameraFragment)
        }

        collectOnStarted {
            viewModel.meals.collect { meals ->
                adapter.submitList(meals.groupedByDay()) { _binding?.rvMeals?.cascadeInOnce() }
                binding.emptyStateLayout.isVisible = meals.isEmpty()
                binding.tvEmpty.isVisible = meals.isEmpty()
                binding.rvMeals.isVisible = meals.isNotEmpty()
                // Nothing matches the search/slot filter: say so, rather than "no meals yet"
                val filtering = !binding.etSearch.text.isNullOrBlank() || binding.chipGroup.checkedChipId != R.id.chipAll
                binding.tvEmptyTitle.setText(if (filtering) R.string.no_match_title else R.string.empty_history_title)
                binding.tvEmpty.setText(if (filtering) R.string.no_match_sub else R.string.empty_history_sub)
                binding.btnEmptyScan.isVisible = !filtering
            }
        }
    }

    /** Swipe = delete at once, with a few seconds to undo (the photo is removed only after). */
    private fun deleteWithUndo(meal: MealEntity) {
        viewModel.remove(meal)
        Snackbar.make(binding.root, getString(R.string.deleted_fmt, requireContext().mealName(meal)), UNDO_MS)
            .setAction(R.string.undo) { v -> v.haptic(Haptic.CONFIRM); viewModel.restore(meal) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) viewModel.purgePhoto(meal)
                }
            })
            .show()
    }

    override fun onDestroyView() {
        binding.rvMeals.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** Long enough to notice and reach "Undo" (Material suggests 4–10 s for actions). */
        const val UNDO_MS = 6000
    }
}
