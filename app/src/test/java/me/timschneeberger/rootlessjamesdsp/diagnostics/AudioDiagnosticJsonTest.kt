package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransferResult
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticJsonTest {
    @Test
    fun `transport snapshot is versioned escaped and privacy-safe`() {
        val snapshot = populatedSnapshot()
        val json = AudioDiagnosticJson.transportSnapshot(
            snapshot = snapshot,
            build = DiagnosticBuildIdentity(
                applicationId = "com.example.\"debug\"",
                versionName = "2.0\nalpha",
                versionCode = 100,
                commit = "abc\\def",
            ),
            engineEpoch = "epoch-1",
            wallClockEpochMs = 1234,
            droppedEventCount = 2,
        )

        assertTrue(json.startsWith("{\"schemaVersion\":1"))
        assertTrue(json.contains("\"eventType\":\"TRANSPORT_SNAPSHOT\""))
        assertTrue(json.contains("com.example.\\\"debug\\\""))
        assertTrue(json.contains("2.0\\nalpha"))
        assertTrue(json.contains("abc\\\\def"))
        assertTrue(json.contains("\"readSamples\":8192"))
        assertTrue(json.contains("\"writtenSamples\":8192"))
        assertTrue(json.contains("\"lastRecoveryAgeMs\":0"))
        assertTrue(json.contains("\"droppedEventCount\":2"))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\r'))
    }

    @Test
    fun `recovery delta includes reason while ordinary deltas do not`() {
        val snapshot = populatedSnapshot()
        val build = DiagnosticBuildIdentity("app", "version", 1, "commit")

        val recovery = AudioDiagnosticJson.counterDelta(
            AudioDiagnosticEventType.RECOVERY,
            1,
            snapshot,
            build,
            "epoch",
            1234,
        )
        val underrun = AudioDiagnosticJson.counterDelta(
            AudioDiagnosticEventType.UNDERRUN,
            2,
            snapshot,
            build,
            "epoch",
            1234,
        )

        assertTrue(recovery.contains("\"eventType\":\"RECOVERY\""))
        assertTrue(recovery.contains("\"reason\":\"app selection changed\""))
        assertTrue(underrun.contains("\"eventType\":\"UNDERRUN\""))
        assertFalse(underrun.contains("\"reason\""))
    }

    @Test
    fun `control characters are escaped`() {
        val escaped = AudioDiagnosticJson.escape("a\tb\u0001c\"d\\e")
        assertTrue(escaped.contains("\\t"))
        assertTrue(escaped.contains("\\u0001"))
        assertTrue(escaped.contains("\\\""))
        assertTrue(escaped.contains("\\\\"))
    }

    private fun populatedSnapshot(): AudioTransportTelemetry.Snapshot {
        var now = 1_000_000_000L
        val telemetry = AudioTransportTelemetry { now }
        telemetry.configure(48_000, 2, 8_192)
        val complete = AudioTransferResult(
            requestedSamples = 8_192,
            transferredSamples = 8_192,
            operationCount = 1,
            partialOperationCount = 0,
            zeroProgressCount = 0,
        )
        telemetry.recordRead(complete)
        telemetry.recordWrite(complete)
        telemetry.recordProcessing(8_000_000L, 85_000_000L, bypassed = false)
        telemetry.recordUnderrunDelta(2)
        telemetry.recordRecovery("app selection changed")
        now += 1_000L
        return telemetry.snapshot()
    }
}
