package me.timschneeberger.rootlessjamesdsp.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    @Test
    fun `current and legacy RootlessJamesDSP version metadata are accepted`() {
        assertEquals(51, BackupManager.getMinimumVersionCode(mapOf("min_version_code" to "51")))
        assertEquals(42, BackupManager.getMinimumVersionCode(mapOf("minVersionCode" to "42")))
        assertEquals(
            51,
            BackupManager.getMinimumVersionCode(
                mapOf("min_version_code" to "51", "minVersionCode" to "42")
            )
        )
        assertEquals(0, BackupManager.getMinimumVersionCode(emptyMap()))
        assertEquals(0, BackupManager.getMinimumVersionCode(mapOf("min_version_code" to "invalid")))
        assertEquals(
            42,
            BackupManager.getMinimumVersionCode(
                mapOf("min_version_code" to "invalid", "minVersionCode" to "42")
            )
        )
    }

    @Test
    fun `legacy backup without marker remains compatible but explicit non-backup is rejected`() {
        assertFalse(BackupManager.isExplicitNonBackup(emptyMap()))
        assertFalse(BackupManager.isExplicitNonBackup(mapOf(BackupManager.META_IS_BACKUP to "true")))
        assertTrue(BackupManager.isExplicitNonBackup(mapOf(BackupManager.META_IS_BACKUP to "FALSE")))
    }

    @Test
    fun `known backup paths require exact roots and expected files`() {
        assertTrue(BackupManager.isKnownFile("shared_prefs/dsp_equalizer.xml"))
        assertTrue(BackupManager.isKnownFile("shared_prefs/dsp_equalizer.XML"))
        assertTrue(BackupManager.isKnownFile("profiles/profile.tar"))
        assertTrue(BackupManager.isKnownFile("profiles/speaker/profile.json"))
        assertTrue(BackupManager.isKnownFile("Convolver/room.WAV"))
        assertTrue(BackupManager.isKnownFile("Liveprog/custom.eel"))

        assertFalse(BackupManager.isKnownFile("shared_prefs/not_dsp.xml"))
        assertFalse(BackupManager.isKnownFile("nested/shared_prefs/dsp_equalizer.xml"))
        assertFalse(BackupManager.isKnownFile("profiles/device/nested/profile.json"))
        assertFalse(BackupManager.isKnownFile("profiles/device/notes.txt"))
        assertFalse(BackupManager.isKnownFile("xConvolver/room.wav"))
        assertFalse(BackupManager.isKnownFile("Convolver/nested/room.wav"))
    }

    @Test
    fun `backup picker covers Samsung legacy and generic MIME classifications`() {
        val first = BackupManager.getSupportedMimeTypes()
        assertTrue(first.contains("application/gzip"))
        assertTrue(first.contains("application/x-gzip"))
        assertTrue(first.contains("application/octet-stream"))
        assertTrue(first.contains("application/*"))

        first[0] = "mutated"
        assertNotEquals("mutated", BackupManager.getSupportedMimeTypes()[0])
    }
}
