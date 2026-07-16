package me.timschneeberger.rootlessjamesdsp

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.activity.SettingsActivity
import org.junit.Test
import org.junit.runner.RunWith

/** Physical/emulator UI check for the restored regular Settings and Diagnostics navigation. */
@RunWith(AndroidJUnit4::class)
class SettingsDiagnosticsNavigationTest {
    @Test
    fun diagnosticsPreferenceOpensFromRegularSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.rootless_zach_diagnostics_title))
                .check(matches(isDisplayed()))
                .perform(click())

            onView(withText(R.string.rootless_zach_diagnostics_engine_status_title))
                .check(matches(isDisplayed()))
        }
    }
}
