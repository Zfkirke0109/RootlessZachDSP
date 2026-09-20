package me.timschneeberger.rootlessjamesdsp.player.codec.flac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlacMetadataParserTest {
    @Test
    fun `preserves repeated tags and parses replaygain without applying it`() {
        val tags = FlacMetadataParser.parseTags(
            arrayOf(
                "artist=One",
                "ARTIST=Two",
                "REPLAYGAIN_TRACK_GAIN=-7.20 dB",
                "REPLAYGAIN_TRACK_PEAK=0.9234",
                "malformed",
            ),
        )

        assertEquals(listOf("One", "Two"), tags["ARTIST"])
        val replayGain = FlacMetadataParser.replayGain(tags)
        assertEquals("-7.20 dB", replayGain.trackGain)
        assertEquals("0.9234", replayGain.trackPeak)
        assertNull(replayGain.albumGain)
    }

    @Test
    fun `reports canonical flac layouts`() {
        assertEquals("mono", FlacMetadataParser.channelLayout(1))
        assertEquals(
            "front-left, front-right, front-center, lfe, back-left, back-right",
            FlacMetadataParser.channelLayout(6),
        )
        assertEquals("unknown (9 channels)", FlacMetadataParser.channelLayout(9))
    }
}
