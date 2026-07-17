package me.timschneeberger.rootlessjamesdsp.directplayer

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalSourceDecoderRegistryTest {
    private val flac = SourceAudioFormat(
        sampleRateHz = 96_000,
        channelCount = 2,
        pcmEncoding = AudioFormat.ENCODING_INVALID,
        bitDepth = 24,
        mimeType = "audio/flac",
    )

    @Test
    fun `open build ships with an empty proprietary decoder registry`() {
        val registry = OptionalSourceDecoderRegistry()

        assertTrue(registry.identifiers().isEmpty())
        assertTrue(registry.matching(flac).isEmpty())
        assertNull(registry.find("mqa"))
    }

    @Test
    fun `duplicate decoder identifiers are rejected`() {
        val decoder = FakeDecoder("authorized-test")

        assertThrows(IllegalArgumentException::class.java) {
            OptionalSourceDecoderRegistry(listOf(decoder, decoder))
        }
    }

    @Test
    fun `mqa state cannot claim an unfold without an authorized decoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqaSupportState(
                availability = MqaAvailability.LAWFUL_DECODER_PLUGIN_NOT_INSTALLED,
                carrierPassthroughPossible = true,
                firstUnfoldActive = true,
            )
        }

        val state = MqaSupportState.unavailable(carrierReportedByTrustedMetadata = true)
        assertEquals(MqaAvailability.LAWFUL_DECODER_PLUGIN_NOT_INSTALLED, state.availability)
        assertTrue(state.carrierPassthroughPossible)
        assertFalse(state.firstUnfoldActive)
    }

    private class FakeDecoder(
        override val identifier: String,
    ) : OptionalSourceDecoder {
        override val displayName: String = "Authorized test decoder"

        override fun supports(metadata: SourceAudioFormat): Boolean = true

        override fun process(input: EncodedBuffer): DecodeResult = DecodeResult.NeedMoreInput

        override fun reset() = Unit
    }
}
