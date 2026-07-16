package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry

internal data class DiagnosticBuildIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val commit: String,
)

internal enum class AudioDiagnosticEventType {
    TRANSPORT_SNAPSHOT,
    RECOVERY,
    UNDERRUN,
    DEADLINE_MISS,
    IO_ERROR,
    BYPASS_BUFFER,
}

/** Manual JSON encoding keeps the diagnostics foundation dependency-free and testable on the JVM. */
internal object AudioDiagnosticJson {
    const val SCHEMA_VERSION = 1

    fun transportSnapshot(
        snapshot: AudioTransportTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
        droppedEventCount: Long,
    ): String = buildString(640) {
        beginEnvelope(
            type = AudioDiagnosticEventType.TRANSPORT_SNAPSHOT,
            snapshot = snapshot,
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
        number("underruns", snapshot.underrunCount)
        number("deadlineMisses", snapshot.deadlineMissCount)
        number("bypassBuffers", snapshot.bypassBufferCount)
        number("lastProcessingNanos", snapshot.lastProcessingNanos)
        number("maxProcessingNanos", snapshot.maxProcessingNanos)
        decimal("processingLoadEwma", snapshot.processingLoadEwma)
        nullableNumber("lastRecoveryAgeMs", recoveryAgeMs(snapshot))
        nullableNumber("lastErrorCode", snapshot.lastErrorCode?.toLong())
        number("droppedEventCount", droppedEventCount)
        endObject()
    }

    fun counterDelta(
        type: AudioDiagnosticEventType,
        delta: Long,
        snapshot: AudioTransportTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
    ): String = buildString(320) {
        require(type != AudioDiagnosticEventType.TRANSPORT_SNAPSHOT)
        beginEnvelope(type, snapshot, build, engineEpoch, wallClockEpochMs)
        number("delta", delta)
        number("bufferSamples", snapshot.bufferSamples)
        decimal("processingLoadEwma", snapshot.processingLoadEwma)
        number("underruns", snapshot.underrunCount)
        number("deadlineMisses", snapshot.deadlineMissCount)
        number("ioErrors", snapshot.ioErrorCount)
        number("bypassBuffers", snapshot.bypassBufferCount)
        nullableNumber("lastErrorCode", snapshot.lastErrorCode?.toLong())
        if (type == AudioDiagnosticEventType.RECOVERY) {
            nullableString("reason", snapshot.lastRecoveryReason)
            nullableNumber("recoveryAgeMs", recoveryAgeMs(snapshot))
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
        snapshot.lastRecoveryAtNanos?.let { recoveryAt ->
            (snapshot.capturedAtNanos - recoveryAt).coerceAtLeast(0L) / 1_000_000L
        }

    private fun StringBuilder.beginEnvelope(
        type: AudioDiagnosticEventType,
        snapshot: AudioTransportTelemetry.Snapshot,
        build: DiagnosticBuildIdentity,
        engineEpoch: String,
        wallClockEpochMs: Long,
    ) {
        append('{')
        number("schemaVersion", SCHEMA_VERSION, first = true)
        string("eventType", type.name)
        number("capturedAtNanos", snapshot.capturedAtNanos)
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
}
