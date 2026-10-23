package com.nutrilens.ai.ui.region

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.FragmentRegionBinding
import com.nutrilens.ai.ui.common.ScanViewModel
import com.nutrilens.ai.util.ImageUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the user box one food on the scanned photo; it is recognised and added to the meal. */
@AndroidEntryPoint
class RegionFragment : Fragment(R.layout.fragment_region) {

    private val scanViewModel: ScanViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRegionBinding.bind(view)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnCancel.setOnClickListener { findNavController().popBackStack() }
        binding.btnRecognise.setOnClickListener {
            scanViewModel.analyzeRegion(binding.selector.region)
            findNavController().popBackStack()
        }

        val uri = scanViewModel.photoUri ?: run { findNavController().popBackStack(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(requireContext(), uri, maxDim = 1600) }
            binding.selector.bitmap = bmp
            if (savedInstanceState == null) scanViewModel.suggestRegion()?.let { binding.selector.region = it }
        }
    }
}
