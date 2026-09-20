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
    fun `processing window reports nearest-rank percentiles and deadline streaks`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)

        for (millis in 1L..100L) {
            telemetry.recordProcessing(
                durationNanos = millis * 1_000_000L,
                deadlineNanos = 50_000_000L,
                bypassed = false,
            )
        }

        val snapshot = telemetry.snapshot()
        assertEquals(100, snapshot.processingWindowSamples)
        assertEquals(50_000_000L, snapshot.processingP50Nanos)
        assertEquals(95_000_000L, snapshot.processingP95Nanos)
        assertEquals(99_000_000L, snapshot.processingP99Nanos)
        assertEquals(50L, snapshot.deadlineMissCount)
        assertEquals(50, snapshot.currentConsecutiveDeadlineMisses)
        assertEquals(50, snapshot.maxConsecutiveDeadlineMisses)
        assertTrue(snapshot.compactString().contains("processP95Ns=95000000"))
    }

    @Test
    fun `reconfiguration resets active processing window`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)
        telemetry.recordProcessing(40_000_000L, 32_000_000L, bypassed = false)
        assertEquals(1, telemetry.snapshot().processingWindowSamples)

        telemetry.configure(48_000, 2, 6_144)
        val reset = telemetry.snapshot()
        assertEquals(0, reset.processingWindowSamples)
        assertEquals(0, reset.currentConsecutiveDeadlineMisses)
        assertEquals(0, reset.maxConsecutiveDeadlineMisses)
    }

    @Test
    fun `new AudioTrack generation baselines its first underrun observation`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 3_072)

        telemetry.recordActiveTrackUnderrunCount(1)
        var snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.underrunCount)
        assertEquals(1, snapshot.activeTrackUnderrunCount)

        telemetry.recordActiveTrackUnderrunCount(2)
        snapshot = telemetry.snapshot()
        assertEquals(1, snapshot.underrunCount)
        assertEquals(2, snapshot.activeTrackUnderrunCount)

        telemetry.configure(48_000, 2, 6_144)
        telemetry.recordActiveTrackUnderrunCount(1)
        snapshot = telemetry.snapshot()
        assertEquals(1, snapshot.underrunCount)
        assertEquals(1, snapshot.activeTrackUnderrunCount)
        assertEquals(2, snapshot.trackGeneration)
    }

    @Test
    fun `adaptive stable shrink is recorded as reconfiguration not recovery`() {
        val telemetry = AudioTransportTelemetry()
        telemetry.configure(48_000, 2, 6_144)

        telemetry.recordRecovery("adaptive buffer stable_shrink")

        val snapshot = telemetry.snapshot()
        assertEquals(0L, snapshot.recoveryCount)
        assertEquals(1L, snapshot.reconfigurationCount)
        assertEquals("adaptive buffer stable_shrink", snapshot.lastReconfigurationReason)
    }
}
