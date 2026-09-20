package me.timschneeberger.rootlessjamesdsp.audio.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackDrainTest {
    @Test
    fun `target is pending before playback head reaches it`() {
        assertFalse(AudioTrackDrain.hasReachedTarget(900, 1_000))
    }

    @Test
    fun `target is reached at equality or a small overrun`() {
        assertTrue(AudioTrackDrain.hasReachedTarget(1_000, 1_000))
        assertTrue(AudioTrackDrain.hasReachedTarget(1_001, 1_000))
    }

    @Test
    fun `comparison handles unsigned playback head wrap`() {
        assertFalse(AudioTrackDrain.hasReachedTarget(0xffff_fff0L, 0x10L))
        assertTrue(AudioTrackDrain.hasReachedTarget(0x11L, 0x10L))
    }
}
