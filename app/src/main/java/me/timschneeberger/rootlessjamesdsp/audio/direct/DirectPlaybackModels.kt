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
    /** Exact source and mixer formats are available without a known conversion. */
    ELIGIBLE,

    /** Android accepted and read back its public bit-perfect mixer contract. */
    ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE,

    /** A playing AudioTrack reported the selected USB device from [android.media.AudioTrack.getRoutedDevice]. */
    ROUTED_DEVICE_CONFIRMED,

    /** A digital-loopback or USB-payload hash matched for this exact PCM format. */
    EXTERNALLY_VERIFIED,
}

enum class MqaAvailabilityState {
    DECODER_UNAVAILABLE,
    CARRIER_NOT_DETECTED,
    CARRIER_DETECTED_DECODER_NOT_INSTALLED,
    CARRIER_PASSTHROUGH_AVAILABLE,
    AUTHORIZED_DECODER_AVAILABLE,
}

/**
 * App-observable evidence for a direct session. Higher tiers are granted only when every lower
 * tier is also present; attaching an external evidence reference alone can never produce an
 * external-verification claim.
 */
data class DirectPlaybackEvidence(
    val sourceFormat: DirectPcmFormat,
    val eligible: Boolean,
    val androidBitPerfectMixerContractActive: Boolean,
    val routedDeviceConfirmed: Boolean,
    val externalVerificationEvidence: ExternalBitPerfectEvidence?,
) {
    val highestState: DirectPlaybackFidelityState?
        get() = when {
            eligible &&
                androidBitPerfectMixerContractActive &&
                routedDeviceConfirmed &&
                externalVerificationEvidence?.sourceFormat?.exactlyMatches(sourceFormat) == true ->
                DirectPlaybackFidelityState.EXTERNALLY_VERIFIED
            eligible && androidBitPerfectMixerContractActive && routedDeviceConfirmed ->
                DirectPlaybackFidelityState.ROUTED_DEVICE_CONFIRMED
            eligible && androidBitPerfectMixerContractActive ->
                DirectPlaybackFidelityState.ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE
            eligible -> DirectPlaybackFidelityState.ELIGIBLE
            else -> null
        }
}

/** Independent evidence types that can demonstrate unchanged digital samples at the output. */
enum class ExternalBitPerfectVerificationMethod {
    DIGITAL_LOOPBACK_PCM_HASH_MATCH,
    USB_PROTOCOL_PAYLOAD_HASH_MATCH,
}

enum class DirectRouteObservation {
    PENDING,
    SELECTED_USB_CONFIRMED,
    DIFFERENT_DEVICE,
}

object DirectRouteEvidence {
    fun classify(selectedUsbDeviceId: Int, routedDeviceId: Int?): DirectRouteObservation = when {
        routedDeviceId == null -> DirectRouteObservation.PENDING
        routedDeviceId == selectedUsbDeviceId -> DirectRouteObservation.SELECTED_USB_CONFIRMED
        else -> DirectRouteObservation.DIFFERENT_DEVICE
    }
}

/**
 * A reference to independently captured evidence for one exact PCM format. Merely observing a DAC
 * rate indicator or Android API state is intentionally not an accepted method.
 */
data class ExternalBitPerfectEvidence(
    val method: ExternalBitPerfectVerificationMethod,
    val sourceFormat: DirectPcmFormat,
    val artifactReference: String,
) {
    init {
        require(artifactReference.isNotBlank())
    }
}

/**
 * Transformation gates for direct USB eligibility. This deliberately excludes every
 * RootlessZachDSP processing stage. Android-contract, routed-device, and physical-output evidence
 * remain separate so an app-observable check is never presented as external verification.
 */
data class BitPerfectPlaybackPolicy(
    val sourceFormat: DirectPcmFormat,
    val exactSourceFormat: Boolean,
    val exactBitPerfectMixerCandidate: Boolean,
    val dspBypassed: Boolean,
    val equalizerBypassed: Boolean,
    val limiterBypassed: Boolean,
    val gainRampsBypassed: Boolean,
    val fadesBypassed: Boolean,
    val loudnessBypassed: Boolean,
    val trackVolumeUnity: Boolean,
    val androidBitPerfectMixerContractActive: Boolean,
    val routedDeviceConfirmed: Boolean = false,
    val externalVerificationEvidence: ExternalBitPerfectEvidence? = null,
) {
    val eligible: Boolean
        get() = exactSourceFormat &&
            exactBitPerfectMixerCandidate &&
            dspBypassed &&
            equalizerBypassed &&
            limiterBypassed &&
            gainRampsBypassed &&
            fadesBypassed &&
            loudnessBypassed &&
            trackVolumeUnity

    val evidence: DirectPlaybackEvidence
        get() = DirectPlaybackEvidence(
            sourceFormat = sourceFormat,
            eligible = eligible,
            androidBitPerfectMixerContractActive = androidBitPerfectMixerContractActive,
            routedDeviceConfirmed = routedDeviceConfirmed,
            externalVerificationEvidence = externalVerificationEvidence,
        )
}
