package com.nutrilens.ai.ui.suggestions

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.databinding.FragmentSuggestionsBinding
import com.nutrilens.ai.util.loadMeal
import com.nutrilens.ai.util.mealSaved
import com.nutrilens.ai.util.foodName
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Healthier alternatives from the local nutrition dataset, based on the current meal. */
@AndroidEntryPoint
class SuggestionsFragment : Fragment(R.layout.fragment_suggestions) {

    private val viewModel: SuggestionsViewModel by viewModels()

    private var _binding: FragmentSuggestionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = SuggestionAdapter { item -> confirmLog(item) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSuggestionsBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.rvSuggestions.adapter = adapter

        val label = arguments?.getString("label").orEmpty()
        val imageUri = arguments?.getString("imageUri")

        viewLifecycleOwner.lifecycleScope.launch {
            val current = viewModel.current(label)
            if (current != null) {
                binding.imgCurrent.loadMeal(imageUri, label)
                binding.tvCurrentName.text = requireContext().foodName(label)
                binding.tvCurrentKcal.text = getString(R.string.fmt_kcal, current.calories)
            }
            binding.currentCard.isVisible = current != null

            val alternatives = viewModel.alternatives(label)
            adapter.submitList(alternatives)
            binding.tvNoSuggestions.isVisible = alternatives.isEmpty()
        }
    }

    private fun confirmLog(item: NutritionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.log_food_title_fmt, requireContext().foodName(item.label)))
            .setMessage(getString(R.string.log_food_msg_fmt, requireContext().foodName(item.label), item.calories))
            .setPositiveButton(R.string.log_meal) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = viewModel.log(item)
                    mealSaved(listOf(item.label), item.calories, listOf(id))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.rvSuggestions.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
