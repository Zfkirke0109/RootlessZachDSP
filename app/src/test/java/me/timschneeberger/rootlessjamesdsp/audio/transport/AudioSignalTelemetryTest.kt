package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
        assertEquals(1, snapshot.hashStride)
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
    fun `decimated hashes preserve full metrics and changed sample detection`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 45L, hashStride = 4)
        val input = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val output = input.copyOf().apply { this[3] = 0.9f }

        telemetry.recordFloat(input, output, input.size)
        val snapshot = telemetry.snapshot()

        assertEquals(8L, snapshot.sampleCount)
        assertEquals(4, snapshot.hashStride)
        assertEquals(1L, snapshot.changedSamples)
        assertTrue(snapshot.outputChanged)
        assertEquals(0.7, snapshot.inputPeak, 1.0e-6)
        assertEquals(0.9, snapshot.outputPeak, 1.0e-6)
    }

    @Test
    fun `hash stride must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioSignalTelemetry(hashSeed = 1L, hashStride = 0)
        }
    }

    @Test
    fun `float range records only requested input and output windows`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 31L)
        val input = floatArrayOf(9f, 0.25f, -0.25f, 8f)
        val output = floatArrayOf(7f, 0.5f, -0.5f, 6f)

        telemetry.recordFloat(input, 1, output, 1, 2)
        val snapshot = telemetry.snapshot()

        assertEquals(2L, snapshot.sampleCount)
        assertEquals(0.25, snapshot.inputPeak, 1.0e-12)
        assertEquals(0.5, snapshot.outputPeak, 1.0e-12)
        assertEquals(2L, snapshot.changedSamples)
    }

    @Test
    fun `short range supports offset input and zero based output`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 17L)
        val input = shortArrayOf(30_000, 8_192, -8_192, 30_000)
        val output = shortArrayOf(4_096, -4_096)

        telemetry.recordShort(input, 1, output, 0, 2)
        val snapshot = telemetry.snapshot()

        assertEquals(2L, snapshot.sampleCount)
        assertEquals(0.25, snapshot.inputPeak, 1.0e-12)
        assertEquals(0.125, snapshot.outputPeak, 1.0e-12)
        assertEquals(2L, snapshot.changedSamples)
    }

    @Test
    fun `invalid ranges are rejected before reading buffers`() {
        val telemetry = AudioSignalTelemetry(hashSeed = 13L)
        assertThrows(IllegalArgumentException::class.java) {
            telemetry.recordFloat(floatArrayOf(1f), 1, floatArrayOf(1f), 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            telemetry.recordShort(shortArrayOf(1), 0, shortArrayOf(1), -1, 1)
        }
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
