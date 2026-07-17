package me.timschneeberger.rootlessjamesdsp.activity

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectPlayerNavigationTest {
    @Test
    fun rootlessSettingsOpensDirectPlayer() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.direct_player_title)).perform(click())
            onView(withText(R.string.direct_player_source_section)).check(matches(isDisplayed()))
            onView(withText(R.string.direct_player_usb_toggle)).check(matches(isDisplayed()))
        }
    }
}
