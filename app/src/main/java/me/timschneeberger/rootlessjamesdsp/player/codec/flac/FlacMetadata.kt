package me.timschneeberger.rootlessjamesdsp.player.codec.flac

import java.util.Locale

data class FlacReplayGain(
    val trackGain: String? = null,
    val trackPeak: String? = null,
    val albumGain: String? = null,
    val albumPeak: String? = null,
)

data class FlacArtwork(
    val mimeType: String?,
    val bytes: ByteArray,
)

data class FlacMetadata(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channelCount: Int,
    val channelLayout: String,
    val totalFrames: Long,
    val streamMd5: String?,
    val tags: Map<String, List<String>>,
    val replayGain: FlacReplayGain,
    val artwork: FlacArtwork?,
) {
    val durationMillis: Long?
        get() = if (sampleRate > 0 && totalFrames > 0) {
            (totalFrames * 1_000L) / sampleRate
        } else {
            null
        }
}

object FlacMetadataParser {
    fun parseTags(comments: Array<String>): Map<String, List<String>> = buildMap {
        comments.forEach { comment ->
            val separator = comment.indexOf('=')
            if (separator <= 0) return@forEach
            val key = comment.substring(0, separator).trim().uppercase(Locale.ROOT)
            if (key.isEmpty()) return@forEach
            val value = comment.substring(separator + 1)
            put(key, getOrElse(key) { emptyList() } + value)
        }
    }

    fun replayGain(tags: Map<String, List<String>>): FlacReplayGain = FlacReplayGain(
        trackGain = tags["REPLAYGAIN_TRACK_GAIN"]?.firstOrNull(),
        trackPeak = tags["REPLAYGAIN_TRACK_PEAK"]?.firstOrNull(),
        albumGain = tags["REPLAYGAIN_ALBUM_GAIN"]?.firstOrNull(),
        albumPeak = tags["REPLAYGAIN_ALBUM_PEAK"]?.firstOrNull(),
    )

    /** FLAC defines canonical channel assignments for one through eight channels. */
    fun channelLayout(channelCount: Int): String = when (channelCount) {
        1 -> "mono"
        2 -> "front-left, front-right"
        3 -> "front-left, front-right, front-center"
        4 -> "front-left, front-right, back-left, back-right"
        5 -> "front-left, front-right, front-center, back-left, back-right"
        6 -> "front-left, front-right, front-center, lfe, back-left, back-right"
        7 -> "front-left, front-right, front-center, lfe, back-center, side-left, side-right"
        8 -> "front-left, front-right, front-center, lfe, back-left, back-right, side-left, side-right"
        else -> "unknown ($channelCount channels)"
    }
}
