package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectPlaybackPolicyTest {
    private val source = DirectPcmFormat(
        sampleRate = 96_000,
        channelCount = 2,
        encoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
        channelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO,
    )

    @Test
    fun `eligible only when every transformation gate passes`() {
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

        assertFalse(eligible.copy(fadesBypassed = false).eligible)
        assertFalse(eligible.copy(trackVolumeUnity = false).eligible)
        assertFalse(eligible.copy(exactBitPerfectMixerCandidate = false).eligible)
        assertTrue(eligible.copy(androidBitPerfectMixerContractActive = false).eligible)
        assertFalse(eligible.copy(exactSourceFormat = false).eligible)
    }

    @Test
    fun `evidence tiers require all lower tiers`() {
        assertNull(
            DirectPlaybackEvidence(
                sourceFormat = source,
                eligible = false,
                androidBitPerfectMixerContractActive = false,
                routedDeviceConfirmed = false,
                externalVerificationEvidence = null,
            ).highestState,
        )

        val eligible = DirectPlaybackEvidence(
            sourceFormat = source,
            eligible = true,
            androidBitPerfectMixerContractActive = false,
            routedDeviceConfirmed = false,
            externalVerificationEvidence = null,
        )
        assertEquals(DirectPlaybackFidelityState.ELIGIBLE, eligible.highestState)

        val androidContract = eligible.copy(androidBitPerfectMixerContractActive = true)
        assertEquals(
            DirectPlaybackFidelityState.ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE,
            androidContract.highestState,
        )

        val routed = androidContract.copy(routedDeviceConfirmed = true)
        assertEquals(DirectPlaybackFidelityState.ROUTED_DEVICE_CONFIRMED, routed.highestState)

        val external = routed.copy(
            externalVerificationEvidence = ExternalBitPerfectEvidence(
                method = ExternalBitPerfectVerificationMethod.DIGITAL_LOOPBACK_PCM_HASH_MATCH,
                sourceFormat = source,
                artifactReference = "sha256:fixture-loopback-match",
            ),
        )
        assertEquals(DirectPlaybackFidelityState.EXTERNALLY_VERIFIED, external.highestState)
    }

    @Test
    fun `external evidence cannot skip Android contract or routed device evidence`() {
        val unsupportedClaim = DirectPlaybackEvidence(
            sourceFormat = source,
            eligible = true,
            androidBitPerfectMixerContractActive = false,
            routedDeviceConfirmed = false,
            externalVerificationEvidence = ExternalBitPerfectEvidence(
                method = ExternalBitPerfectVerificationMethod.USB_PROTOCOL_PAYLOAD_HASH_MATCH,
                sourceFormat = source,
                artifactReference = "sha256:fixture-usb-payload-match",
            ),
        )

        assertEquals(DirectPlaybackFidelityState.ELIGIBLE, unsupportedClaim.highestState)
        assertFalse(unsupportedClaim.highestState == DirectPlaybackFidelityState.EXTERNALLY_VERIFIED)
    }

    @Test
    fun `external evidence must match the current source format`() {
        val mismatchedEvidence = DirectPlaybackEvidence(
            sourceFormat = source,
            eligible = true,
            androidBitPerfectMixerContractActive = true,
            routedDeviceConfirmed = true,
            externalVerificationEvidence = ExternalBitPerfectEvidence(
                method = ExternalBitPerfectVerificationMethod.DIGITAL_LOOPBACK_PCM_HASH_MATCH,
                sourceFormat = source.copy(sampleRate = 48_000),
                artifactReference = "sha256:different-format",
            ),
        )

        assertEquals(
            DirectPlaybackFidelityState.ROUTED_DEVICE_CONFIRMED,
            mismatchedEvidence.highestState,
        )
    }

    @Test
    fun `routed device evidence distinguishes pending selected and different routes`() {
        assertEquals(DirectRouteObservation.PENDING, DirectRouteEvidence.classify(7, null))
        assertEquals(
            DirectRouteObservation.SELECTED_USB_CONFIRMED,
            DirectRouteEvidence.classify(7, 7),
        )
        assertEquals(
            DirectRouteObservation.DIFFERENT_DEVICE,
            DirectRouteEvidence.classify(7, 12),
        )
    }
}
