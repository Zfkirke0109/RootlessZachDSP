package me.timschneeberger.rootlessjamesdsp.audio.transport

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
}
