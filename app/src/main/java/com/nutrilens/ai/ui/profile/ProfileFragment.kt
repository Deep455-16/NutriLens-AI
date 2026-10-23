package com.nutrilens.ai.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nutrilens.ai.R
import com.nutrilens.ai.data.prefs.UserSettings
import com.nutrilens.ai.databinding.FragmentProfileBinding
import com.nutrilens.ai.domain.GoalNames
import com.nutrilens.ai.domain.MacroSplit
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.dp
import com.nutrilens.ai.util.navigateSlide
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat

/** Profile & settings: personal info and preferences stored with DataStore. */
@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private var current = UserSettings()

    private val goals = GoalNames.ALL

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.setNotifications(false)
                toast(getString(R.string.notif_denied))
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentProfileBinding.bind(view)

        binding.headerCard.setOnClickListener {
            editText(getString(R.string.your_name), current.name) { viewModel.setName(it) }
        }
        binding.rowAge.setOnClickListener {
            editNumber(getString(R.string.age), current.age, 5, 120) { viewModel.setAge(it) }
        }
        binding.rowHeight.setOnClickListener {
            editNumber(getString(R.string.height_cm), current.heightCm, 80, 250) { viewModel.setHeight(it) }
        }
        binding.rowWeight.setOnClickListener {
            editNumber(getString(R.string.weight_kg), current.weightKg, 20, 300) { viewModel.setWeight(it) }
        }
        binding.rowGoal.setOnClickListener { chooseGoal() }
        binding.rowMacroSplit.setOnClickListener { chooseMacroSplit() }
        binding.rowCalorieGoal.setOnClickListener {
            editNumber(getString(R.string.daily_calorie_goal_kcal), current.calorieGoal, 800, 6000) {
                viewModel.setCalorieGoal(it)
            }
        }

        // setOnClickListener fires only for user taps, so programmatic updates cannot loop.
        binding.swReminder.setOnClickListener {
            val on = binding.swReminder.isChecked
            viewModel.setDailyReminder(on)
            if (on) ensureNotificationPermission()
        }
        binding.swNotifications.setOnClickListener {
            val on = binding.swNotifications.isChecked
            viewModel.setNotifications(on)
            if (on) ensureNotificationPermission()
        }
        binding.swDarkMode.setOnClickListener {
            viewModel.setDarkMode(binding.swDarkMode.isChecked)
        }

        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnAbout.setOnClickListener { navigateSlide(R.id.aboutFragment) }

        collectOnStarted { viewModel.settings.collect { render(it) } }
    }

    private fun render(s: UserSettings) {
        current = s
        binding.tvName.text = s.name.ifBlank { getString(R.string.add_your_name) }
        binding.tvAge.text = s.age.toString()
        binding.tvHeight.text = getString(R.string.fmt_cm, s.heightCm)
        binding.tvWeight.text = getString(R.string.fmt_kg, s.weightKg)
        binding.tvGoal.setText(GoalNames.label(s.goal))
        binding.tvCalorieGoal.text = getString(R.string.fmt_kcal, s.calorieGoal)
        val t = s.macroSplit.targets(s.calorieGoal)
        binding.tvMacroSplit.text = getString(R.string.fmt_macro_targets, t.protein, t.carbs, t.fat)
        binding.tvSumKcal.text = NumberFormat.getIntegerInstance().format(s.calorieGoal)
        binding.tvSumGoal.setText(GoalNames.label(s.goal))
        binding.tvSumProtein.text = getString(R.string.fmt_g, t.protein)
        binding.swReminder.isChecked = s.dailyReminder
        binding.swNotifications.isChecked = s.notifications
        binding.swDarkMode.isChecked = s.darkMode
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun chooseGoal() {
        val selected = goals.indexOf(current.goal).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.goal)
            .setSingleChoiceItems(goals.map { getString(GoalNames.label(it)) }.toTypedArray(), selected) { dialog, which ->
                viewModel.setGoal(goals[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseMacroSplit() {
        val splits = MacroSplit.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.macro_split_title)
            .setSingleChoiceItems(splits.map { getString(it.label) }.toTypedArray(), splits.indexOf(current.macroSplit)) { dialog, which ->
                viewModel.setMacroSplit(splits[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editText(title: String, value: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(value)
            setSelection(text.length)
        }
        showInputDialog(title, input) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onSave(text)
        }
    }

    private fun editNumber(title: String, value: Int, min: Int, max: Int, onSave: (Int) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSelection(text.length)
        }
        showInputDialog(title, input) {
            val number = input.text.toString().toIntOrNull()
            if (number == null) toast(getString(R.string.enter_a_number)) else onSave(number.coerceIn(min, max))
        }
    }

    private fun showInputDialog(title: String, input: EditText, onConfirm: () -> Unit) {
        val container = FrameLayout(requireContext()).apply {
            val side = context.dp(24)
            setPadding(side, context.dp(8), side, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_msg)
            .setPositiveButton(R.string.clear) { _, _ ->
                viewModel.clearAllMeals()
                toast(getString(R.string.history_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
