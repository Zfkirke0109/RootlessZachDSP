package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavPackTruthModelTest {
    @Test
    fun `standard lossless is distinct from hybrid corrected`() {
        val assessment = WavPackTruthModel.assess(
            modeFlags = WavPackModeFlags.LOSSLESS,
            correctionFileProvided = false,
        )

        assertEquals(WavPackEncodingMode.LOSSLESS, assessment.mode)
        assertTrue(assessment.reconstructsOriginalPcm)
        assertFalse(assessment.correctionFileUsed)
    }

    @Test
    fun `hybrid core stays lossy even when a correction file was merely provided`() {
        val assessment = WavPackTruthModel.assess(
            modeFlags = WavPackModeFlags.HYBRID,
            correctionFileProvided = true,
        )

        assertEquals(WavPackEncodingMode.HYBRID_LOSSY, assessment.mode)
        assertTrue(assessment.correctionFileProvided)
        assertFalse(assessment.correctionFileUsed)
        assertFalse(assessment.reconstructsOriginalPcm)
    }

    @Test
    fun `hybrid is corrected only when library reports wvc and lossless`() {
        val assessment = WavPackTruthModel.assess(
            modeFlags = WavPackModeFlags.HYBRID or
                WavPackModeFlags.LOSSLESS or
                WavPackModeFlags.CORRECTION_FILE_USED,
            correctionFileProvided = true,
        )

        assertEquals(WavPackEncodingMode.HYBRID_CORRECTED, assessment.mode)
        assertTrue(assessment.correctionFileUsed)
        assertTrue(assessment.reconstructsOriginalPcm)
    }

    @Test
    fun `wvc flag without lossless evidence is not called corrected`() {
        val assessment = WavPackTruthModel.assess(
            modeFlags = WavPackModeFlags.HYBRID or WavPackModeFlags.CORRECTION_FILE_USED,
            correctionFileProvided = true,
        )

        assertEquals(WavPackEncodingMode.HYBRID_LOSSY, assessment.mode)
        assertFalse(assessment.reconstructsOriginalPcm)
    }

    @Test
    fun `tag parser preserves repeated and null separated values`() {
        val tags = WavPackMetadataParser.parseTextTagPairs(
            arrayOf(
                "Artist",
                "One\u0000Two",
                "ARTIST",
                "Three",
                "ReplayGain_Track_Gain",
                "-7.20 dB",
                "REPLAYGAIN_TRACK_PEAK",
                "0.9234",
                "dangling-key",
            ),
        )

        assertEquals(listOf("One", "Two", "Three"), tags["ARTIST"])
        val replayGain = WavPackMetadataParser.replayGain(tags)
        assertEquals("-7.20 dB", replayGain.trackGain)
        assertEquals("0.9234", replayGain.trackPeak)
        assertNull(replayGain.albumGain)
    }

    @Test
    fun `ape cover art filename is separated from image bytes`() {
        val raw = "front.png\u0000".encodeToByteArray() + byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
            0x01,
        )

        val artwork = WavPackMetadataParser.parseArtwork("Cover Art (Front)", raw)

        requireNotNull(artwork)
        assertEquals("front.png", artwork.fileName)
        assertEquals("image/png", artwork.mimeType)
        assertEquals(9, artwork.bytes.size)
        assertEquals(0x89, artwork.bytes.first().toInt() and 0xff)
    }

    @Test
    fun `non artwork binary tags are not mislabeled as artwork`() {
        val value = "file.bin\u0000payload".encodeToByteArray()
        assertNull(WavPackMetadataParser.parseArtwork("Binary Notes", value))
    }

    @Test
    fun `channel identities preserve microsoft and unknown positions`() {
        assertEquals(
            "front-left, front-right, front-center, lfe, side-left, side-right, unassigned, channel-id-33",
            WavPackMetadataParser.describeChannelLayout(listOf(1, 2, 3, 4, 10, 11, 255, 33)),
        )
    }
}
