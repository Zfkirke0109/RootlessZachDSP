package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

import java.nio.charset.StandardCharsets
import java.util.Locale

enum class WavPackEncodingMode {
    LOSSLESS,
    HYBRID_LOSSY,
    HYBRID_CORRECTED,
    UNKNOWN,
}

enum class WavPackSampleRepresentation {
    SIGNED_INTEGER_IN_INT32,
    IEEE_FLOAT32_BITS_IN_INT32,
}

data class WavPackEncodingAssessment(
    val mode: WavPackEncodingMode,
    val correctionFileProvided: Boolean,
    val correctionFileUsed: Boolean,
) {
    val reconstructsOriginalPcm: Boolean
        get() = mode == WavPackEncodingMode.LOSSLESS || mode == WavPackEncodingMode.HYBRID_CORRECTED
}

data class WavPackReplayGain(
    val trackGain: String? = null,
    val trackPeak: String? = null,
    val albumGain: String? = null,
    val albumPeak: String? = null,
)

data class WavPackArtwork(
    val tagName: String,
    val fileName: String?,
    val mimeType: String?,
    val bytes: ByteArray,
)

data class WavPackMetadata(
    val sampleRate: Int,
    val nativeSampleRate: Int,
    val bitsPerSample: Int,
    val bytesPerSample: Int,
    val channelCount: Int,
    val channelMask: Long,
    val channelIdentities: List<Int>,
    val channelLayoutTag: Long,
    val channelReordering: List<Int>,
    val totalFrames: Long,
    val sampleRepresentation: WavPackSampleRepresentation,
    val encoding: WavPackEncodingAssessment,
    val sourceContainer: String,
    val storedMd5: String?,
    val tags: Map<String, List<String>>,
    val replayGain: WavPackReplayGain,
    val binaryTagNames: List<String>,
    val artwork: List<WavPackArtwork>,
) {
    val durationMillis: Long?
        get() = if (sampleRate > 0 && totalFrames >= 0) {
            (totalFrames / sampleRate) * 1_000L +
                ((totalFrames % sampleRate) * 1_000L) / sampleRate
        } else {
            null
        }

    val channelLayoutDescription: String
        get() = WavPackMetadataParser.describeChannelLayout(channelIdentities)
}

object WavPackModeFlags {
    const val CORRECTION_FILE_USED = 0x1
    const val LOSSLESS = 0x2
    const val HYBRID = 0x4
    const val FLOAT = 0x8
}

object WavPackTruthModel {
    fun assess(modeFlags: Int, correctionFileProvided: Boolean): WavPackEncodingAssessment {
        val hybrid = modeFlags and WavPackModeFlags.HYBRID != 0
        val lossless = modeFlags and WavPackModeFlags.LOSSLESS != 0
        val correctionUsed = modeFlags and WavPackModeFlags.CORRECTION_FILE_USED != 0
        val mode = when {
            hybrid && correctionUsed && lossless -> WavPackEncodingMode.HYBRID_CORRECTED
            hybrid -> WavPackEncodingMode.HYBRID_LOSSY
            lossless -> WavPackEncodingMode.LOSSLESS
            else -> WavPackEncodingMode.UNKNOWN
        }
        return WavPackEncodingAssessment(
            mode = mode,
            correctionFileProvided = correctionFileProvided,
            correctionFileUsed = correctionUsed,
        )
    }
}

object WavPackMetadataParser {
    private const val COVER_ART_PREFIX = "COVER ART ("

    fun parseTextTagPairs(pairs: Array<String>): Map<String, List<String>> = buildMap {
        var index = 0
        while (index + 1 < pairs.size) {
            val key = pairs[index].trim().uppercase(Locale.ROOT)
            val values = pairs[index + 1].split('\u0000')
            if (key.isNotEmpty()) {
                put(key, getOrElse(key) { emptyList() } + values)
            }
            index += 2
        }
    }

    fun replayGain(tags: Map<String, List<String>>): WavPackReplayGain = WavPackReplayGain(
        trackGain = tags["REPLAYGAIN_TRACK_GAIN"]?.firstOrNull(),
        trackPeak = tags["REPLAYGAIN_TRACK_PEAK"]?.firstOrNull(),
        albumGain = tags["REPLAYGAIN_ALBUM_GAIN"]?.firstOrNull(),
        albumPeak = tags["REPLAYGAIN_ALBUM_PEAK"]?.firstOrNull(),
    )

    fun isArtworkTag(name: String): Boolean =
        name.uppercase(Locale.ROOT).startsWith(COVER_ART_PREFIX)

    fun parseArtwork(tagName: String, rawValue: ByteArray): WavPackArtwork? {
        if (!isArtworkTag(tagName)) return null
        val separator = rawValue.indexOf(0)
        if (separator < 0 || separator == rawValue.lastIndex) return null
        val fileName = rawValue.copyOfRange(0, separator)
            .toString(StandardCharsets.UTF_8)
            .takeIf { it.isNotBlank() }
        val image = rawValue.copyOfRange(separator + 1, rawValue.size)
        if (image.isEmpty()) return null
        return WavPackArtwork(
            tagName = tagName,
            fileName = fileName,
            mimeType = inferArtworkMimeType(image, fileName),
            bytes = image,
        )
    }

    fun describeChannelLayout(identities: List<Int>): String = identities
        .joinToString(separator = ", ") { identity ->
            when (identity) {
                1 -> "front-left"
                2 -> "front-right"
                3 -> "front-center"
                4 -> "lfe"
                5 -> "back-left"
                6 -> "back-right"
                7 -> "front-left-of-center"
                8 -> "front-right-of-center"
                9 -> "back-center"
                10 -> "side-left"
                11 -> "side-right"
                12 -> "top-center"
                13 -> "top-front-left"
                14 -> "top-front-center"
                15 -> "top-front-right"
                16 -> "top-back-left"
                17 -> "top-back-center"
                18 -> "top-back-right"
                255 -> "unassigned"
                else -> "channel-id-$identity"
            }
        }
        .ifEmpty { "unknown" }

    fun bytesToHex(value: ByteArray?): String? = value
        ?.joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    fun sourceContainer(fileFormat: Int): String = when (fileFormat) {
        0 -> "WAV"
        1 -> "W64"
        2 -> "CAF"
        3 -> "DFF"
        4 -> "DSF"
        5 -> "AIFF"
        else -> "unknown ($fileFormat)"
    }

    private fun inferArtworkMimeType(image: ByteArray, fileName: String?): String? = when {
        image.startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        image.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        image.startsWithAscii("GIF87a") || image.startsWithAscii("GIF89a") -> "image/gif"
        image.size >= 12 && image.startsWithAscii("RIFF") &&
            image.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        fileName?.lowercase(Locale.ROOT)?.endsWith(".jpg") == true ||
            fileName?.lowercase(Locale.ROOT)?.endsWith(".jpeg") == true -> "image/jpeg"
        fileName?.lowercase(Locale.ROOT)?.endsWith(".png") == true -> "image/png"
        fileName?.lowercase(Locale.ROOT)?.endsWith(".gif") == true -> "image/gif"
        fileName?.lowercase(Locale.ROOT)?.endsWith(".webp") == true -> "image/webp"
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index ->
            (this[index].toInt() and 0xff) == prefix[index]
        }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        startsWith(*prefix.toByteArray(StandardCharsets.US_ASCII).map { it.toInt() and 0xff }.toIntArray())
}
