package me.timschneeberger.rootlessjamesdsp.directplayer

import android.media.AudioFormat

/** Source information resolved before playback enters Android's mixer. */
data class SourceAudioFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcmEncoding: Int = AudioFormat.ENCODING_INVALID,
    val bitDepth: Int? = null,
    val mimeType: String? = null,
    val containerMimeType: String? = null,
) {
    val isLinearPcm: Boolean
        get() = pcmEncoding in setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
        )

    val isKnownLossless: Boolean
        get() = isLinearPcm || mimeType?.lowercase() in LOSSLESS_MIME_TYPES

    val isHighResolution: Boolean
        get() = sampleRateHz > 48_000 || (bitDepth ?: 0) > 16

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        require(bitDepth == null || bitDepth > 0) { "bitDepth must be positive when present" }
    }

    companion object {
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac",
            "audio/alac",
            "audio/x-alac",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/vnd.wave",
            "audio/raw",
        )
    }
}

data class EncodedBuffer(
    val bytes: ByteArray,
    val endOfStream: Boolean = false,
)

data class PcmBuffer(
    val bytes: ByteArray,
    val format: SourceAudioFormat,
    val frameCount: Int,
)

sealed interface DecodeResult {
    data class Output(val pcm: PcmBuffer) : DecodeResult
    data object NeedMoreInput : DecodeResult
    data object EndOfStream : DecodeResult
    data class Failure(val reason: String) : DecodeResult
}

/**
 * Lawful source-side decoder boundary.
 *
 * The open-source build intentionally registers no MQA implementation. Proprietary implementations
 * may only be supplied later under an explicit redistribution and patent licence.
 */
interface OptionalSourceDecoder {
    val identifier: String
    val displayName: String

    fun supports(metadata: SourceAudioFormat): Boolean

    fun process(input: EncodedBuffer): DecodeResult

    fun reset()
}

class OptionalSourceDecoderRegistry(
    decoders: Collection<OptionalSourceDecoder> = emptyList(),
) {
    private val decoders = decoders.associateBy { decoder -> decoder.identifier }

    init {
        require(this.decoders.size == decoders.size) { "Decoder identifiers must be unique" }
        require(this.decoders.keys.none(String::isBlank)) { "Decoder identifiers must not be blank" }
    }

    fun matching(metadata: SourceAudioFormat): List<OptionalSourceDecoder> =
        decoders.values.filter { decoder -> decoder.supports(metadata) }

    fun find(identifier: String): OptionalSourceDecoder? = decoders[identifier]

    fun identifiers(): Set<String> = decoders.keys
}

enum class MqaAvailability {
    NOT_DETECTED,
    CARRIER_REPORTED_BY_TRUSTED_METADATA,
    LAWFUL_DECODER_PLUGIN_NOT_INSTALLED,
    AUTHORIZED_DECODER_AVAILABLE,
}

data class MqaSupportState(
    val availability: MqaAvailability,
    val carrierPassthroughPossible: Boolean,
    val firstUnfoldActive: Boolean = false,
    val higherStageRenderingActive: Boolean = false,
) {
    init {
        require(!firstUnfoldActive || availability == MqaAvailability.AUTHORIZED_DECODER_AVAILABLE) {
            "First unfold cannot be active without an authorized decoder"
        }
        require(!higherStageRenderingActive || firstUnfoldActive) {
            "Higher-stage rendering requires an active authorized decoder"
        }
    }

    companion object {
        fun unavailable(carrierReportedByTrustedMetadata: Boolean): MqaSupportState =
            if (carrierReportedByTrustedMetadata) {
                MqaSupportState(
                    availability = MqaAvailability.LAWFUL_DECODER_PLUGIN_NOT_INSTALLED,
                    carrierPassthroughPossible = true,
                )
            } else {
                MqaSupportState(
                    availability = MqaAvailability.NOT_DETECTED,
                    carrierPassthroughPossible = false,
                )
            }
    }
}
