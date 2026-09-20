package me.timschneeberger.rootlessjamesdsp.audio.direct

/** Metadata available before the source is decoded or submitted to Android audio. */
data class DirectStreamMetadata(
    val containerMimeType: String?,
    val codecIdentifier: String?,
    val sampleRate: Int?,
    val channelCount: Int?,
    val bitDepth: Int?,
    val carrierFlags: Set<String> = emptySet(),
)

data class EncodedBuffer(
    val bytes: ByteArray,
    val offset: Int = 0,
    val length: Int = bytes.size,
) {
    init {
        require(offset >= 0)
        require(length >= 0)
        require(offset <= bytes.size - length)
    }
}

data class PcmBuffer(
    val bytes: ByteArray,
    val format: DirectPcmFormat,
    val frames: Int,
)

sealed interface DecodeResult {
    data class Produced(val pcm: PcmBuffer, val consumedBytes: Int) : DecodeResult
    data object NeedMoreInput : DecodeResult
    data class Unsupported(val reason: String) : DecodeResult
    data class Failed(val reason: String) : DecodeResult
}

/**
 * Legal isolation boundary for optional source-side codecs or expansion stages.
 *
 * RootlessZachDSP ships no proprietary MQA implementation through this interface. A module may be
 * registered only after its software license, redistribution permission, patent status, trademark
 * use, and test vectors are documented and approved.
 */
interface OptionalSourceDecoder {
    val identifier: String

    fun supports(metadata: DirectStreamMetadata): Boolean

    fun process(input: EncodedBuffer): DecodeResult
}

object NoProprietarySourceDecoder : OptionalSourceDecoder {
    override val identifier: String = "none"

    override fun supports(metadata: DirectStreamMetadata): Boolean = false

    override fun process(input: EncodedBuffer): DecodeResult =
        DecodeResult.Unsupported("No authorized optional source decoder is installed")
}
