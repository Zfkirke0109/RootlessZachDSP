package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Technical source metadata only. No song name, path, package identity, or PCM is persisted. */
data class DirectSourceInspection(
    val metadata: DirectStreamMetadata,
    val decodedPcmFormat: DirectPcmFormat?,
    val knownLossless: Boolean,
    val highResolution: Boolean,
)

object DirectSourceInspector {
    fun inspect(context: Context, uri: Uri): Result<DirectSourceInspection> = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackFormat = (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?: error("No audio track was found")

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            val sampleRate = trackFormat.optionalPositiveInt(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = trackFormat.optionalPositiveInt(MediaFormat.KEY_CHANNEL_COUNT)
            val bitDepth = trackFormat.optionalPositiveInt("bits-per-sample")
                ?: bitDepthForEncoding(trackFormat.optionalPositiveInt(MediaFormat.KEY_PCM_ENCODING))
            val encoding = resolvePcmEncoding(mime, trackFormat, bitDepth)
            val channelMask = channelMask(channelCount)
            val pcmFormat = if (
                mime == "audio/raw" &&
                sampleRate != null &&
                channelCount != null &&
                encoding != AudioFormat.ENCODING_INVALID &&
                channelMask != null
            ) {
                DirectPcmFormat(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    encoding = encoding,
                    channelMask = channelMask,
                )
            } else {
                null
            }
            val metadata = DirectStreamMetadata(
                containerMimeType = context.contentResolver.getType(uri),
                codecIdentifier = mime,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitDepth = bitDepth,
                // Android has no standardized public MQA carrier flag. Never infer it from rate/depth.
                carrierFlags = emptySet(),
            )
            val lossless = pcmFormat != null || mime?.lowercase() in LOSSLESS_MIME_TYPES
            DirectSourceInspection(
                metadata = metadata,
                decodedPcmFormat = pcmFormat,
                knownLossless = lossless,
                highResolution = lossless && ((sampleRate ?: 0) > 48_000 || (bitDepth ?: 0) > 16),
            )
        } finally {
            extractor.release()
        }
    }

    private fun resolvePcmEncoding(
        mime: String?,
        format: MediaFormat,
        bitDepth: Int?,
    ): Int {
        if (mime != "audio/raw") return AudioFormat.ENCODING_INVALID
        val explicit = format.optionalPositiveInt(MediaFormat.KEY_PCM_ENCODING)
        if (explicit != null && explicit != AudioFormat.ENCODING_INVALID) return explicit
        return when (bitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_INVALID
        }
    }

    private fun channelMask(channelCount: Int?): Int? = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> null
    }

    private fun bitDepthForEncoding(encoding: Int?): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> 32
        else -> null
    }

    private fun MediaFormat.optionalPositiveInt(key: String): Int? =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

    private val LOSSLESS_MIME_TYPES = setOf(
        "audio/flac",
        "audio/alac",
        "audio/x-alac",
        "audio/raw",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave",
    )
}
