package me.timschneeberger.rootlessjamesdsp.audio.direct

enum class RequestedPlaybackMode {
    BIT_PERFECT_DIRECT,
    ENHANCED_DSP,
    AUTOMATIC,
}

enum class ResolvedPlaybackPath {
    ANDROID_BIT_PERFECT_DIRECT,
    ROOTLESS_JAMES_DSP,
    ORDINARY_ANDROID_PLAYBACK,
    UNAVAILABLE,
}

data class PlaybackPathCapabilities(
    val sourceDecodedWithoutLoss: Boolean,
    val directPcmFormatSupported: Boolean,
    val androidBitPerfectUsbSessionReady: Boolean,
    val jamesDspSupportsChannelLayout: Boolean,
    val ordinaryAndroidPlaybackSupported: Boolean,
)

data class PlaybackModeResolution(
    val path: ResolvedPlaybackPath,
    val reason: String,
    val dspEnabled: Boolean,
    val bitPerfectClaimAllowed: Boolean,
)

object PlaybackModeResolver {
    fun resolve(
        requested: RequestedPlaybackMode,
        capabilities: PlaybackPathCapabilities,
    ): PlaybackModeResolution = when (requested) {
        RequestedPlaybackMode.BIT_PERFECT_DIRECT -> when {
            !capabilities.sourceDecodedWithoutLoss -> unavailable("No lossless decoder is available for this source")
            !capabilities.directPcmFormatSupported -> unavailable("The decoded PCM layout has no exact Android AudioTrack format")
            !capabilities.androidBitPerfectUsbSessionReady -> unavailable("No Android bit-perfect USB mixer-contract session is active")
            else -> PlaybackModeResolution(
                path = ResolvedPlaybackPath.ANDROID_BIT_PERFECT_DIRECT,
                reason = "DSP and all app transformations are bypassed",
                dspEnabled = false,
                // The path may be eligible/active, but only external evidence permits a literal
                // verified-bit-perfect claim.
                bitPerfectClaimAllowed = false,
            )
        }

        RequestedPlaybackMode.ENHANCED_DSP -> if (capabilities.jamesDspSupportsChannelLayout) {
            PlaybackModeResolution(
                path = ResolvedPlaybackPath.ROOTLESS_JAMES_DSP,
                reason = "Decoded PCM is processed by the active RootlessJamesDSP stages",
                dspEnabled = true,
                bitPerfectClaimAllowed = false,
            )
        } else {
            unavailable("JamesDSP playback currently requires stereo decoded PCM")
        }

        RequestedPlaybackMode.AUTOMATIC -> when {
            capabilities.sourceDecodedWithoutLoss &&
                capabilities.directPcmFormatSupported &&
                capabilities.androidBitPerfectUsbSessionReady -> PlaybackModeResolution(
                path = ResolvedPlaybackPath.ANDROID_BIT_PERFECT_DIRECT,
                reason = "Automatic mode selected the transformation-free Android USB path",
                dspEnabled = false,
                bitPerfectClaimAllowed = false,
            )
            capabilities.jamesDspSupportsChannelLayout -> PlaybackModeResolution(
                path = ResolvedPlaybackPath.ROOTLESS_JAMES_DSP,
                reason = "Automatic mode selected JamesDSP because exact USB output is unavailable",
                dspEnabled = true,
                bitPerfectClaimAllowed = false,
            )
            capabilities.ordinaryAndroidPlaybackSupported -> PlaybackModeResolution(
                path = ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK,
                reason = "Automatic mode fell back to Android playback; resampling and mixing are unknown",
                dspEnabled = false,
                bitPerfectClaimAllowed = false,
            )
            else -> unavailable("No safe playback path supports this source")
        }
    }

    private fun unavailable(reason: String) = PlaybackModeResolution(
        path = ResolvedPlaybackPath.UNAVAILABLE,
        reason = reason,
        dspEnabled = false,
        bitPerfectClaimAllowed = false,
    )
}
