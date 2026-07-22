package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioFormat
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessIntegrity
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmMetadata
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessSampleRepresentation

object DecodedPcmFormatResolver {
    fun resolve(metadata: LosslessPcmMetadata): DirectPcmFormat? {
        if (metadata.integrity == LosslessIntegrity.HYBRID_LOSSY) return null
        if (!metadata.canonicalInterleavedChannelOrder || metadata.channelCount !in 1..2) return null
        if (metadata.sourceSampleRepresentation != LosslessSampleRepresentation.SIGNED_INTEGER) {
            return null
        }
        val mask = channelMask(metadata.channelCount) ?: return null
        val container = runCatching {
            PcmContainer.forNativeBitDepth(metadata.nativeBitDepth)
        }.getOrNull() ?: return null
        return DirectPcmFormat(
            sampleRate = metadata.sampleRate,
            channelCount = metadata.channelCount,
            encoding = container.androidEncoding,
            channelMask = mask,
        )
    }

    fun channelMask(channelCount: Int): Int? = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> null
    }
}
