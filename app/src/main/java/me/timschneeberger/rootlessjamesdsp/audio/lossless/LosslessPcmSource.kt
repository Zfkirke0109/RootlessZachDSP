package me.timschneeberger.rootlessjamesdsp.audio.lossless

import java.io.Closeable

enum class LosslessCodec {
    FLAC,
    WAVPACK,
}

enum class LosslessIntegrity {
    LOSSLESS,
    HYBRID_LOSSY,
    HYBRID_CORRECTED_LOSSLESS,
}

enum class LosslessSampleRepresentation {
    SIGNED_INTEGER,
    IEEE_FLOAT,
}

data class LosslessArtwork(
    val mimeType: String?,
    val bytes: ByteArray,
)

data class LosslessPcmMetadata(
    val codec: LosslessCodec,
    val integrity: LosslessIntegrity,
    val sampleRate: Int,
    val nativeBitDepth: Int,
    val channelCount: Int,
    val channelLayout: String,
    val totalFrames: Long,
    val tags: Map<String, List<String>>,
    val replayGain: Map<String, String>,
    val artwork: LosslessArtwork?,
    val streamChecksum: String?,
    val sourceSampleRepresentation: LosslessSampleRepresentation = LosslessSampleRepresentation.SIGNED_INTEGER,
    val decodedTransformations: List<String> = emptyList(),
    val canonicalInterleavedChannelOrder: Boolean = true,
)

/** Returns interleaved signed PCM, left-aligned in each 32-bit sample. */
interface LosslessPcmSource : Closeable {
    val metadata: LosslessPcmMetadata

    /** Returns decoded frames, zero at end of stream, or throws on a decoder error. */
    fun readFrames(output: IntArray, requestedFrames: Int): Int

    fun seekToFrame(frame: Long): Boolean
}
