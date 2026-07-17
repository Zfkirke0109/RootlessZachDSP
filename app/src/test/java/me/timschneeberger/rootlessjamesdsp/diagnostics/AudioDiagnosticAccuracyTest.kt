package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticAccuracyTest {
    private val build = DiagnosticBuildIdentity("app", "1", 1, "abc")

    @Test
    fun expectedReconfigurationUsesItsOwnEventType() {
        val snapshot = transportSnapshot(
            reconfigurationCount = 1,
            lastReconfigurationReason = "restricted-session setting changed",
            lastReconfigurationAtNanos = 900_000_000L,
        )
        val json = AudioDiagnosticJson.counterDelta(
            AudioDiagnosticEventType.RECONFIGURATION,
            1,
            snapshot,
            build,
            "epoch",
            123,
        )

        assertTrue(json.contains("\"eventType\":\"RECONFIGURATION\""))
        assertTrue(json.contains("\"expected\":true"))
        assertTrue(json.contains("restricted-session setting changed"))
        assertFalse(json.contains("\"eventType\":\"RECOVERY\""))
    }

    @Test
    fun signalSnapshotCarriesExplicitAudioTrackBoundary() {
        val signal = AudioSignalTelemetry.Snapshot(
            capturedAtNanos = 1,
            sampleCount = 2,
            inputRms = 0.1,
            outputRms = 0.2,
            inputPeak = 0.2,
            outputPeak = 0.3,
            inputDcOffset = 0.0,
            outputDcOffset = 0.0,
            inputSilenceRatio = 0.0,
            outputSilenceRatio = 0.0,
            inputClippedSamples = 0,
            outputClippedSamples = 0,
            changedSamples = 2,
            changedSampleRatio = 1.0,
            inputHash = 1,
            outputHash = 2,
            hashStride = 16,
            outputChanged = true,
        )
        val json = AudioDiagnosticJson.signalSnapshot(
            signal,
            build,
            "epoch",
            123,
            AudioDiagnosticJson.TRACK_INPUT_SIGNAL_MEASUREMENT_BOUNDARY,
        )

        assertTrue(json.contains("CAPTURED_INPUT_TO_AUDIO_TRACK_INPUT"))
    }

    private fun transportSnapshot(
        reconfigurationCount: Long = 0,
        lastReconfigurationReason: String? = null,
        lastReconfigurationAtNanos: Long? = null,
    ) = AudioTransportTelemetry.Snapshot(
        capturedAtNanos = 1_000_000_000L,
        sampleRate = 48_000,
        channelCount = 2,
        bufferSamples = 8_192,
        totalReadSamples = 0,
        totalWrittenSamples = 0,
        partialReadOperations = 0,
        partialWriteOperations = 0,
        zeroProgressOperations = 0,
        ioErrorCount = 0,
        recoveryCount = 0,
        underrunCount = 0,
        deadlineMissCount = 0,
        bypassBufferCount = 0,
        lastProcessingNanos = 0,
        maxProcessingNanos = 0,
        processingLoadEwma = 0.0,
        lastRecoveryReason = null,
        lastRecoveryAtNanos = null,
        lastErrorCode = null,
        reconfigurationCount = reconfigurationCount,
        activeTrackUnderrunCount = 0,
        trackGeneration = 1,
        lastReconfigurationReason = lastReconfigurationReason,
        lastReconfigurationAtNanos = lastReconfigurationAtNanos,
    )
}
