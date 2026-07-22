package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WavPackSampleNormalizerTest {
    @Test
    fun `left aligns integer samples without changing significant bits`() {
        val samples = intArrayOf(0x123456, -0x123457, 99)
        WavPackSampleNormalizer.leftAlignIntegerInPlace(samples, 2, 24)
        assertArrayEquals(intArrayOf(0x12345600, -0x12345700, 99), samples)
    }

    @Test
    fun `converts finite float words and clamps exceptional range`() {
        val samples = intArrayOf(
            (-1f).toBits(),
            (-0.5f).toBits(),
            0f.toBits(),
            0.5f.toBits(),
            2f.toBits(),
            Float.NaN.toBits(),
        )
        WavPackSampleNormalizer.floatBitsToPcm32InPlace(samples, samples.size)
        assertArrayEquals(
            intArrayOf(Int.MIN_VALUE, -1_073_741_824, 0, 1_073_741_824, Int.MAX_VALUE, 0),
            samples,
        )
    }

    @Test
    fun `only canonical stereo identity and order is eligible for playback`() {
        org.junit.Assert.assertTrue(
            WavPackChannelOrder.isCanonical(2, listOf(1, 2), emptyList()),
        )
        org.junit.Assert.assertFalse(
            WavPackChannelOrder.isCanonical(2, listOf(2, 1), emptyList()),
        )
        org.junit.Assert.assertFalse(
            WavPackChannelOrder.isCanonical(2, listOf(1, 2), listOf(1, 0)),
        )
    }
}
