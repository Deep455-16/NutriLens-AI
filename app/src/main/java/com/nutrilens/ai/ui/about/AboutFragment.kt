package com.nutrilens.ai.ui.about

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nutrilens.ai.R
import com.nutrilens.ai.ai.FoodClassifier
import com.nutrilens.ai.databinding.FragmentAboutBinding
import com.nutrilens.ai.util.navigateSlide
import kotlin.math.roundToInt
import org.json.JSONObject

/** How recognition works, where the nutrition data comes from, and the app's limits. */
class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentAboutBinding.bind(view)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnFoods.setOnClickListener { navigateSlide(R.id.foodLibraryFragment) }
        modelSummary()?.let { binding.tvAccuracy.text = it }
    }

    /** Measured accuracy from assets/model_info.json (written by ml/calibrate.py). */
    private fun modelSummary(): String? = runCatching {
        val info = JSONObject(requireContext().assets.open(FoodClassifier.INFO_FILE).bufferedReader().use { it.readText() })
        val acc = info.getJSONObject("accuracy")
        fun pct(v: Double) = "${(v * 100).roundToInt()}%"
        acc.keys().asSequence().joinToString("\n", prefix = getString(R.string.accuracy_title) + "\n") { key ->
            val a = acc.getJSONObject(key)
            val name = if (key == "food101") getString(R.string.accuracy_food101)
                else if (key.startsWith("indian")) getString(R.string.accuracy_indian_fmt, key.removePrefix("indian").toIntOrNull() ?: 1)
                else getString(R.string.accuracy_set_fmt, key.replaceFirstChar { it.uppercase() })
            getString(R.string.accuracy_line_fmt, name, "%,d".format(a.getInt("images")), pct(a.getDouble("top1")), pct(a.getDouble("top3")))
        }
    }.getOrNull()
}
