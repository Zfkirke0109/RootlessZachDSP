package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectPlaybackPolicyTest {
    @Test
    fun `eligible only when every transformation and routing gate passes`() {
        val eligible = BitPerfectPlaybackPolicy(
            exactSourceFormat = true,
            dspBypassed = true,
            equalizerBypassed = true,
            limiterBypassed = true,
            gainRampsBypassed = true,
            fadesBypassed = true,
            loudnessBypassed = true,
            trackVolumeUnity = true,
            preferredMixerVerified = true,
        )
        assertTrue(eligible.eligible)

        assertFalse(eligible.copy(fadesBypassed = false).eligible)
        assertFalse(eligible.copy(trackVolumeUnity = false).eligible)
        assertFalse(eligible.copy(preferredMixerVerified = false).eligible)
        assertFalse(eligible.copy(exactSourceFormat = false).eligible)
    }
}
