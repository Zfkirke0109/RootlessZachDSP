package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

import android.content.Context
import android.net.Uri
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessArtwork
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessCodec
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessIntegrity
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmMetadata
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmSource
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessSampleRepresentation
import kotlin.math.roundToLong

class WavPackPcmSource private constructor(
    private val decoder: NativeWavPackDecoder,
) : LosslessPcmSource {
    private val floatingPoint =
        decoder.metadata.sampleRepresentation == WavPackSampleRepresentation.IEEE_FLOAT32_BITS_IN_INT32

    override val metadata: LosslessPcmMetadata = decoder.metadata.let { wavPack ->
        LosslessPcmMetadata(
            codec = LosslessCodec.WAVPACK,
            integrity = when (wavPack.encoding.mode) {
                WavPackEncodingMode.LOSSLESS -> LosslessIntegrity.LOSSLESS
                WavPackEncodingMode.HYBRID_CORRECTED -> LosslessIntegrity.HYBRID_CORRECTED_LOSSLESS
                WavPackEncodingMode.HYBRID_LOSSY,
                WavPackEncodingMode.UNKNOWN,
                -> LosslessIntegrity.HYBRID_LOSSY
            },
            sampleRate = wavPack.sampleRate,
            nativeBitDepth = wavPack.bitsPerSample,
            channelCount = wavPack.channelCount,
            channelLayout = wavPack.channelLayoutDescription,
            totalFrames = wavPack.totalFrames,
            tags = wavPack.tags,
            replayGain = buildMap {
                wavPack.replayGain.trackGain?.let { put("trackGain", it) }
                wavPack.replayGain.trackPeak?.let { put("trackPeak", it) }
                wavPack.replayGain.albumGain?.let { put("albumGain", it) }
                wavPack.replayGain.albumPeak?.let { put("albumPeak", it) }
            },
            artwork = wavPack.artwork.firstOrNull()?.let {
                LosslessArtwork(it.mimeType, it.bytes)
            },
            streamChecksum = wavPack.storedMd5,
            sourceSampleRepresentation = if (floatingPoint) {
                LosslessSampleRepresentation.IEEE_FLOAT
            } else {
                LosslessSampleRepresentation.SIGNED_INTEGER
            },
            decodedTransformations = if (floatingPoint) {
                listOf("IEEE float converted to signed PCM32 for JamesDSP; direct bit-perfect mode disabled")
            } else {
                emptyList()
            },
            canonicalInterleavedChannelOrder = WavPackChannelOrder.isCanonical(wavPack),
        )
    }

    override fun readFrames(output: IntArray, requestedFrames: Int): Int {
        val frames = decoder.readFrames(output, requestedFrames)
        val sampleCount = frames * metadata.channelCount
        if (floatingPoint) {
            WavPackSampleNormalizer.floatBitsToPcm32InPlace(output, sampleCount)
        } else {
            WavPackSampleNormalizer.leftAlignIntegerInPlace(
                samples = output,
                sampleCount = sampleCount,
                bitsPerSample = metadata.nativeBitDepth,
            )
        }
        return frames
    }

    override fun seekToFrame(frame: Long): Boolean = runCatching {
        decoder.seekToFrame(frame)
        true
    }.getOrDefault(false)

    override fun close() = decoder.close()

    companion object {
        fun open(
            context: Context,
            sourceUri: Uri,
            correctionUri: Uri? = null,
        ): Result<WavPackPcmSource> =
            NativeWavPackDecoder.open(context, sourceUri, correctionUri).map(::WavPackPcmSource)
    }
}

object WavPackSampleNormalizer {
    fun leftAlignIntegerInPlace(samples: IntArray, sampleCount: Int, bitsPerSample: Int) {
        require(sampleCount in 0..samples.size)
        require(bitsPerSample in 1..32)
        val shift = 32 - bitsPerSample
        if (shift == 0) return
        for (index in 0 until sampleCount) samples[index] = samples[index] shl shift
    }

    fun floatBitsToPcm32InPlace(samples: IntArray, sampleCount: Int) {
        require(sampleCount in 0..samples.size)
        for (index in 0 until sampleCount) {
            val value = Float.fromBits(samples[index])
            samples[index] = when {
                value.isNaN() -> 0
                value >= 1f -> Int.MAX_VALUE
                value <= -1f -> Int.MIN_VALUE
                else -> (value.toDouble() * PCM32_NEGATIVE_SCALE).roundToLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }
    }

    private const val PCM32_NEGATIVE_SCALE = 2_147_483_648.0
}

object WavPackChannelOrder {
    fun isCanonical(metadata: WavPackMetadata): Boolean = isCanonical(
        metadata.channelCount,
        metadata.channelIdentities,
        metadata.channelReordering,
    )

    fun isCanonical(
        channelCount: Int,
        channelIdentities: List<Int>,
        channelReordering: List<Int>,
    ): Boolean {
        val expectedIdentities = when (channelCount) {
            1 -> listOf(3)
            2 -> listOf(1, 2)
            else -> return false
        }
        val identityReordering = channelReordering.isEmpty() ||
            channelReordering == channelReordering.indices.toList()
        return channelIdentities == expectedIdentities && identityReordering
    }
}
