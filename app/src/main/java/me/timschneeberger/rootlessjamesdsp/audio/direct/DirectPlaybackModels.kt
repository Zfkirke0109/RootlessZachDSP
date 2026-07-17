package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioFormat

/** Exact decoded PCM format owned by Direct Player before Android mixing. */
data class DirectPcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val channelMask: Int,
) {
    init {
        require(sampleRate > 0)
        require(channelCount > 0)
        require(channelMask != 0)
    }

    fun exactlyMatches(other: DirectPcmFormat): Boolean =
        sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            encoding == other.encoding &&
            channelMask == other.channelMask

    companion object {
        fun fromAudioFormat(format: AudioFormat): DirectPcmFormat = DirectPcmFormat(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            encoding = format.encoding,
            channelMask = format.channelMask,
        )
    }
}

enum class DirectPlaybackFidelityState {
    UNSUPPORTED_ANDROID_VERSION,
    NO_USB_OUTPUT,
    NO_CONFIGURABLE_USB_MIXER,
    NO_EXACT_SOURCE_FORMAT,
    NO_BIT_PERFECT_MIXER,
    PREFERENCE_REJECTED,
    PREFERENCE_NOT_ACTIVE,
    READY_BIT_PERFECT,
}

enum class MqaAvailabilityState {
    DECODER_UNAVAILABLE,
    CARRIER_NOT_DETECTED,
    CARRIER_DETECTED_DECODER_NOT_INSTALLED,
    CARRIER_PASSTHROUGH_AVAILABLE,
    AUTHORIZED_DECODER_AVAILABLE,
}

/**
 * Conditions that must all remain true while claiming bit-perfect USB playback.
 * This deliberately excludes every RootlessZachDSP processing stage.
 */
data class BitPerfectPlaybackPolicy(
    val exactSourceFormat: Boolean,
    val dspBypassed: Boolean,
    val equalizerBypassed: Boolean,
    val limiterBypassed: Boolean,
    val gainRampsBypassed: Boolean,
    val fadesBypassed: Boolean,
    val loudnessBypassed: Boolean,
    val trackVolumeUnity: Boolean,
    val preferredMixerVerified: Boolean,
) {
    val eligible: Boolean
        get() = exactSourceFormat &&
            dspBypassed &&
            equalizerBypassed &&
            limiterBypassed &&
            gainRampsBypassed &&
            fadesBypassed &&
            loudnessBypassed &&
            trackVolumeUnity &&
            preferredMixerVerified
}
