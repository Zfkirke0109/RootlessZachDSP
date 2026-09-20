package me.timschneeberger.rootlessjamesdsp.player.codec.flac

import android.content.Context
import android.net.Uri
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessArtwork
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessCodec
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessIntegrity
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmMetadata
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmSource

class FlacPcmSource private constructor(
    private val decoder: NativeFlacDecoder,
) : LosslessPcmSource {
    override val metadata: LosslessPcmMetadata = decoder.metadata.let { flac ->
        LosslessPcmMetadata(
            codec = LosslessCodec.FLAC,
            integrity = LosslessIntegrity.LOSSLESS,
            sampleRate = flac.sampleRate,
            nativeBitDepth = flac.bitsPerSample,
            channelCount = flac.channelCount,
            channelLayout = flac.channelLayout,
            totalFrames = flac.totalFrames,
            tags = flac.tags,
            replayGain = buildMap {
                flac.replayGain.trackGain?.let { put("trackGain", it) }
                flac.replayGain.trackPeak?.let { put("trackPeak", it) }
                flac.replayGain.albumGain?.let { put("albumGain", it) }
                flac.replayGain.albumPeak?.let { put("albumPeak", it) }
            },
            artwork = flac.artwork?.let { LosslessArtwork(it.mimeType, it.bytes) },
            streamChecksum = flac.streamMd5,
        )
    }

    override fun readFrames(output: IntArray, requestedFrames: Int): Int =
        decoder.readFrames(output, requestedFrames)

    override fun seekToFrame(frame: Long): Boolean = decoder.seekToFrame(frame)

    override fun close() = decoder.close()

    companion object {
        fun open(context: Context, uri: Uri): Result<FlacPcmSource> =
            NativeFlacDecoder.open(context, uri).map(::FlacPcmSource)
    }
}
