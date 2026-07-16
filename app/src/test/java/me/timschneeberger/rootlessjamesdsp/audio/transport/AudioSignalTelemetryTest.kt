package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AudioSignalTelemetryTest {
    @Test
    fun `identical float input and output remains unchanged`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 1234L)
        val samples = floatArrayOf(-0.5f, 0f, 0.5f, 1f)

        telemetry.recordFloat(samples, samples.copyOf(), samples.size)
        val snapshot = telemetry.snapshot()

        assertEquals(4L, snapshot.sampleCount)
        assertEquals(sqrt(0.375), snapshot.inputRms, 1.0e-6)
        assertEquals(snapshot.inputRms, snapshot.outputRms, 1.0e-12)
        assertEquals(1.0, snapshot.inputPeak, 1.0e-12)
        assertEquals(1L, snapshot.inputSilenceRatio.times(snapshot.sampleCount).toLong())
        assertEquals(1L, snapshot.inputClippedSamples)
        assertEquals(0L, snapshot.changedSamples)
        assertFalse(snapshot.outputChanged)
    }

    @Test
    fun `changed output reports energy peak clipping and changed ratio`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 99L)
        val input = floatArrayOf(0f, 0.25f, -0.25f, 0.5f)
        val output = floatArrayOf(0f, 0.5f, -0.5f, 1.1f)

        telemetry.recordFloat(input, output, input.size)
        val snapshot = telemetry.snapshot()

        assertTrue(snapshot.outputChanged)
        assertEquals(3L, snapshot.changedSamples)
        assertEquals(0.75, snapshot.changedSampleRatio, 1.0e-12)
        assertTrue(snapshot.outputRms > snapshot.inputRms)
        assertEquals(1.1, snapshot.outputPeak, 1.0e-6)
        assertEquals(1L, snapshot.outputClippedSamples)
        assertNotEquals(snapshot.inputHash, snapshot.outputHash)
    }

    @Test
    fun `short path normalizes signed pcm and detects dc offset`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 7L)
        val input = shortArrayOf(0, 16_384, 16_384, 0)
        val output = shortArrayOf(8_192, 8_192, 8_192, 8_192)

        telemetry.recordShort(input, output, input.size)
        val snapshot = telemetry.snapshot()

        assertEquals(0.25, snapshot.inputDcOffset, 1.0e-12)
        assertEquals(0.25, snapshot.outputDcOffset, 1.0e-12)
        assertEquals(4L, snapshot.changedSamples)
        assertTrue(snapshot.outputChanged)
    }

    @Test
    fun `non finite float samples are converted to zero`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 11L)
        telemetry.recordFloat(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY),
            floatArrayOf(0f, 0f),
            2,
        )

        val snapshot = telemetry.snapshot()
        assertEquals(0.0, snapshot.inputRms, 0.0)
        assertEquals(1.0, snapshot.inputSilenceRatio, 0.0)
        assertFalse(snapshot.outputChanged)
    }

    @Test
    fun `reset clears aggregates and replaces session hash seed`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 1L)
        telemetry.recordFloat(floatArrayOf(0.5f), floatArrayOf(0.25f), 1)
        val before = telemetry.snapshot()

        telemetry.reset(newHashSeed = 2L)
        val after = telemetry.snapshot()

        assertEquals(0L, after.sampleCount)
        assertFalse(after.outputChanged)
        assertNotEquals(before.inputHash, after.inputHash)
    }
}
