package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectNegotiatorTest {
    private val source = DirectPcmFormat(
        sampleRate = 96_000,
        channelCount = 2,
        encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
        channelMask = AudioFormat.CHANNEL_OUT_STEREO,
    )

    @Test
    fun `empty capability list is rejected`() {
        val result = UsbBitPerfectNegotiator.select(source, emptyList())

        assertEquals(DirectPlaybackFidelityState.NO_CONFIGURABLE_USB_MIXER, result.state)
        assertNull(result.candidate)
    }

    @Test
    fun `sample-rate conversion is never accepted`() {
        val result = UsbBitPerfectNegotiator.select(
            source,
            listOf(candidate(source.copy(sampleRate = 48_000), bitPerfect = true)),
        )

        assertEquals(DirectPlaybackFidelityState.NO_EXACT_SOURCE_FORMAT, result.state)
        assertNull(result.candidate)
    }

    @Test
    fun `exact format without bit-perfect behavior is rejected`() {
        val result = UsbBitPerfectNegotiator.select(
            source,
            listOf(candidate(source, bitPerfect = false)),
        )

        assertEquals(DirectPlaybackFidelityState.NO_BIT_PERFECT_MIXER, result.state)
        assertNull(result.candidate)
    }

    @Test
    fun `first stable exact bit-perfect candidate is selected`() {
        val result = UsbBitPerfectNegotiator.select(
            source,
            listOf(
                candidate(source, bitPerfect = true, order = 4),
                candidate(source, bitPerfect = true, order = 2),
            ),
        )

        assertEquals(DirectPlaybackFidelityState.ELIGIBLE, result.state)
        assertNotNull(result.candidate)
        assertEquals(2, result.candidate?.stableOrder)
    }

    @Test
    fun `bit-perfect policy requires every bypass and verification condition`() {
        val eligible = BitPerfectPlaybackPolicy(
            sourceFormat = source,
            exactSourceFormat = true,
            exactBitPerfectMixerCandidate = true,
            dspBypassed = true,
            equalizerBypassed = true,
            limiterBypassed = true,
            gainRampsBypassed = true,
            fadesBypassed = true,
            loudnessBypassed = true,
            trackVolumeUnity = true,
            androidBitPerfectMixerContractActive = true,
        )
        assertTrue(eligible.eligible)
        assertEquals(
            DirectPlaybackFidelityState.ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE,
            eligible.evidence.highestState,
        )
        assertFalse(eligible.copy(limiterBypassed = false).eligible)
        assertFalse(eligible.copy(trackVolumeUnity = false).eligible)
        assertFalse(eligible.copy(exactBitPerfectMixerCandidate = false).eligible)
        assertEquals(
            DirectPlaybackFidelityState.ELIGIBLE,
            eligible.copy(androidBitPerfectMixerContractActive = false).evidence.highestState,
        )
    }

    private fun candidate(
        format: DirectPcmFormat,
        bitPerfect: Boolean,
        order: Int = 0,
    ) = UsbMixerCandidate(format, bitPerfect, order)
}
