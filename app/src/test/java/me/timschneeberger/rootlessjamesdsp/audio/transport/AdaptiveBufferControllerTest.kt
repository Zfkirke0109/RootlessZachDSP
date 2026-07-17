package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.*
import org.junit.Test

class AdaptiveBufferControllerTest {
    @Test fun `grows after repeated pressure`() {
        val controller = AdaptiveBufferController(512, 512, 4096, pressureIntervalsBeforeGrow = 2)
        assertFalse(controller.observe(AdaptiveBufferController.Observation(underrunDelta = 1)).changed)
        val decision = controller.observe(AdaptiveBufferController.Observation(underrunDelta = 1))
        assertTrue(decision.changed)
        assertEquals(1024, controller.currentSamples)
        assertEquals(AdaptiveBufferController.Reason.UNDERRUN, decision.reason)
    }

    @Test fun `duration accounts for interleaved channels`() {
        assertEquals(10_000_000L, AdaptiveBufferController.bufferDurationNanos(960, 48_000, 2))
    }
}
