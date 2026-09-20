package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeResolverTest {
    @Test
    fun `automatic prefers exact usb and disables dsp`() {
        val result = PlaybackModeResolver.resolve(
            RequestedPlaybackMode.AUTOMATIC,
            fullCapabilities(),
        )
        assertEquals(ResolvedPlaybackPath.ANDROID_BIT_PERFECT_DIRECT, result.path)
        assertFalse(result.dspEnabled)
        assertFalse(result.bitPerfectClaimAllowed)
    }

    @Test
    fun `automatic uses dsp when usb contract is unavailable`() {
        val result = PlaybackModeResolver.resolve(
            RequestedPlaybackMode.AUTOMATIC,
            fullCapabilities().copy(androidBitPerfectUsbSessionReady = false),
        )
        assertEquals(ResolvedPlaybackPath.ROOTLESS_JAMES_DSP, result.path)
        assertTrue(result.dspEnabled)
    }

    @Test
    fun `explicit direct mode never silently falls back`() {
        val result = PlaybackModeResolver.resolve(
            RequestedPlaybackMode.BIT_PERFECT_DIRECT,
            fullCapabilities().copy(androidBitPerfectUsbSessionReady = false),
        )
        assertEquals(ResolvedPlaybackPath.UNAVAILABLE, result.path)
        assertFalse(result.dspEnabled)
    }

    @Test
    fun `automatic labels ordinary android fallback honestly`() {
        val result = PlaybackModeResolver.resolve(
            RequestedPlaybackMode.AUTOMATIC,
            fullCapabilities().copy(
                sourceDecodedWithoutLoss = false,
                directPcmFormatSupported = false,
                androidBitPerfectUsbSessionReady = false,
                jamesDspSupportsChannelLayout = false,
            ),
        )
        assertEquals(ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK, result.path)
        assertTrue(result.reason.contains("resampling"))
    }

    private fun fullCapabilities() = PlaybackPathCapabilities(
        sourceDecodedWithoutLoss = true,
        directPcmFormatSupported = true,
        androidBitPerfectUsbSessionReady = true,
        jamesDspSupportsChannelLayout = true,
        ordinaryAndroidPlaybackSupported = true,
    )
}
