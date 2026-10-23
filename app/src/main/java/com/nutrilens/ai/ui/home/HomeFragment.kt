package com.nutrilens.ai.ui.home

import android.Manifest
import com.nutrilens.ai.util.dishIcon
import com.nutrilens.ai.util.loadDish
import com.nutrilens.ai.util.DishPhotos
import com.nutrilens.ai.ui.camera.CameraFragment
import com.nutrilens.ai.databinding.ItemActionPillBinding
import com.nutrilens.ai.databinding.ItemSlotRowBinding
import com.nutrilens.ai.data.local.MealEntity
import com.nutrilens.ai.domain.mealSlot
import com.nutrilens.ai.util.MealSlot
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.annotation.ColorRes
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nutrilens.ai.R
import com.nutrilens.ai.data.local.NutritionEntity
import com.nutrilens.ai.databinding.FragmentHomeBinding
import com.nutrilens.ai.databinding.ItemFoodBinding
import com.nutrilens.ai.databinding.ItemHomeIndianFoodBinding
import com.nutrilens.ai.domain.NextMealSuggestion
import com.nutrilens.ai.domain.NutritionScore
import com.nutrilens.ai.ui.common.ThaliBottomSheet
import com.nutrilens.ai.util.TimeUtils
import com.nutrilens.ai.util.collectOnStarted
import com.nutrilens.ai.util.countTo
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.util.mealName
import com.nutrilens.ai.util.navigateSlide
import com.nutrilens.ai.util.openFood
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val viewModel: HomeViewModel by viewModels()

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var reminderOn = true
    private var lastFeaturedIndian: List<NutritionEntity>? = null
    private var lastQuickAdd: List<QuickFood>? = null
    private var lastNextMeal: List<NextMealSuggestion>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* worker re-checks */ }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)

        binding.tvViewAll.setOnClickListener { navigateSlide(R.id.dayFragment) }
        binding.btnReminder.setOnClickListener { showReminderDialog() }
        setUpActions()
        binding.tvSeeAllIndian.setOnClickListener {
            navigateSlide(R.id.foodLibraryFragment, bundleOf("filter" to "INDIAN"))
        }
        binding.todayCard.setOnClickListener { navigateSlide(R.id.dayFragment) }
        binding.tvDate.text = LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

        collectOnStarted { viewModel.state.collect { render(it) } }
    }

    /** The other ways to log, as compact tinted pills (photo scanning is the round button). */
    private fun setUpActions() {
        fun pill(p: ItemActionPillBinding, @DrawableRes icon: Int, @ColorRes tint: Int, @ColorRes iconTint: Int,
                 @StringRes title: Int, onClick: () -> Unit) {
            p.tile.setCardBackgroundColor(ContextCompat.getColor(requireContext(), tint))
            p.icon.setImageResource(icon)
            p.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), iconTint))
            p.title.setText(title)
            p.tile.setOnClickListener { onClick() }
        }
        pill(binding.actionBarcode, R.drawable.ic_barcode, R.color.tile_blue, R.color.macro_protein_fixed, R.string.action_short_barcode) {
            findNavController().navigate(R.id.action_home_to_camera, bundleOf(CameraFragment.ARG_BARCODE to true))
        }
        pill(binding.actionFind, R.drawable.ic_search, R.color.tile_amber, R.color.macro_carbs_fixed, R.string.action_short_find) {
            navigateSlide(R.id.foodLibraryFragment)
        }
        pill(binding.actionThali, R.drawable.ic_restaurant, R.color.tile_coral, R.color.macro_fat_fixed, R.string.action_short_thali) {
            ThaliBottomSheet.open(this)
        }
    }

    private fun render(state: HomeUiState) {
        val firstName = state.name.trim().substringBefore(' ')
        binding.tvGreeting.text =
            if (firstName.isEmpty()) getString(R.string.greeting_fmt, getString(TimeUtils.greeting()))
            else getString(R.string.greeting_name_fmt, getString(TimeUtils.greeting()), firstName)

        bindToday(state)
        bindMacro(binding.tvProtein, binding.progressProtein, state.protein, state.targets.protein)
        bindMacro(binding.tvCarbs, binding.progressCarbs, state.carbs, state.targets.carbs)
        bindMacro(binding.tvFat, binding.progressFat, state.fat, state.targets.fat)

        reminderOn = state.dailyReminder
        binding.btnReminder.alpha = if (reminderOn) 1f else 0.45f

        bindQuickAdd(state.quickAdd)
        bindNextMeal(state)
        bindIndianDishes(state.featuredIndian)

        bindSlots(state.today)
    }

    /** Breakfast, lunch, snacks and dinner: what was eaten and its calories; + adds food to that slot. */
    private fun bindSlots(today: List<MealEntity>) {
        val bySlot = today.groupBy { it.mealSlot }
        val numbers = NumberFormat.getIntegerInstance()
        binding.slotList.removeAllViews()
        MealSlot.entries.forEach { slot ->
            val meals = bySlot[slot].orEmpty().sortedBy { it.timestamp }
            val (icon, tint, iconTint) = SLOT_STYLE.getValue(slot)
            val row = ItemSlotRowBinding.inflate(layoutInflater, binding.slotList, false)
            val name = getString(slot.label)
            row.icon.setImageResource(icon)
            row.icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
            row.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), iconTint))
            row.title.text = name
            row.foods.text = if (meals.isEmpty()) getString(R.string.slot_nothing_yet)
                else meals.joinToString(", ") { requireContext().mealName(it) }
            row.kcal.isVisible = meals.isNotEmpty()
            row.kcal.text = getString(R.string.fmt_kcal_s, numbers.format(meals.sumOf { it.calories }))
            row.btnAdd.contentDescription = getString(R.string.cd_add_to_slot_fmt, name)
            row.btnAdd.setOnClickListener { navigateSlide(R.id.foodLibraryFragment, bundleOf("slot" to slot.name)) }
            row.row.setOnClickListener {
                if (meals.isEmpty()) navigateSlide(R.id.foodLibraryFragment, bundleOf("slot" to slot.name))
                else navigateSlide(R.id.dayFragment)
            }
            binding.slotList.addView(row.root)
        }
    }

    /** "1,022  of 2,300 kcal", a bar, and how much is left (or over). */
    private fun bindToday(state: HomeUiState) {
        val numbers = NumberFormat.getIntegerInstance()
        binding.tvCalories.countTo(state.calories) { numbers.format(it) }
        binding.tvGoal.text = getString(R.string.eaten_of_goal_fmt, numbers.format(state.calorieGoal))

        val left = state.calorieGoal - state.calories
        val withinTenPercent = state.calorieGoal > 0 && kotlin.math.abs(left) <= state.calorieGoal / 10
        binding.tvPercent.text = when {
            state.calories > 0 && withinTenPercent -> getString(R.string.on_target)
            left >= 0 -> getString(R.string.kcal_left_fmt, left)
            else -> getString(R.string.kcal_over_fmt, -left)
        }
        val over = left < 0 && !withinTenPercent
        binding.tvPercent.setTextColor(
            ContextCompat.getColor(requireContext(), if (over) R.color.warning else R.color.text_secondary)
        )
        binding.ring.setProgress(if (state.calorieGoal > 0) state.calories / state.calorieGoal.toFloat() else 0f, over)
    }

    /** "28 / 100 g" plus a bar; the bar caps at 100% but the text shows the real amount. */
    private fun bindMacro(text: TextView, bar: LinearProgressIndicator, eaten: Float, target: Int) {
        text.text = getString(R.string.eaten_of_target_fmt, eaten.roundToInt(), target)
        val pct = if (target > 0) (eaten * 100 / target).roundToInt() else 0
        bar.setProgressCompat(pct.coerceIn(0, 100), true)
    }

    /** Favourites (★) and frequent foods: tap to open, set the portion and log. */
    private fun bindQuickAdd(foods: List<QuickFood>) {
        if (lastQuickAdd == foods) return
        lastQuickAdd = foods
        binding.tvQuickTitle.isVisible = foods.isNotEmpty()
        binding.quickScroll.isVisible = foods.isNotEmpty()
        binding.chipsQuick.removeAllViews()
        foods.forEach { f ->
            val chip = Chip(requireContext()).apply {
                text = requireContext().foodName(f.label).let { if (f.favorite) getString(R.string.fav_chip_fmt, it) else it }
                isCheckable = false
                chipMinHeight = resources.displayMetrics.density * 40
                setOnClickListener { openFood(f.label) }
            }
            chip.dishIcon(f.label)
            binding.chipsQuick.addView(chip)
        }
    }

    private fun bindNextMeal(state: HomeUiState) {
        val left = state.calorieGoal - state.calories
        val show = state.nextMeal.isNotEmpty()
        binding.nextMealCard.isVisible = show
        binding.tvNextMealTitle.isVisible = show
        binding.tvNextMealHint.isVisible = show
        binding.tvNextMealHint.text =
            getString(R.string.next_meal_hint_fmt, left, (state.targets.protein - state.protein).roundToInt().coerceAtLeast(0))
        if (lastNextMeal == state.nextMeal) return
        lastNextMeal = state.nextMeal
        binding.nextMealList.removeAllViews()
        state.nextMeal.forEach { s ->
            val row = ItemFoodBinding.inflate(layoutInflater, binding.nextMealList, false)
            row.tvFood.text = requireContext().foodName(s.food.label)
            row.ivPhoto.isVisible = true
            row.ivPhoto.loadDish(s.food.label)
            row.tvFoodInfo.text = getString(R.string.next_meal_reason_fmt, s.protein, s.calories, s.food.serving)
            row.root.setOnClickListener { openFood(s.food.label, row.ivPhoto) }
            binding.nextMealList.addView(row.root)
        }
    }

    private fun bindIndianDishes(dishes: List<NutritionEntity>) {
        if (lastFeaturedIndian == dishes) return
        lastFeaturedIndian = dishes
        // Featured cards need a photo; dishes without one are still in the library.
        val withPhotos = dishes.filter { DishPhotos.has(requireContext(), it.label) }.take(10)
        binding.cardIndianDishes.isVisible = withPhotos.isNotEmpty()
        binding.layoutIndianDishes.removeAllViews()
        withPhotos.forEach { food ->
            val item = ItemHomeIndianFoodBinding.inflate(layoutInflater, binding.layoutIndianDishes, false)
            item.tvName.text = requireContext().foodName(food.label)
            item.tvServing.text = food.serving
            item.tvCalories.text = getString(R.string.fmt_kcal, food.calories)
            item.tvProtein.text = getString(R.string.protein_g_fmt, food.protein.roundToInt())
            item.tvScore.text = getString(R.string.score_fmt, NutritionScore.evaluate(food).score)
            item.imgDish.loadDish(food.label)
            item.root.setOnClickListener { openFood(food.label, item.imgDish) }
            binding.layoutIndianDishes.addView(item.root)
        }
    }

    private fun showReminderDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reminder_title)
            .setMessage(if (reminderOn) R.string.reminder_on else R.string.reminder_off)
            .setPositiveButton(if (reminderOn) R.string.turn_off else R.string.turn_on) { _, _ ->
                viewModel.setDailyReminder(!reminderOn)
                if (!reminderOn) askNotificationPermission()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroyView() {
        lastFeaturedIndian = null
        lastQuickAdd = null
        lastNextMeal = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** Icon, circle colour and icon colour for each slot (same tints as the Home actions). */
        val SLOT_STYLE = mapOf(
            MealSlot.BREAKFAST to Triple(R.drawable.ic_slot_breakfast, R.color.tile_amber, R.color.macro_carbs_fixed),
            MealSlot.LUNCH to Triple(R.drawable.ic_slot_lunch, R.color.tile_green, R.color.ring_end),
            MealSlot.SNACKS to Triple(R.drawable.ic_slot_snacks, R.color.tile_coral, R.color.macro_fat_fixed),
            MealSlot.DINNER to Triple(R.drawable.ic_slot_dinner, R.color.tile_blue, R.color.macro_protein_fixed)
        )
    }
}
