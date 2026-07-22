package me.timschneeberger.rootlessjamesdsp.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicySelectionTest {
    @Test
    fun normalized_removesBlankAndTrimsPackages() {
        val selection = CapturePolicySelection(
            mode = CapturePolicyStore.Mode.EXCLUDE_SELECTED,
            packageNames = setOf(" com.amazon.mp3 ", "", "   "),
        ).normalized()

        assertEquals(setOf("com.amazon.mp3"), selection.packageNames)
    }

    @Test
    fun toggle_addsAndRemovesTheSamePackage() {
        val initial = CapturePolicySelection(
            mode = CapturePolicyStore.Mode.ALLOW_SELECTED,
            packageNames = emptySet(),
        )

        val selected = initial.toggle("com.spotify.music")
        val removed = selected.toggle(" com.spotify.music ")

        assertTrue("com.spotify.music" in selected.packageNames)
        assertTrue(removed.packageNames.isEmpty())
    }

    @Test
    fun build_retainsSavedPackageThatIsMissingOrNotVisible() {
        val choices = CaptureApplicationChoices.build(
            visibleApplications = listOf(
                visibleApp("com.spotify.music", "Spotify", uid = 10100),
            ),
            selectedPackages = setOf("com.spotify.music", "com.amazon.mp3"),
        )

        val missing = choices.single { it.packageName == "com.amazon.mp3" }
        assertFalse(missing.isInstalledAndVisible)
        assertNull(missing.uid)
        assertEquals("com.amazon.mp3", missing.label)
    }

    @Test
    fun build_deduplicatesVisiblePackagesAndOrdersMissingThenUserThenSystem() {
        val choices = CaptureApplicationChoices.build(
            visibleApplications = listOf(
                visibleApp("android.music", "System Music", uid = 1000, isSystem = true),
                visibleApp("com.spotify.music", "Spotify", uid = 10100),
                visibleApp("com.spotify.music", "Duplicate", uid = 10100),
            ),
            selectedPackages = setOf("com.missing.player"),
        )

        assertEquals(
            listOf("com.missing.player", "com.spotify.music", "android.music"),
            choices.map { it.packageName },
        )
    }

    @Test
    fun filter_matchesLabelOrPackageIgnoringCase() {
        val choices = CaptureApplicationChoices.build(
            visibleApplications = listOf(
                visibleApp("com.amazon.mp3", "Amazon Music", uid = 10101),
                visibleApp("com.spotify.music", "Spotify", uid = 10102),
            ),
            selectedPackages = emptySet(),
        )

        assertEquals(
            listOf("com.amazon.mp3"),
            CaptureApplicationChoices.filter(choices, "AMAZON").map { it.packageName },
        )
        assertEquals(
            listOf("com.spotify.music"),
            CaptureApplicationChoices.filter(choices, "spotify.music").map { it.packageName },
        )
    }

    private fun visibleApp(
        packageName: String,
        label: String,
        uid: Int,
        isSystem: Boolean = false,
    ) = VisibleCaptureApplication(
        packageName = packageName,
        label = label,
        uid = uid,
        isSystem = isSystem,
        isEnabled = true,
    )
}
