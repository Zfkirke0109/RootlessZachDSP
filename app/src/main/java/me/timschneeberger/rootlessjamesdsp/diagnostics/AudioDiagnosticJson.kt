package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry

internal data class DiagnosticBuildIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val commit: String,
)

internal enum class AudioDiagnosticEventType {
    TRANSPORT_SNAPSHOT,
    SIGNAL_SNAPSHOT,
    RECOVERY,
    RECONFIGURATION,
    UNDERRUN,
    DEADLINE_MISS,
    IO_ERROR,
    BYPASS_BUFFER,
}

/** Manual JSON encoding keeps the diagnostics foundation dependency-free and testable on the JVM. */
internal object AudioDiagnosticJson {
    const val SCHEMA_VERSION = 1
    const val ENGINE_SIGNAL_MEASUREMENT_BOUNDARY = "CAPTURED_INPUT_TO_DSP_ENGINE_OUTPUT"
    const val TRACK_INPUT_SIGNAL_MEASUREMENT_BOUNDARY = "CAPTURED_INPUT_TO_AUDIO_TRACK_INPUT"

    /** Compatibility alias for older report callers. */
    const val SIGNAL_MEASUREMENT_BOUNDARY = ENGINE_SIGNAL_MEASUREMENT_BOUNDARY

    fun transportSnapshot(
        snapshot: AudioTransportTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
        droppedEventCount: Long,
    ): String = buildString(760) {
        beginEnvelope(
            type = AudioDiagnosticEventType.TRANSPORT_SNAPSHOT,
            capturedAtNanos = snapshot.capturedAtNanos,
            build = build,
            engineEpoch = engineEpoch,
            wallClockEpochMs = wallClockEpochMs,
        )
        number("sampleRate", snapshot.sampleRate)
        number("channels", snapshot.channelCount)
        number("bufferSamples", snapshot.bufferSamples)
        number("readSamples", snapshot.totalReadSamples)
        number("writtenSamples", snapshot.totalWrittenSamples)
        number("partialReadOperations", snapshot.partialReadOperations)
        number("partialWriteOperations", snapshot.partialWriteOperations)
        number("zeroProgressOperations", snapshot.zeroProgressOperations)
        number("ioErrors", snapshot.ioErrorCount)
        number("recoveries", snapshot.recoveryCount)
        number("reconfigurations", snapshot.reconfigurationCount)
        number("underruns", snapshot.underrunCount)
        number("epochUnderrunDelta", snapshot.underrunCount)
        number("activeTrackUnderruns", snapshot.activeTrackUnderrunCount)
        number("trackGeneration", snapshot.trackGeneration)
        number("deadlineMisses", snapshot.deadlineMissCount)
        number("bypassBuffers", snapshot.bypassBufferCount)
        number("lastProcessingNanos", snapshot.lastProcessingNanos)
        number("maxProcessingNanos", snapshot.maxProcessingNanos)
        decimal("processingLoadEwma", snapshot.processingLoadEwma)
        nullableNumber("lastRecoveryAgeMs", recoveryAgeMs(snapshot))
        nullableNumber("lastReconfigurationAgeMs", reconfigurationAgeMs(snapshot))
        nullableNumber("lastErrorCode", snapshot.lastErrorCode?.toLong())
        number("droppedEventCount", droppedEventCount)
        endObject()
    }

    fun signalSnapshot(
        snapshot: AudioSignalTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
        measurementBoundary: String = ENGINE_SIGNAL_MEASUREMENT_BOUNDARY,
    ): String = buildString(720) {
        beginEnvelope(
            type = AudioDiagnosticEventType.SIGNAL_SNAPSHOT,
            capturedAtNanos = snapshot.capturedAtNanos,
            build = build,
            engineEpoch = engineEpoch,
            wallClockEpochMs = wallClockEpochMs,
        )
        string("measurementBoundary", measurementBoundary)
        number("sampleCount", snapshot.sampleCount)
        decimal("inputRms", snapshot.inputRms)
        decimal("outputRms", snapshot.outputRms)
        decimal("inputPeak", snapshot.inputPeak)
        decimal("outputPeak", snapshot.outputPeak)
        decimal("inputDcOffset", snapshot.inputDcOffset)
        decimal("outputDcOffset", snapshot.outputDcOffset)
        decimal("inputSilenceRatio", snapshot.inputSilenceRatio)
        decimal("outputSilenceRatio", snapshot.outputSilenceRatio)
        number("inputClippedSamples", snapshot.inputClippedSamples)
        number("outputClippedSamples", snapshot.outputClippedSamples)
        number("changedSamples", snapshot.changedSamples)
        decimal("changedSampleRatio", snapshot.changedSampleRatio)
        string("inputHash", java.lang.Long.toUnsignedString(snapshot.inputHash, 16))
        string("outputHash", java.lang.Long.toUnsignedString(snapshot.outputHash, 16))
        number("hashStride", snapshot.hashStride)
        boolean("outputChanged", snapshot.outputChanged)
        endObject()
    }

