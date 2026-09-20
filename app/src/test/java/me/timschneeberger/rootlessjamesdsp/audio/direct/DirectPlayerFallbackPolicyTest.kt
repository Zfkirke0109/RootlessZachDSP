package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPlayerFallbackPolicyTest {
    @Test
    fun automaticMode_fallsBackOnlyWhenOrdinaryPlaybackIsSupported() {
        assertTrue(
            DirectPlayerFallbackPolicy.shouldUseOrdinaryPlaybackAfterFailure(
                RequestedPlaybackMode.AUTOMATIC,
                ordinaryPlaybackSupported = true,
                hasSelectedSource = true,
            ),
        )
        assertFalse(
            DirectPlayerFallbackPolicy.shouldUseOrdinaryPlaybackAfterFailure(
                RequestedPlaybackMode.AUTOMATIC,
                ordinaryPlaybackSupported = false,
                hasSelectedSource = true,
            ),
        )
    }

    @Test
    fun explicitModes_neverSilentlyDowngrade() {
        assertFalse(
            DirectPlayerFallbackPolicy.shouldUseOrdinaryPlaybackAfterFailure(
                RequestedPlaybackMode.BIT_PERFECT_DIRECT,
                ordinaryPlaybackSupported = true,
                hasSelectedSource = true,
            ),
        )
        assertFalse(
            DirectPlayerFallbackPolicy.shouldUseOrdinaryPlaybackAfterFailure(
                RequestedPlaybackMode.ENHANCED_DSP,
                ordinaryPlaybackSupported = true,
                hasSelectedSource = true,
            ),
        )
    }
}
