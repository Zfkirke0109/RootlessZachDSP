package me.timschneeberger.rootlessjamesdsp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.activity.MainActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator regression check for the normal MainActivity -> Settings path. */
@RunWith(AndroidJUnit4::class)
class SettingsDiagnosticsNavigationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val targetContext
        get() = instrumentation.targetContext

    @Before
    fun prepareMainScreen() {
        val packageName = targetContext.packageName

        shell("pm grant $packageName android.permission.RECORD_AUDIO")
        shell("pm grant $packageName android.permission.DUMP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            shell("appops set $packageName android:project_media allow")
        }

        targetContext.getSharedPreferences(Constants.PREF_VAR, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(targetContext.getString(R.string.key_first_boot), false)
            .putBoolean(
                targetContext.getString(R.string.key_android15_screenrecord_restriction_seen),
                true
            )
            .commit()
    }

    @Test
    fun settingsRemainsReachableFromMainScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openSettingsFromVisibleToolbarAction()
            onView(withText(R.string.rootless_zach_diagnostics_title))
                .check(matches(isDisplayed()))
                .perform(click())
            onView(withText(R.string.rootless_zach_diagnostics_engine_status_title))
                .check(matches(isDisplayed()))

            pressBack()
            pressBack()

            openSettingsFromVisibleToolbarAction()
            onView(withText(R.string.rootless_zach_diagnostics_title))
                .check(matches(isDisplayed()))
        }
    }

    private fun openSettingsFromVisibleToolbarAction() {
        onView(
            allOf(
                withContentDescription(R.string.action_settings),
                isDisplayed()
            )
        ).perform(click())
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                stream.readBytes()
            }
        }
    }
}
