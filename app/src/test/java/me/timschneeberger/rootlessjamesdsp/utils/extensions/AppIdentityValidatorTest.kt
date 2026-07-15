package me.timschneeberger.rootlessjamesdsp.utils.extensions

import me.timschneeberger.rootlessjamesdsp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityValidatorTest {
    @Test
    fun `generated variant identity is accepted`() {
        assertEquals(
            0,
            check(
                packageName = BuildConfig.APPLICATION_ID,
                appName = BuildConfig.EXPECTED_APP_NAME,
            ),
        )
    }

    @Test
    fun `unexpected package is rejected before label`() {
        assertEquals(
            1,
            check(
                packageName = "com.example.clone",
                appName = BuildConfig.EXPECTED_APP_NAME,
            ),
        )
    }

    @Test
    fun `unexpected label is rejected`() {
        assertEquals(
            2,
            check(
                packageName = BuildConfig.APPLICATION_ID,
                appName = "RootlessJamesDSP",
            ),
        )
    }

    @Test
    fun `release identity cannot carry debug suffix`() {
        assertEquals(
            3,
            AppIdentityValidator.check(
                actualPackageName = "com.zfkirke0109.rootlesszachdsp.debug",
                actualAppName = "RootlessZachDSP",
                expectedPackageName = "com.zfkirke0109.rootlesszachdsp",
                expectedAppName = "RootlessZachDSP",
                isPlugin = false,
                isDebug = false,
            ),
        )
    }

    @Test
    fun `plugin identity remains exempt`() {
        assertEquals(
            0,
            AppIdentityValidator.check(
                actualPackageName = "com.example.plugin-clone",
                actualAppName = "Unexpected plugin label",
                expectedPackageName = BuildConfig.APPLICATION_ID,
                expectedAppName = BuildConfig.EXPECTED_APP_NAME,
                isPlugin = true,
                isDebug = false,
            ),
        )
    }

    private fun check(packageName: String, appName: String): Int {
        return AppIdentityValidator.check(
            actualPackageName = packageName,
            actualAppName = appName,
            expectedPackageName = BuildConfig.APPLICATION_ID,
            expectedAppName = BuildConfig.EXPECTED_APP_NAME,
            isPlugin = false,
            isDebug = BuildConfig.DEBUG,
        )
    }
}
