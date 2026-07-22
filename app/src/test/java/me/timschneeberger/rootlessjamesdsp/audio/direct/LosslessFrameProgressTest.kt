package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessFrameProgressTest {
    @Test
    fun knownTotal_acceptsExactEndOfStream() {
        val progress = LosslessFrameProgress(10)

        progress.record(4)
        progress.record(6)
        progress.verifyEndOfStream()

        assertEquals(10, progress.decodedFrames)
    }

    @Test(expected = IllegalStateException::class)
    fun knownTotal_rejectsEarlyEndOfStream() {
        val progress = LosslessFrameProgress(10)

        progress.record(9)
        progress.verifyEndOfStream()
    }

    @Test(expected = IllegalStateException::class)
    fun knownTotal_rejectsDecoderOverrun() {
        LosslessFrameProgress(10).record(11)
    }

    @Test
    fun unknownTotal_acceptsEndOfStream() {
        val progress = LosslessFrameProgress(0)

        progress.record(3)
        progress.verifyEndOfStream()
    }
}