    fun counterDelta(
        type: AudioDiagnosticEventType,
        delta: Long,
        snapshot: AudioTransportTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
    ): String = buildString(420) {
        require(
            type != AudioDiagnosticEventType.TRANSPORT_SNAPSHOT &&
                type != AudioDiagnosticEventType.SIGNAL_SNAPSHOT,
        )
        beginEnvelope(
            type = type,
            capturedAtNanos = snapshot.capturedAtNanos,
            build = build,
            engineEpoch = engineEpoch,
            wallClockEpochMs = wallClockEpochMs,
        )
        number("delta", delta)
        number("bufferSamples", snapshot.bufferSamples)
        decimal("processingLoadEwma", snapshot.processingLoadEwma)
        number("underruns", snapshot.underrunCount)
        number("epochUnderrunDelta", snapshot.underrunCount)
        number("activeTrackUnderruns", snapshot.activeTrackUnderrunCount)
        number("trackGeneration", snapshot.trackGeneration)
        number("deadlineMisses", snapshot.deadlineMissCount)
        number("ioErrors", snapshot.ioErrorCount)
        number("bypassBuffers", snapshot.bypassBufferCount)
        nullableNumber("lastErrorCode", snapshot.lastErrorCode?.toLong())
        when (type) {
            AudioDiagnosticEventType.RECOVERY -> {
                nullableString("reason", snapshot.lastRecoveryReason)
                nullableNumber("recoveryAgeMs", recoveryAgeMs(snapshot))
                boolean("expected", false)
            }
            AudioDiagnosticEventType.RECONFIGURATION -> {
                nullableString("reason", snapshot.lastReconfigurationReason)
                nullableNumber("reconfigurationAgeMs", reconfigurationAgeMs(snapshot))
                boolean("expected", true)
            }
            AudioDiagnosticEventType.UNDERRUN -> {
                val nearExpectedReconfiguration =
                    reconfigurationAgeMs(snapshot)?.let { it <= RECONFIGURATION_ASSOCIATION_WINDOW_MS } == true
                boolean("nearExpectedReconfiguration", nearExpectedReconfiguration)
            }
            else -> Unit
        }
        endObject()
    }

    internal fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
    }

    private fun recoveryAgeMs(snapshot: AudioTransportTelemetry.Snapshot): Long? =
        snapshot.lastRecoveryAtNanos?.let { eventAgeMs(snapshot.capturedAtNanos, it) }

    private fun reconfigurationAgeMs(snapshot: AudioTransportTelemetry.Snapshot): Long? =
        snapshot.lastReconfigurationAtNanos?.let { eventAgeMs(snapshot.capturedAtNanos, it) }

    private fun eventAgeMs(capturedAtNanos: Long, eventAtNanos: Long): Long =
        (capturedAtNanos - eventAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun StringBuilder.beginEnvelope(
        type: AudioDiagnosticEventType,
        capturedAtNanos: Long,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
    ) {
        append('{')
        number("schemaVersion", SCHEMA_VERSION, first = true)
        string("eventType", type.name)
        number("capturedAtNanos", capturedAtNanos)
        number("wallClockEpochMs", wallClockEpochMs)
        string("applicationId", build.applicationId)
        string("versionName", build.versionName)
        number("versionCode", build.versionCode)
        string("commit", build.commit)
        string("engineEpoch", engineEpoch)
    }

    private fun StringBuilder.number(name: String, value: Number, first: Boolean = false) {
        if (!first) append(',')
        append('"').append(escape(name)).append("\":").append(value)
    }

    private fun StringBuilder.decimal(name: String, value: Double) {
        append(',').append('"').append(escape(name)).append("\":")
        append(if (value.isFinite()) value else 0.0)
    }

    private fun StringBuilder.boolean(name: String, value: Boolean) {
        append(',').append('"').append(escape(name)).append("\":").append(value)
    }

    private fun StringBuilder.string(name: String, value: String) {
        append(',').append('"').append(escape(name)).append("\":\"")
        append(escape(value)).append('"')
    }

    private fun StringBuilder.nullableString(name: String, value: String?) {
        if (value == null) {
            append(',').append('"').append(escape(name)).append("\":null")
        } else string(name, value)
    }

    private fun StringBuilder.nullableNumber(name: String, value: Long?) {
        append(',').append('"').append(escape(name)).append("\":")
        if (value == null) append("null") else append(value)
    }

    private fun StringBuilder.endObject() {
        append('}')
    }

    private const val RECONFIGURATION_ASSOCIATION_WINDOW_MS = 2_000L
}
