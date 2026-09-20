
package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferControllerTest {
    @Test
    fun `grows after repeated pressure`() {
        val controller = AdaptiveBufferController(
            minimumSamples = 384,
            initialSamples = 768,
            maximumSamples = 3_072,
            alignmentSamples = 384,
            pressureIntervalsBeforeGrow = 2,
        )

        assertFalse(controller.observe(AdaptiveBufferController.Observation(underrunDelta = 1)).changed)
        val decision = controller.observe(AdaptiveBufferController.Observation(underrunDelta = 1))

        assertTrue(decision.changed)
        assertEquals(1_536, controller.currentSamples)
        assertEquals(AdaptiveBufferController.Reason.UNDERRUN, decision.reason)
        assertEquals(0, controller.currentSamples % 384)
    }

    @Test
    fun `stable load shrinks cautiously but never below independent minimum`() {
        val controller = AdaptiveBufferController(
            minimumSamples = 3_072,
            initialSamples = 16_128,
            maximumSamples = 16_128,
            alignmentSamples = 384,
            stableIntervalsBeforeShrink = 2,
            postShrinkProbationIntervals = 1,
        )

        assertFalse(controller.observe(AdaptiveBufferController.Observation(processingLoadRatio = 0.1)).changed)
        assertTrue(controller.observe(AdaptiveBufferController.Observation(processingLoadRatio = 0.1)).changed)
        assertEquals(8_064, controller.currentSamples)

        repeat(8) {
            controller.observe(AdaptiveBufferController.Observation(processingLoadRatio = 0.1))
        }

        assertEquals(3_072, controller.currentSamples)
        assertEquals(0, controller.currentSamples % 384)
    }

    @Test
    fun `pressure during shrink probation restores size and learns a stable floor`() {
        val controller = AdaptiveBufferController(
            minimumSamples = 384,
            initialSamples = 1_536,
            maximumSamples = 3_072,
            alignmentSamples = 384,
            pressureIntervalsBeforeGrow = 2,
            stableIntervalsBeforeShrink = 2,
            postShrinkProbationIntervals = 5,
        )

        controller.observe(AdaptiveBufferController.Observation(processingLoadRatio = 0.1))
        val shrink = controller.observe(
            AdaptiveBufferController.Observation(processingLoadRatio = 0.1),
        )
        assertEquals(768, shrink.newSamples)
        assertEquals(AdaptiveBufferController.Reason.STABLE_SHRINK, shrink.reason)

        val rollback = controller.observe(
            AdaptiveBufferController.Observation(underrunDelta = 1),
        )
        assertEquals(1_536, rollback.newSamples)
        assertEquals(AdaptiveBufferController.Reason.SHRINK_ROLLBACK, rollback.reason)

        repeat(20) {
            controller.observe(AdaptiveBufferController.Observation(processingLoadRatio = 0.1))
        }
        assertEquals(1_536, controller.currentSamples)

        assertFalse(
            controller.observe(AdaptiveBufferController.Observation(deadlineMissDelta = 1)).changed,
        )
        assertTrue(
            controller.observe(AdaptiveBufferController.Observation(deadlineMissDelta = 1)).changed,
        )
        assertEquals(3_072, controller.currentSamples)
    }

    @Test
    fun `s23 ultra policy uses exact 192-frame stereo burst multiples`() {
        val policy = AdaptiveBufferController.burstAlignedPolicy(
            framesPerBurst = 192,
            channelCount = 2,
            configuredSamples = 16_384,
            maximumSamples = 16_384,
            minimumBursts = 8,
            initialBursts = 16,
        )

        assertEquals(384, policy.alignmentSamples)
        assertEquals(3_072, policy.minimumSamples)
        assertEquals(16_128, policy.initialSamples)
        assertEquals(16_128, policy.maximumSamples)
        assertEquals(0, policy.minimumSamples % policy.alignmentSamples)
        assertEquals(0, policy.initialSamples % policy.alignmentSamples)
        assertEquals(0, policy.maximumSamples % policy.alignmentSamples)
    }

    @Test
    fun `configured size influences initial target but not adaptive minimum`() {
        val policy = AdaptiveBufferController.burstAlignedPolicy(
            framesPerBurst = 192,
            channelCount = 2,
            configuredSamples = 7_000,
            minimumBursts = 8,
            initialBursts = 16,
        )

        assertEquals(3_072, policy.minimumSamples)
        assertEquals(7_296, policy.initialSamples)
    }

    @Test
    fun `duration accounts for interleaved channels`() {
        assertEquals(10_000_000L, AdaptiveBufferController.bufferDurationNanos(960, 48_000, 2))
    }
}
