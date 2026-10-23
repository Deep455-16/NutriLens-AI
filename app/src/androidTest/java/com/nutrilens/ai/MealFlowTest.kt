package com.nutrilens.ai

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutrilens.ai.ui.common.ScanViewModel
import com.nutrilens.ai.util.foodName
import java.io.File
import java.util.Calendar
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end flows on the real app (real model, database and nutrition data):
 * scan a photo -> save -> edit -> swipe-delete + undo -> delete, and log a food without a photo
 * (then undo it from the confirmation on Home).
 * Food names are looked up in the app's current language, so the tests also pass in Hindi.
 */
@RunWith(AndroidJUnit4::class)
class MealFlowTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun start() {
        // Start from an empty history. The app's own Room instance is not observing yet.
        app.getDatabasePath("nutrilens.db").takeIf { it.exists() }?.let {
            SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.execSQL("DELETE FROM meals") }
        }
        // The app asks for notifications on first launch; that system dialog would cover the app.
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        skipOnboardingIfShown()
    }

    @After fun stop() = scenario.close()

    @Test fun scanSaveEditUndoDelete() {
        val pizza = name("pizza")
        val photo = File(app.cacheDir, "test_pizza.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("pizza.jpg").use { i -> photo.outputStream().use { i.copyTo(it) } }
        // Same hand-off as CameraFragment after a capture (the camera itself is not testable on CI).
        scenario.onActivity { a ->
            ViewModelProvider(a)[ScanViewModel::class.java].analyze(Uri.fromFile(photo))
            (a.supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController.navigate(R.id.resultFragment)
        }

        // Recognised and saved
        waitFor(allOf(withId(R.id.tvName), withText(pizza)), timeoutMs = 30_000)
        onView(withId(R.id.btnSave)).perform(clickAnyway())
        waitFor(withId(R.id.todayCard)) // saving returns to Home
        onView(withId(R.id.historyFragment)).perform(click())
        val row = allOf(withId(R.id.tvName), withText(pizza), isDescendantOfA(withId(R.id.rvMeals)))
        waitFor(row)

        // Edit: move it to a meal slot it was not saved in, then check the history filter agrees
        val morning = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 11
        val (slot, chip, otherChip) = if (morning) Triple(R.id.slotDinner, R.id.chipDinner, R.id.chipBreakfast)
        else Triple(R.id.slotBreakfast, R.id.chipBreakfast, R.id.chipDinner)
        val slotName = str(if (morning) R.string.dinner else R.string.breakfast)
        onView(row).perform(click())
        waitFor(withId(slot), visible)
        onView(withId(slot)).perform(clickAnyway())
        waitFor(allOf(withId(R.id.tvScanned), withText(containsString(slotName))), visible)
        pressBack()
        onView(withId(chip)).perform(scrollTo(), click())
        waitFor(row)
        onView(withId(otherChip)).perform(scrollTo(), click())
        waitGone(row)
        onView(withId(R.id.chipAll)).perform(scrollTo(), click())
        waitFor(row)

        // Swipe to delete, then undo
        onView(row).perform(swipeLeft())
        waitGone(row)
        waitFor(withText(R.string.undo))
        onView(withText(R.string.undo)).perform(click())
        waitFor(row)

        // Delete from the details screen
        onView(row).perform(click())
        waitFor(withId(R.id.btnDelete), visible)
        onView(withId(R.id.btnDelete)).perform(clickAnyway())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        waitFor(withId(R.id.tvEmpty)) // the only meal is gone, so History shows its empty state
        waitGone(row)
    }

    @Test fun logFoodWithoutPhotoThenUndo() {
        val idli = name("idli")
        onView(withId(R.id.actionFind)).perform(clickAnyway())
        waitFor(withId(R.id.etSearch))
        onView(withId(R.id.etSearch)).perform(replaceText(idli))
        val item = allOf(withId(R.id.tvName), withText(idli), isDescendantOfA(withId(R.id.rvFoods)))
        waitFor(item)
        onView(item).perform(click())
        waitFor(withId(R.id.btnLog), visible)
        onView(withId(R.id.btnLog)).perform(clickAnyway())

        // Back on Home: listed under today's meals, until "Undo" on the confirmation removes it
        val recent = allOf(withId(R.id.foods), withText(containsString(idli)), isDescendantOfA(withId(R.id.slotList)))
        waitFor(recent, visible) // the list may be below the fold
        onView(withText(R.string.undo)).perform(click())
        waitGone(recent)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private val visible: ViewAssertion = matches(withEffectiveVisibility(Visibility.VISIBLE))

    private fun name(label: String): String {
        var n = label
        scenario.onActivity { n = it.foodName(label) }
        return n
    }

    private fun str(id: Int): String {
        var s = ""
        scenario.onActivity { s = it.getString(id) }
        return s
    }

    private fun skipOnboardingIfShown() {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            if (shown(withId(R.id.todayCard))) return
            if (shown(withId(R.id.btnSkip))) { onView(withId(R.id.btnSkip)).perform(clickAnyway()); waitFor(withId(R.id.todayCard)); return }
            Thread.sleep(200)
        }
        waitFor(withId(R.id.todayCard))
    }

    private fun shown(m: Matcher<View>) = runCatching { onView(m).check(matches(isDisplayed())) }.isSuccess

    /** Espresso does not wait for coroutines on background threads (model, database), so poll. */
    private fun waitFor(m: Matcher<View>, check: ViewAssertion = matches(isDisplayed()), timeoutMs: Long = 10_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try { onView(m).check(check); return } catch (e: Throwable) {
                if (SystemClock.uptimeMillis() > end) throw e
                Thread.sleep(200)
            }
        }
    }

    /** Gone = not in the hierarchy, or not visible (an emptied list is hidden with its old rows inside). */
    private fun waitGone(m: Matcher<View>) = waitFor(m, ViewAssertion { view, _ ->
        if (view != null && view.isShown) throw AssertionError("still shown: $view")
    })

    /** Clicks a view even if it is scrolled out of sight (long result/detail screens). */
    private fun clickAnyway() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isEnabled()
        override fun getDescription() = "performClick()"
        override fun perform(uiController: UiController, view: View) {
            view.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }
}
