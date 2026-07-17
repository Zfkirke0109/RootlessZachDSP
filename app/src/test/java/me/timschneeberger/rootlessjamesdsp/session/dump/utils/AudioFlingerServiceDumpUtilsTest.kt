package me.timschneeberger.rootlessjamesdsp.session.dump.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFlingerServiceDumpUtilsTest {
    @Test
    fun `android 16 table version 30 parses and deduplicates exact rows`() {
        val fixture = javaClass.classLoader
            ?.getResource("session_dump/audio_flinger_v30_pluto_sanitized.txt")
            ?.readText()
        assertNotNull(fixture)

        val parsed = AudioFlingerServiceDumpUtils.process(requireNotNull(fixture))
        assertNotNull(parsed)
        assertEquals(3, parsed?.size)
        assertTrue(parsed.orEmpty().contains(AudioFlingerServiceDumpUtils.Dataset(21673, 4101, 12001)))
        assertTrue(parsed.orEmpty().contains(AudioFlingerServiceDumpUtils.Dataset(21593, 4202, 12002)))
        assertTrue(parsed.orEmpty().contains(AudioFlingerServiceDumpUtils.Dataset(21681, 4101, 12001)))
    }

    @Test
    fun `audioflinger property lines are recognized as non-row metadata`() {
        assertTrue(AudioFlingerServiceDumpUtils.isRecognizedNonRowMetadata("mSystemReady=1"))
        assertTrue(AudioFlingerServiceDumpUtils.isRecognizedNonRowMetadata("  mPrimaryHwDev = 0x1234 "))
        assertFalse(AudioFlingerServiceDumpUtils.isRecognizedNonRowMetadata("not a property line"))
    }

    @Test
    fun `api 29 table remains supported`() {
        val parsed = AudioFlingerServiceDumpUtils.process(
            """
            Global session refs
              session pid count
              123 456 1

            """.trimIndent(),
        )

        assertEquals(listOf(AudioFlingerServiceDumpUtils.Dataset(123, 456, null)), parsed)
    }
}
