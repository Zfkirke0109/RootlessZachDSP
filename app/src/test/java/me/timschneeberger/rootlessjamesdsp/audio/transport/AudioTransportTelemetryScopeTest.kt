package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTransportTelemetryScopeTest {
    @Test
    fun expectedReconfigurationIsSeparateFromRecoveryAndTrackUnderrunsAreScoped() {
        var now = 1_000_000_000L
        val telemetry = AudioTransportTelemetry { now }

        telemetry.configure(48_000, 2, 8_192)
        telemetry.recordReconfiguration("capture policy changed")
        telemetry.recordActiveTrackUnderrunCount(3)

        var snapshot = telemetry.snapshot()
        assertEquals(0L, snapshot.recoveryCount)
        assertEquals(1L, snapshot.reconfigurationCount)
        assertEquals(3, snapshot.underrunCount)
        assertEquals(3, snapshot.activeTrackUnderrunCount)
        assertEquals(1, snapshot.trackGeneration)
        assertTrue(snapshot.compactString().contains("reconfigurationReason=capture policy changed"))

        now += 1_000_000L
        telemetry.configure(48_000, 2, 16_384)
        telemetry.recordActiveTrackUnderrunCount(2)
        snapshot = telemetry.snapshot()

        assertEquals(5, snapshot.underrunCount)
        assertEquals(2, snapshot.activeTrackUnderrunCount)
        assertEquals(2, snapshot.trackGeneration)
    }

    @Test
    fun unexpectedRecoveryRemainsAnAnomaly() {
        val telemetry = AudioTransportTelemetry { 2_000_000_000L }
        telemetry.configure(48_000, 2, 8_192)
        telemetry.recordRecovery("AudioRecord error -3")

        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.recoveryCount)
        assertEquals(0L, snapshot.reconfigurationCount)
        assertEquals("AudioRecord error -3", snapshot.lastRecoveryReason)
    }
}
