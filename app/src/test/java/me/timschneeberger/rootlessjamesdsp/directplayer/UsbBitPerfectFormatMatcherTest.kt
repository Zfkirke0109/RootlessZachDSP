package me.timschneeberger.rootlessjamesdsp.directplayer

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectFormatMatcherTest {
    private val pcm24 = SourceAudioFormat(
        sampleRateHz = 96_000,
        channelCount = 2,
        pcmEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
        bitDepth = 24,
        mimeType = "audio/raw",
    )

    @Test
    fun `selects only exact bit-perfect PCM mixer`() {
        val candidates = listOf(
            UsbBitPerfectFormatMatcher.MixerCandidate(48_000, 2, AudioFormat.ENCODING_PCM_24BIT_PACKED, true, 0),
            UsbBitPerfectFormatMatcher.MixerCandidate(96_000, 2, AudioFormat.ENCODING_PCM_24BIT_PACKED, false, 1),
            UsbBitPerfectFormatMatcher.MixerCandidate(96_000, 2, AudioFormat.ENCODING_PCM_24BIT_PACKED, true, 2),
        )

        val decision = UsbBitPerfectFormatMatcher.select(pcm24, candidates)

        assertTrue(decision is UsbBitPerfectFormatMatcher.Decision.Match)
        assertEquals(
            2,
            (decision as UsbBitPerfectFormatMatcher.Decision.Match).candidate.platformIndex,
        )
    }

    @Test
    fun `rejects compressed source until decoder output PCM is verified`() {
        val flac = SourceAudioFormat(
            sampleRateHz = 96_000,
            channelCount = 2,
            pcmEncoding = AudioFormat.ENCODING_INVALID,
            bitDepth = 24,
            mimeType = "audio/flac",
        )

        val decision = UsbBitPerfectFormatMatcher.select(
            flac,
            listOf(
                UsbBitPerfectFormatMatcher.MixerCandidate(
                    96_000,
                    2,
                    AudioFormat.ENCODING_PCM_24BIT_PACKED,
                    true,
                    0,
                ),
            ),
        )

        assertEquals(
            UsbBitPerfectFormatMatcher.RejectionReason.SOURCE_NOT_LINEAR_PCM,
            (decision as UsbBitPerfectFormatMatcher.Decision.Rejected).reason,
        )
    }

    @Test
    fun `rejects near match rather than silently resampling`() {
        val decision = UsbBitPerfectFormatMatcher.select(
            pcm24,
            listOf(
                UsbBitPerfectFormatMatcher.MixerCandidate(
                    192_000,
                    2,
                    AudioFormat.ENCODING_PCM_24BIT_PACKED,
                    true,
                    0,
                ),
            ),
        )

        assertEquals(
            UsbBitPerfectFormatMatcher.RejectionReason.NO_EXACT_FORMAT_MATCH,
            (decision as UsbBitPerfectFormatMatcher.Decision.Rejected).reason,
        )
    }
}
