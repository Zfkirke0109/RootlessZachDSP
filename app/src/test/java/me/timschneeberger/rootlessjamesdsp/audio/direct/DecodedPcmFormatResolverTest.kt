package me.timschneeberger.rootlessjamesdsp.audio.direct

import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessCodec
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessIntegrity
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmMetadata
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessSampleRepresentation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DecodedPcmFormatResolverTest {
    @Test
    fun `lossless integer pcm may be evaluated for exact usb`() {
        assertNotNull(DecodedPcmFormatResolver.resolve(metadata()))
    }

    @Test
    fun `hybrid core-only is never eligible for bit-perfect direct mode`() {
        assertNull(
            DecodedPcmFormatResolver.resolve(
                metadata().copy(integrity = LosslessIntegrity.HYBRID_LOSSY),
            ),
        )
    }

    @Test
    fun `float conversion is never eligible for bit-perfect direct mode`() {
        assertNull(
            DecodedPcmFormatResolver.resolve(
                metadata().copy(
                    sourceSampleRepresentation = LosslessSampleRepresentation.IEEE_FLOAT,
                    decodedTransformations = listOf("float converted to integer"),
                ),
            ),
        )
    }

    private fun metadata() = LosslessPcmMetadata(
        codec = LosslessCodec.WAVPACK,
        integrity = LosslessIntegrity.LOSSLESS,
        sampleRate = 96_000,
        nativeBitDepth = 24,
        channelCount = 2,
        channelLayout = "front-left, front-right",
        totalFrames = 1_000,
        tags = emptyMap(),
        replayGain = emptyMap(),
        artwork = null,
        streamChecksum = null,
    )
}
