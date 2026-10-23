package com.nutrilens.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.nutrilens.ai.data.prefs.UserPreferences
import com.nutrilens.ai.data.repo.MealRepository
import com.nutrilens.ai.databinding.ActivityMainBinding
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.haptic
import com.nutrilens.ai.work.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single-activity host. Screens are Fragments managed by the Navigation Component;
 * the bottom navigation bar is shown only on the four top-level destinations.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var meals: MealRepository

    private lateinit var binding: ActivityMainBinding

    private val topLevelDestinations = setOf(
        R.id.homeFragment,
        R.id.dashboardFragment,
        R.id.historyFragment,
        R.id.profileFragment
    )

    private val navController
        get() = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result handled lazily */ }

    /** Keeps the system splash up until we know whether first-launch setup is needed. */
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsLoaded }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setupWithNavController(navController)
        binding.fabScan.setOnClickListener { navController.navigate(R.id.cameraFragment) }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            showBottomNav(destination.id in topLevelDestinations)
        }
        lifecycleScope.launch {
            val needsSetup = !prefs.settings.first().onboarded
            if (savedInstanceState == null && needsSetup) {
                navController.navigate(R.id.onboardingFragment, null, navOptions {
                    popUpTo(R.id.homeFragment) { inclusive = true }
                })
            }
            settingsLoaded = true
        }

        observeSettings()
        askNotificationPermissionOnce()
    }

    private fun observeSettings() {
        // Apply the dark-mode preference
        lifecycleScope.launch {
            prefs.settings.map { it.darkMode }.distinctUntilChanged().collect { dark ->
                val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                if (AppCompatDelegate.getDefaultNightMode() != mode) {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }
        // Keep the daily reminder (WorkManager) in sync with the preference
        lifecycleScope.launch {
            prefs.settings.map { it.dailyReminder }.distinctUntilChanged().collect { enabled ->
                if (enabled) ReminderScheduler.schedule(applicationContext)
                else ReminderScheduler.cancel(applicationContext)
            }
        }
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        lifecycleScope.launch {
            if (!prefs.notifPermissionAsked.first()) {
                prefs.markNotifPermissionAsked()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * After a meal is logged: back to Home, where the ring shows the new total, with a short
     * confirmation and a few seconds to undo (which deletes the new meals and their photo).
     */
    fun showMealSaved(text: CharSequence, mealIds: List<Long>) {
        val nav = navController
        if (nav.currentDestination?.id != R.id.homeFragment && !nav.popBackStack(R.id.homeFragment, false)) {
            nav.navigate(R.id.homeFragment)
        }
        binding.root.haptic(Haptic.CONFIRM)
        Snackbar.make(binding.root, text, UNDO_MS)
            .setAnchorView(binding.fabScan)
            .setAction(R.string.undo) {
                lifecycleScope.launch { mealIds.mapNotNull { meals.getById(it) }.forEach { meals.delete(it) } }
            }
            .show()
    }

    /** Shows or hides the bottom bar on full-screen pages and the four main tabs. */
    private fun showBottomNav(show: Boolean) {
        val nav = binding.bottomBar
        if (nav.isVisible == show) return
        nav.animate().cancel()
        nav.translationY = 0f
        nav.isVisible = show
    }

    private companion object {
        /** Long enough to notice and reach "Undo" (Material suggests 4–10 s for actions). */
        const val UNDO_MS = 6000
    }
}
