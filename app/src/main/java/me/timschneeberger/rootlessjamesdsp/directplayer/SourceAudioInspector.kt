package me.timschneeberger.rootlessjamesdsp.directplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Reads container metadata without storing PCM or private path information. */
object SourceAudioInspector {
    data class Inspection(
        val format: SourceAudioFormat,
        val mqaCarrierReportedByTrustedMetadata: Boolean = false,
    )

    fun inspect(context: Context, uri: Uri): Result<Inspection> = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioFormat = (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?: error("No audio track was found in the selected document")

            val mimeType = audioFormat.getString(MediaFormat.KEY_MIME)
            val sampleRate = audioFormat.requiredPositiveInt(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.requiredPositiveInt(MediaFormat.KEY_CHANNEL_COUNT)
            val bitDepth = audioFormat.optionalPositiveInt(MediaFormat.KEY_BITS_PER_SAMPLE)
            val explicitEncoding = audioFormat.optionalPositiveInt(MediaFormat.KEY_PCM_ENCODING)
            val inferredEncoding = inferPcmEncoding(mimeType, explicitEncoding, bitDepth)

            Inspection(
                format = SourceAudioFormat(
                    sampleRateHz = sampleRate,
                    channelCount = channelCount,
                    pcmEncoding = inferredEncoding,
                    bitDepth = bitDepth ?: bitDepthForEncoding(inferredEncoding),
                    mimeType = mimeType,
                    containerMimeType = context.contentResolver.getType(uri),
                ),
                // Android's public extractor metadata does not provide a standardized MQA marker.
                // Never infer MQA from sample rate, file extension, or bit depth.
                mqaCarrierReportedByTrustedMetadata = false,
            )
        } finally {
            extractor.release()
        }
    }

    private fun inferPcmEncoding(
        mimeType: String?,
        explicitEncoding: Int?,
        bitDepth: Int?,
    ): Int {
        if (explicitEncoding != null && explicitEncoding != AudioFormat.ENCODING_INVALID) {
            return explicitEncoding
        }
        if (mimeType != "audio/raw") return AudioFormat.ENCODING_INVALID
        return when (bitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_INVALID
        }
    }

    private fun bitDepthForEncoding(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> 32
        else -> null
    }

    private fun MediaFormat.requiredPositiveInt(key: String): Int =
        optionalPositiveInt(key) ?: error("Selected audio does not report $key")

    private fun MediaFormat.optionalPositiveInt(key: String): Int? =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null
}
