package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTransportTelemetryTest {
    @Test
    fun `recovery reason is shown briefly then replaced by age only`() {
        var now = 1_000_000_000L
        val telemetry = AudioTransportTelemetry { now }
        telemetry.configure(48_000, 2, 8_192)
        telemetry.recordRecovery("app selection changed")

        val fresh = telemetry.snapshot().compactString()
        assertTrue(fresh.contains("recoveryReason=app selection changed"))
        assertTrue(fresh.contains("recoveryAgeMs=0"))

        now += 11_000_000_000L
        val stale = telemetry.snapshot().compactString()
        assertFalse(stale.contains("recoveryReason="))
        assertTrue(stale.contains("recoveryAgeMs=11000"))
    }

    @Test
    fun `underruns before two current-track buffers are classified as startup priming`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)
        telemetry.recordActiveTrackUnderrunCount(1)

        val snapshot = telemetry.snapshot()
        assertEquals(1, snapshot.underrunCount)
        assertEquals(1, snapshot.startupPrimingUnderrunCount)
        assertEquals(0, snapshot.runtimeStarvationUnderrunCount)
    }

    @Test
    fun `underruns after two current-track buffers are classified as runtime starvation`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)
        telemetry.recordWrite(successfulTransfer(6_144))
        telemetry.recordActiveTrackUnderrunCount(2)

        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.underrunCount)
        assertEquals(0, snapshot.startupPrimingUnderrunCount)
        assertEquals(2, snapshot.runtimeStarvationUnderrunCount)
    }

    @Test
    fun `track reconfiguration resets priming progress without resetting epoch totals`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)
        telemetry.recordWrite(successfulTransfer(6_144))
        telemetry.recordActiveTrackUnderrunCount(1)
        telemetry.configure(48_000, 2, 3_072)
        telemetry.recordActiveTrackUnderrunCount(1)

        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.underrunCount)
        assertEquals(1, snapshot.startupPrimingUnderrunCount)
        assertEquals(1, snapshot.runtimeStarvationUnderrunCount)
        assertEquals(2, snapshot.trackGeneration)
        assertEquals(0L, snapshot.activeTrackWrittenSamples)
    }

    private fun successfulTransfer(samples: Int) = AudioTransferResult(
        requestedSamples = samples,
        transferredSamples = samples,
        operationCount = 1,
        partialOperationCount = 0,
        zeroProgressCount = 0,
        errorCode = null,
    )
}
