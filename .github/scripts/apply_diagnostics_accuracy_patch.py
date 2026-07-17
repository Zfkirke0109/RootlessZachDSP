#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:160]!r}")
    write(path, content.replace(old, new, 1))


write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/transport/AudioTransportTelemetry.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.audio.transport

import java.util.Locale

private const val EVENT_REASON_LOG_WINDOW_MS = 10_000L

/** Consistent snapshots of the capture -> DSP -> output transport. */
class AudioTransportTelemetry(private val clockNanos: () -> Long = System::nanoTime) {
    data class Snapshot(
        val capturedAtNanos: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val bufferSamples: Int,
        val totalReadSamples: Long,
        val totalWrittenSamples: Long,
        val partialReadOperations: Long,
        val partialWriteOperations: Long,
        val zeroProgressOperations: Long,
        val ioErrorCount: Long,
        val recoveryCount: Long,
        val underrunCount: Int,
        val deadlineMissCount: Long,
        val bypassBufferCount: Long,
        val lastProcessingNanos: Long,
        val maxProcessingNanos: Long,
        val processingLoadEwma: Double,
        val lastRecoveryReason: String?,
        val lastRecoveryAtNanos: Long?,
        val lastErrorCode: Int?,
        val reconfigurationCount: Long = 0L,
        val activeTrackUnderrunCount: Int = 0,
        val trackGeneration: Int = 0,
        val lastReconfigurationReason: String? = null,
        val lastReconfigurationAtNanos: Long? = null,
    ) {
        fun compactString() = buildString {
            append("sampleRate=").append(sampleRate)
            append(" channels=").append(channelCount)
            append(" bufferSamples=").append(bufferSamples)
            append(" read=").append(totalReadSamples)
            append(" written=").append(totalWrittenSamples)
            append(" partialRead=").append(partialReadOperations)
            append(" partialWrite=").append(partialWriteOperations)
            append(" zeroProgress=").append(zeroProgressOperations)
            append(" ioErrors=").append(ioErrorCount)
            append(" recoveries=").append(recoveryCount)
            append(" reconfigurations=").append(reconfigurationCount)
            // Keep the legacy field while adding explicit scope labels.
            append(" underruns=").append(underrunCount)
            append(" epochUnderrunDelta=").append(underrunCount)
            append(" activeTrackUnderruns=").append(activeTrackUnderrunCount)
            append(" trackGeneration=").append(trackGeneration)
            append(" deadlineMisses=").append(deadlineMissCount)
            append(" bypassBuffers=").append(bypassBufferCount)
            append(" processNs=").append(lastProcessingNanos)
            append(" maxProcessNs=").append(maxProcessingNanos)
            append(" loadEwma=").append(String.format(Locale.US, "%.3f", processingLoadEwma))
            lastRecoveryAtNanos?.let { recoveryAt ->
                val recoveryAgeMs = ageMs(recoveryAt)
                append(" recoveryAgeMs=").append(recoveryAgeMs)
                if (recoveryAgeMs <= EVENT_REASON_LOG_WINDOW_MS) {
                    lastRecoveryReason?.let { append(" recoveryReason=").append(it) }
                }
            }
            lastReconfigurationAtNanos?.let { reconfigurationAt ->
                val ageMs = ageMs(reconfigurationAt)
                append(" reconfigurationAgeMs=").append(ageMs)
                if (ageMs <= EVENT_REASON_LOG_WINDOW_MS) {
                    lastReconfigurationReason?.let { append(" reconfigurationReason=").append(it) }
                }
            }
            lastErrorCode?.let { append(" lastError=").append(it) }
        }

        private fun ageMs(eventAtNanos: Long): Long =
            (capturedAtNanos - eventAtNanos).coerceAtLeast(0L) / 1_000_000L
    }

    private var sampleRate = 0
    private var channelCount = 0
    private var bufferSamples = 0
    private var totalReadSamples = 0L
    private var totalWrittenSamples = 0L
    private var partialReadOperations = 0L
    private var partialWriteOperations = 0L
    private var zeroProgressOperations = 0L
    private var ioErrorCount = 0L
    private var recoveryCount = 0L
    private var reconfigurationCount = 0L
    private var underrunCount = 0
    private var activeTrackUnderrunCount = 0
    private var trackGeneration = 0
    private var deadlineMissCount = 0L
    private var bypassBufferCount = 0L
    private var lastProcessingNanos = 0L
    private var maxProcessingNanos = 0L
    private var processingLoadEwma = 0.0
    private var lastRecoveryReason: String? = null
    private var lastRecoveryAtNanos: Long? = null
    private var lastReconfigurationReason: String? = null
    private var lastReconfigurationAtNanos: Long? = null
    private var lastErrorCode: Int? = null

    @Synchronized
    fun configure(rate: Int, channels: Int, samples: Int) {
        sampleRate = rate
        channelCount = channels
        bufferSamples = samples
        trackGeneration++
        activeTrackUnderrunCount = 0
    }

    @Synchronized
    fun recordRead(result: AudioTransferResult) {
        totalReadSamples += result.transferredSamples
        partialReadOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let {
            ioErrorCount++
            lastErrorCode = it
        }
    }

    @Synchronized
    fun recordWrite(result: AudioTransferResult) {
        totalWrittenSamples += result.transferredSamples
        partialWriteOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let {
            ioErrorCount++
            lastErrorCode = it
        }
    }

    @Synchronized
    fun recordProcessing(durationNanos: Long, deadlineNanos: Long, bypassed: Boolean) {
        lastProcessingNanos = durationNanos.coerceAtLeast(0)
        maxProcessingNanos = maxOf(maxProcessingNanos, lastProcessingNanos)
        if (deadlineNanos > 0 && durationNanos > deadlineNanos) deadlineMissCount++
        if (bypassed) bypassBufferCount++
        val ratio = if (deadlineNanos > 0) durationNanos.toDouble() / deadlineNanos else 0.0
        processingLoadEwma = if (processingLoadEwma == 0.0) ratio else processingLoadEwma * 0.9 + ratio * 0.1
    }

    /** Records the monotonic underrun counter belonging to the currently active AudioTrack. */
    @Synchronized
    fun recordActiveTrackUnderrunCount(currentCount: Int) {
        val safeCurrent = currentCount.coerceAtLeast(0)
        underrunCount += (safeCurrent - activeTrackUnderrunCount).coerceAtLeast(0)
        activeTrackUnderrunCount = safeCurrent
    }

    /** Retained for deterministic tests and non-AudioTrack transports. */
    @Synchronized
    fun recordUnderrunDelta(delta: Int) {
        val safeDelta = delta.coerceAtLeast(0)
        underrunCount += safeDelta
        activeTrackUnderrunCount += safeDelta
    }

    @Synchronized
    fun recordRecovery(reason: String) {
        recoveryCount++
        lastRecoveryReason = reason
        lastRecoveryAtNanos = clockNanos()
    }

    @Synchronized
    fun recordReconfiguration(reason: String) {
        reconfigurationCount++
        lastReconfigurationReason = reason
        lastReconfigurationAtNanos = clockNanos()
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = clockNanos()
        return Snapshot(
            capturedAtNanos = now,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bufferSamples = bufferSamples,
            totalReadSamples = totalReadSamples,
            totalWrittenSamples = totalWrittenSamples,
            partialReadOperations = partialReadOperations,
            partialWriteOperations = partialWriteOperations,
            zeroProgressOperations = zeroProgressOperations,
            ioErrorCount = ioErrorCount,
            recoveryCount = recoveryCount,
            underrunCount = underrunCount,
            deadlineMissCount = deadlineMissCount,
            bypassBufferCount = bypassBufferCount,
            lastProcessingNanos = lastProcessingNanos,
            maxProcessingNanos = maxProcessingNanos,
            processingLoadEwma = processingLoadEwma,
            lastRecoveryReason = lastRecoveryReason,
            lastRecoveryAtNanos = lastRecoveryAtNanos,
            lastErrorCode = lastErrorCode,
            reconfigurationCount = reconfigurationCount,
            activeTrackUnderrunCount = activeTrackUnderrunCount,
            trackGeneration = trackGeneration,
            lastReconfigurationReason = lastReconfigurationReason,
            lastReconfigurationAtNanos = lastReconfigurationAtNanos,
        )
    }
}
''',
)

write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/AudioDiagnosticJson.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.diagnostics

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
''',
)

write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/RootlessZachDiagnostics.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory bridge plus bounded app-private persistence for rootless diagnostics.
 *
 * Publish calls may originate from the urgent audio thread. They perform atomic updates and simple
 * counter comparisons only. JSON serialization and file I/O run on a dedicated writer thread.
 */
object RootlessZachDiagnostics {
    private val transportSnapshot = AtomicReference<AudioTransportTelemetry.Snapshot?>(null)
    private val engineSignalSnapshot = AtomicReference<AudioSignalTelemetry.Snapshot?>(null)
    private val trackInputSignalSnapshot = AtomicReference<AudioSignalTelemetry.Snapshot?>(null)
    private val writerStarted = AtomicBoolean(false)
    private val store = AtomicReference<RotatingJsonlStore?>(null)
    private val droppedEventCount = AtomicLong(0L)

    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RootlessZachDiagnostics").apply { isDaemon = true }
    }

    @Volatile
    private var engineEpoch = newEpoch()

    // Accessed only by the diagnostics writer thread.
    private var lastPersistedTransportSnapshot: AudioTransportTelemetry.Snapshot? = null
    private var lastPersistedEngineSignalSnapshot: AudioSignalTelemetry.Snapshot? = null
    private var lastPersistedTrackInputSignalSnapshot: AudioSignalTelemetry.Snapshot? = null

    private val buildIdentity = DiagnosticBuildIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        commit = BuildConfig.COMMIT_SHA,
    )

    fun publish(value: AudioTransportTelemetry.Snapshot) {
        val previous = transportSnapshot.getAndSet(value)
        ensureWriterStarted()
        if (previous != null && hasImmediateEventIncrease(previous, value)) {
            writer.execute { flushLatestSafely() }
        }
    }

    /** Captured input compared with the direct DSP-engine output. */
    fun publishSignal(value: AudioSignalTelemetry.Snapshot) {
        engineSignalSnapshot.set(value)
        ensureWriterStarted()
    }

    /** Captured input compared with the final post-crossfade buffer submitted to AudioTrack. */
    fun publishTrackInputSignal(value: AudioSignalTelemetry.Snapshot) {
        trackInputSignalSnapshot.set(value)
        ensureWriterStarted()
    }

    fun latestTransportSnapshot(): AudioTransportTelemetry.Snapshot? = transportSnapshot.get()

    fun latestSignalSnapshot(): AudioSignalTelemetry.Snapshot? = engineSignalSnapshot.get()

    fun latestTrackInputSignalSnapshot(): AudioSignalTelemetry.Snapshot? = trackInputSignalSnapshot.get()

    /** Starts a new non-identifying correlation epoch for a service/engine lifetime. */
    fun beginEngineEpoch() {
        engineEpoch = newEpoch()
        engineSignalSnapshot.set(null)
        trackInputSignalSnapshot.set(null)
        writer.execute {
            lastPersistedTransportSnapshot = null
            lastPersistedEngineSignalSnapshot = null
            lastPersistedTrackInputSignalSnapshot = null
        }
        ensureWriterStarted()
    }

    /** Clears only the in-memory latest snapshots, preserving persisted history. */
    fun clear() {
        transportSnapshot.set(null)
        engineSignalSnapshot.set(null)
        trackInputSignalSnapshot.set(null)
    }

    fun clearHistory() {
        clear()
        writer.execute {
            runCatching { diagnosticsStore()?.clear() }
                .onFailure { Timber.w(it, "Unable to clear RootlessZach diagnostics history") }
            lastPersistedTransportSnapshot = null
            lastPersistedEngineSignalSnapshot = null
            lastPersistedTrackInputSignalSnapshot = null
            droppedEventCount.set(0L)
        }
    }

    fun readRecentLines(maximumLines: Int = 200): List<String> =
        runCatching { diagnosticsStore()?.readRecentLines(maximumLines).orEmpty() }
            .onFailure { Timber.w(it, "Unable to read RootlessZach diagnostics history") }
            .getOrDefault(emptyList())

    fun latestDiagnosticsFile(): File? = diagnosticsStore()?.activeFile()

    private fun ensureWriterStarted() {
        if (!writerStarted.compareAndSet(false, true)) return
        writer.scheduleWithFixedDelay(
            { flushLatestSafely() },
            0L,
            PERSIST_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun flushLatestSafely() {
        val current = transportSnapshot.get() ?: return
        val currentEngineSignal = engineSignalSnapshot.get()
        val currentTrackInputSignal = trackInputSignalSnapshot.get()
        val previous = lastPersistedTransportSnapshot
        val previousEngineSignal = lastPersistedEngineSignalSnapshot
        val previousTrackInputSignal = lastPersistedTrackInputSignalSnapshot

        // Keep final signal-only updates once, but suppress exact stale repeats.
        val transportUnchanged = previous?.capturedAtNanos == current.capturedAtNanos
        val engineSignalUnchanged =
            previousEngineSignal?.capturedAtNanos == currentEngineSignal?.capturedAtNanos
        val trackInputSignalUnchanged =
            previousTrackInputSignal?.capturedAtNanos == currentTrackInputSignal?.capturedAtNanos
        if (transportUnchanged && engineSignalUnchanged && trackInputSignalUnchanged) return

        val droppedBeforeWrite = droppedEventCount.get()
        val nowMs = System.currentTimeMillis()
        val epoch = engineEpoch
        val lines = ArrayList<String>(10)

        if (previous != null) {
            addDelta(
                lines,
                AudioDiagnosticEventType.RECONFIGURATION,
                current.reconfigurationCount - previous.reconfigurationCount,
                current,
                epoch,
                nowMs,
            )
            addDelta(
                lines,
                AudioDiagnosticEventType.RECOVERY,
                current.recoveryCount - previous.recoveryCount,
                current,
                epoch,
                nowMs,
            )
            addDelta(
                lines,
                AudioDiagnosticEventType.UNDERRUN,
                current.underrunCount.toLong() - previous.underrunCount.toLong(),
                current,
                epoch,
                nowMs,
            )
            addDelta(
                lines,
                AudioDiagnosticEventType.DEADLINE_MISS,
                current.deadlineMissCount - previous.deadlineMissCount,
                current,
                epoch,
                nowMs,
            )
            addDelta(
                lines,
                AudioDiagnosticEventType.IO_ERROR,
                current.ioErrorCount - previous.ioErrorCount,
                current,
                epoch,
                nowMs,
            )
            addDelta(
                lines,
                AudioDiagnosticEventType.BYPASS_BUFFER,
                current.bypassBufferCount - previous.bypassBufferCount,
                current,
                epoch,
                nowMs,
            )
        }

        lines += AudioDiagnosticJson.transportSnapshot(
            snapshot = current,
            build = buildIdentity,
            engineEpoch = epoch,
            wallClockEpochMs = nowMs,
            droppedEventCount = droppedBeforeWrite,
        )
        currentEngineSignal?.let { signal ->
            lines += AudioDiagnosticJson.signalSnapshot(
                snapshot = signal,
                build = buildIdentity,
                engineEpoch = epoch,
                wallClockEpochMs = nowMs,
                measurementBoundary = AudioDiagnosticJson.ENGINE_SIGNAL_MEASUREMENT_BOUNDARY,
            )
        }
        currentTrackInputSignal?.let { signal ->
            lines += AudioDiagnosticJson.signalSnapshot(
                snapshot = signal,
                build = buildIdentity,
                engineEpoch = epoch,
                wallClockEpochMs = nowMs,
                measurementBoundary = AudioDiagnosticJson.TRACK_INPUT_SIGNAL_MEASUREMENT_BOUNDARY,
            )
        }

        try {
            diagnosticsStore()?.appendLines(lines) ?: return
            lastPersistedTransportSnapshot = current
            lastPersistedEngineSignalSnapshot = currentEngineSignal
            lastPersistedTrackInputSignalSnapshot = currentTrackInputSignal
            if (droppedBeforeWrite > 0L) {
                droppedEventCount.addAndGet(-droppedBeforeWrite)
            }
        } catch (error: Exception) {
            droppedEventCount.addAndGet(lines.size.toLong())
            Timber.w(error, "Unable to persist RootlessZach diagnostics")
        }
    }

    private fun addDelta(
        target: MutableList<String>,
        type: AudioDiagnosticEventType,
        delta: Long,
        current: AudioTransportTelemetry.Snapshot,
        epoch: String,
        nowMs: Long,
    ) {
        if (delta <= 0L) return
        target += AudioDiagnosticJson.counterDelta(
            type = type,
            delta = delta,
            snapshot = current,
            build = buildIdentity,
            engineEpoch = epoch,
            wallClockEpochMs = nowMs,
        )
    }

    private fun hasImmediateEventIncrease(
        previous: AudioTransportTelemetry.Snapshot,
        current: AudioTransportTelemetry.Snapshot,
    ): Boolean =
        current.reconfigurationCount > previous.reconfigurationCount ||
            current.recoveryCount > previous.recoveryCount ||
            current.underrunCount > previous.underrunCount ||
            current.deadlineMissCount > previous.deadlineMissCount ||
            current.ioErrorCount > previous.ioErrorCount ||
            current.bypassBufferCount > previous.bypassBufferCount

    private fun diagnosticsStore(): RotatingJsonlStore? {
        store.get()?.let { return it }
        val application = runCatching { MainApplication.instance }.getOrNull() ?: return null
        val created = RotatingJsonlStore(File(application.filesDir, DIAGNOSTICS_DIRECTORY))
        return if (store.compareAndSet(null, created)) created else store.get()
    }

    private fun newEpoch(): String = java.lang.Long.toUnsignedString(System.nanoTime(), 16)

    private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
    private const val PERSIST_INTERVAL_SECONDS = 5L
}
''',
)

write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/CompatibilityDiagnosticsReport.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import java.time.Instant
import java.util.Locale

/** Builds a local, copy-friendly, redacted compatibility report. */
object CompatibilityDiagnosticsReport {
    fun build(
        context: Context,
        includeSelectedPackageNames: Boolean = false,
        includeOutputDeviceNames: Boolean = false,
    ): String {
        val app = context.applicationContext
        val audioManager = app.getSystemService(AudioManager::class.java)
        val store = CapturePolicyStore(app)
        val policy = store.read()
        val transport = RootlessZachDiagnostics.latestTransportSnapshot()
        val engineSignal = RootlessZachDiagnostics.latestSignalSnapshot()
        val trackInputSignal = RootlessZachDiagnostics.latestTrackInputSignalSnapshot()
        val diagnosticsFile = RootlessZachDiagnostics.latestDiagnosticsFile()
        val recentStructuredEvents = RootlessZachDiagnostics.readRecentLines(200)
        val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val outputDevices = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.sortedBy { it.type }
            .orEmpty()
        val usbMixerCapabilities = queryUsbMixerCapabilities(audioManager, outputDevices)

        return buildString {
            appendLine("RootlessZachDSP compatibility report")
            appendLine("generatedUtc=${Instant.now()}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${packageInfo?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()}")
            appendLine("commit=${BuildConfig.COMMIT_SHA}")
            appendLine()
            appendLine("[Android]")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("securityPatch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("buildDisplay=${Build.DISPLAY}")
            appendLine("kernel=${System.getProperty("os.version").orEmpty()}")
            appendLine("webView=${webView?.packageName.orEmpty()} ${webView?.versionName.orEmpty()}")
            appendLine()
            appendLine("[Permissions]")
            appendLine("recordAudio=${hasPermission(app, Manifest.permission.RECORD_AUDIO)}")
            appendLine("postNotifications=${hasPermission(app, Manifest.permission.POST_NOTIFICATIONS)}")
            appendLine()
            appendLine("[Capture policy]")
            appendLine("mode=${policy.mode}")
            appendLine("selectedPackageCount=${policy.packageNames.size}")
            appendLine("selectedRawUidCount=${policy.rawUids.size}")
            if (includeSelectedPackageNames) {
                appendLine("selectedPackages=${policy.packageNames.sorted().joinToString(",")}")
                appendLine("selectedRawUids=${policy.rawUids.sorted().joinToString(",")}")
            } else {
                appendLine("selectedPackages=<redacted>")
                appendLine("selectedRawUids=<redacted>")
            }
            appendLine()
            appendLine("[Audio platform]")
            appendLine("outputSampleRate=${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).orEmpty()}")
            appendLine("framesPerBuffer=${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER).orEmpty()}")
            outputDevices.forEachIndexed { index, device ->
                val name = if (includeOutputDeviceNames) device.productName else "<redacted>"
                appendLine(
                    "outputDevice[$index]=type:${device.type},name:$name," +
                        "sampleRates:${device.sampleRates.joinToString("/")}," +
                        "channels:${device.channelCounts.joinToString("/")}",
                )
            }
            appendLine()
            appendLine("[Playback fidelity]")
            appendLine("pipeline=Android playback-capture PCM -> RootlessZachDSP -> AudioTrack")
            appendLine("outputUsage=USAGE_MEDIA")
            appendLine("outputContentType=CONTENT_TYPE_MUSIC")
            appendLine("maximumRootlessSampleRate=48000")
            appendLine("bitPerfect=false")
            appendLine("bitPerfectReason=Playback capture, DSP, crossfades, gain ramps, and AudioTrack prevent bit identity")
            appendLine("highResolutionDriverIncluded=false")
            appendLine("highResolutionDriverReason=An unrooted app cannot replace Samsung kernel or audio-HAL drivers")
            appendLine("usbOutputPresent=${usbMixerCapabilities.usbOutputPresent}")
            appendLine("usbConfigurableMixerCount=${usbMixerCapabilities.configurableMixerCount}")
            appendLine("usbBitPerfectMixerSupported=${usbMixerCapabilities.bitPerfectSupported}")
            appendLine("usbMixerFormats=${usbMixerCapabilities.formats.ifEmpty { "none-reported" }}")
            appendLine("mqaDecoderIncluded=false")
            appendLine("mqaPassthrough=false")
            appendLine("mqaNote=Any licensed source-side decode must happen before PCM capture")
            appendLine("dolbyAtmosCompatibility=media attributes preserved; final system effect policy remains controlled by Samsung")
            appendLine()
            appendLine("[Effect coexistence]")
            appendLine("activeSystemEffectChainObservable=false")
            appendLine("dolbyAtmosStateObservable=false")
            appendLine("warning=Samsung Dolby Atmos, system EQ, volume leveling, or another audio effect may process audio before capture or after AudioTrack input")
            appendLine("recommendedValidation=Compare Atmos off/on/auto per speaker, Bluetooth, and USB route while watching clipping and underruns")
            appendLine()
            appendLine("[Rootless transport]")
            appendLine(transport?.compactString() ?: "state=no-telemetry-yet")
            appendLine()
            appendSignalSection(
                title = "Captured input vs DSP engine output",
                boundary = AudioDiagnosticJson.ENGINE_SIGNAL_MEASUREMENT_BOUNDARY,
                signal = engineSignal,
                outputPrefix = "dspEngineOutput",
            )
            appendLine()
            appendSignalSection(
                title = "Captured input vs AudioTrack input",
                boundary = AudioDiagnosticJson.TRACK_INPUT_SIGNAL_MEASUREMENT_BOUNDARY,
                signal = trackInputSignal,
                outputPrefix = "audioTrackInput",
            )
            appendLine("finalAudioTrackMixMeasured=${trackInputSignal != null}")
            appendLine("finalSystemMixMeasured=false")
            appendLine()
            appendLine("[Structured diagnostics]")
            appendLine("schemaVersion=${AudioDiagnosticJson.SCHEMA_VERSION}")
            appendLine("activeFilePresent=${diagnosticsFile?.exists() == true}")
            appendLine("activeFileBytes=${diagnosticsFile?.takeIf { it.exists() }?.length() ?: 0L}")
            appendLine("recentEventCount=${recentStructuredEvents.size}")
            appendLine("storage=app-private-rotating-jsonl")
            appendLine()
            appendLine("[Privacy]")
            appendLine("This report is generated locally and is not uploaded automatically.")
            appendLine("Output-device names and selected package identities are redacted by default.")
            appendLine("Structured diagnostics contain technical counters only; raw PCM is never stored.")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
        }
    }

    private fun StringBuilder.appendSignalSection(
        title: String,
        boundary: String,
        signal: me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry.Snapshot?,
        outputPrefix: String,
    ) {
        appendLine("[$title]")
        appendLine("measurementBoundary=$boundary")
        if (signal == null) {
            appendLine("state=no-signal-telemetry-yet")
            return
        }
        appendLine("sampleCount=${signal.sampleCount}")
        appendLine("capturedInputRms=${signal.inputRms}")
        appendLine("${outputPrefix}Rms=${signal.outputRms}")
        appendLine("capturedInputPeak=${signal.inputPeak}")
        appendLine("${outputPrefix}Peak=${signal.outputPeak}")
        appendLine("capturedInputClippedSamples=${signal.inputClippedSamples}")
        appendLine("${outputPrefix}ClippedSamples=${signal.outputClippedSamples}")
        appendLine("changedSampleRatio=${signal.changedSampleRatio}")
        appendLine("outputChanged=${signal.outputChanged}")
    }

    private fun queryUsbMixerCapabilities(
        audioManager: AudioManager?,
        outputDevices: List<AudioDeviceInfo>,
    ): UsbMixerCapabilities {
        val usbDevices = outputDevices.filter { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return UsbMixerCapabilities(usbDevices.isNotEmpty(), 0, false, "")
        }
        val attributes = usbDevices.flatMap { device ->
            runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
        }
        val formats = attributes
            .map { mixer ->
                val format = mixer.format
                "rate:${format.sampleRate},encoding:${format.encoding},channels:${format.channelCount},behavior:${mixer.mixerBehavior}"
            }
            .distinct()
            .sorted()
            .joinToString(";")
        return UsbMixerCapabilities(
            usbOutputPresent = usbDevices.isNotEmpty(),
            configurableMixerCount = attributes.size,
            bitPerfectSupported = attributes.any {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            },
            formats = formats,
        )
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class UsbMixerCapabilities(
        val usbOutputPresent: Boolean,
        val configurableMixerCount: Int,
        val bitPerfectSupported: Boolean,
        val formats: String,
    )
}
''',
)

SERVICE = "app/src/main/java/me/timschneeberger/rootlessjamesdsp/service/RootlessAudioProcessorService.kt"

replace_once(
    SERVICE,
    "import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransfers\nimport me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry",
    "import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransfers\nimport me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry\nimport me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry",
)

replace_once(
    SERVICE,
    '''    @Volatile
    private var recreateReason = "configuration changed"

    @Volatile
    private var recorderThread: Thread? = null
''',
    '''    @Volatile
    private var recreateReason = "configuration changed"

    @Volatile
    private var recreateExpected = true

    @Volatile
    private var recorderThread: Thread? = null
''',
)

replace_once(
    SERVICE,
    '''    private val transportTelemetry = AudioTransportTelemetry()
    private val capturePolicyStore by lazy { CapturePolicyStore(this) }
''',
    '''    private val transportTelemetry = AudioTransportTelemetry()
    private val audioTrackInputTelemetry = AudioSignalTelemetry(
        hashSeed = System.nanoTime() xor System.identityHashCode(this).toLong(),
        hashStride = TRACK_INPUT_SIGNAL_HASH_STRIDE,
    )
    private var lastTrackInputSignalPublishNanos = 0L
    private val capturePolicyStore by lazy { CapturePolicyStore(this) }
''',
)

replace_once(
    SERVICE,
    '''    fun requestAudioRecordRecreation(reason: String = "configuration changed") {
        if (isProcessorDisposing || isServiceDisposing) return
        recreateReason = reason
        recreateRecorderRequested = true
    }
''',
    '''    fun requestAudioRecordRecreation(
        reason: String = "configuration changed",
        expected: Boolean = true,
    ) {
        if (isProcessorDisposing || isServiceDisposing) return
        recreateReason = reason
        recreateExpected = expected
        recreateRecorderRequested = true
    }
''',
)

replace_once(
    SERVICE,
    '''        var pendingBufferSamples = pipeline.bufferSamples
        var pendingRebuildReason = ""
        var lastTrackUnderrunCount = 0
        var previousUnderrunCount = 0
''',
    '''        var pendingBufferSamples = pipeline.bufferSamples
        var pendingRebuildReason = ""
        var pendingRebuildExpected = false
        var previousUnderrunCount = 0
''',
)

replace_once(
    SERVICE,
    '''        fun scheduleRebuild(reason: String, bufferSamples: Int = pipeline.bufferSamples) {
            pendingRebuildReason = reason
            pendingBufferSamples = bufferSamples.coerceIn(MIN_BUFFER_SAMPLES, MAX_BUFFER_SAMPLES)
            rebuildAfterCurrentBuffer = true
            recoveryGain.rampTo(0f, crossfadeSamples.coerceAtMost(pipeline.bufferSamples))
        }

        fun rebuildNow(reason: String, requestedBufferSamples: Int): Boolean {
''',
    '''        fun scheduleRebuild(
            reason: String,
            bufferSamples: Int = pipeline.bufferSamples,
            expected: Boolean = false,
        ) {
            pendingRebuildReason = reason
            pendingBufferSamples = bufferSamples.coerceIn(MIN_BUFFER_SAMPLES, MAX_BUFFER_SAMPLES)
            pendingRebuildExpected = expected
            rebuildAfterCurrentBuffer = true
            recoveryGain.rampTo(0f, crossfadeSamples.coerceAtMost(pipeline.bufferSamples))
        }

        fun rebuildNow(
            reason: String,
            requestedBufferSamples: Int,
            expected: Boolean = false,
        ): Boolean {
''',
)

replace_once(
    SERVICE,
    '''                    transportTelemetry.configure(sampleRate, CHANNEL_COUNT, pipeline.bufferSamples)
                    transportTelemetry.recordRecovery(reason)
                    lastTrackUnderrunCount = 0
                    activeAudioRecord = pipeline.recorder
''',
    '''                    transportTelemetry.configure(sampleRate, CHANNEL_COUNT, pipeline.bufferSamples)
                    if (expected) transportTelemetry.recordReconfiguration(reason)
                    else transportTelemetry.recordRecovery(reason)
                    activeAudioRecord = pipeline.recorder
''',
)

replace_once(
    SERVICE,
    '''                if (recreateRecorderRequested && !rebuildAfterCurrentBuffer) {
                    recreateRecorderRequested = false
                    scheduleRebuild(recreateReason)
                }
''',
    '''                if (recreateRecorderRequested && !rebuildAfterCurrentBuffer) {
                    recreateRecorderRequested = false
                    val expected = recreateExpected
                    recreateExpected = true
                    scheduleRebuild(recreateReason, expected = expected)
                }
''',
)

replace_once(
    SERVICE,
    '''                        if (!rebuildNow(pendingRebuildReason, pendingBufferSamples)) {
                            stopSelf()
                            return
                        }
''',
    '''                        if (!rebuildNow(
                                pendingRebuildReason,
                                pendingBufferSamples,
                                pendingRebuildExpected,
                            )) {
                            stopSelf()
                            return
                        }
''',
)

replace_once(
    SERVICE,
    '''                applyRecoveryGain(encoding, buffers, recoveryGain)
                transportTelemetry.recordProcessing(
''',
    '''                applyRecoveryGain(encoding, buffers, recoveryGain)
                recordAudioTrackInputSignal(encoding, buffers)
                transportTelemetry.recordProcessing(
''',
)

replace_once(
    SERVICE,
    '''                val currentUnderruns = pipeline.track.underrunCount
                transportTelemetry.recordUnderrunDelta(
                    (currentUnderruns - lastTrackUnderrunCount).coerceAtLeast(0),
                )
                lastTrackUnderrunCount = currentUnderruns
''',
    '''                transportTelemetry.recordActiveTrackUnderrunCount(pipeline.track.underrunCount)
''',
)

# The same three-line rebuild block appears a second time after writes.
replace_once(
    SERVICE,
    '''                    if (!rebuildNow(pendingRebuildReason, pendingBufferSamples)) {
                        stopSelf()
                        return
                    }
''',
    '''                    if (!rebuildNow(
                        pendingRebuildReason,
                        pendingBufferSamples,
                        pendingRebuildExpected,
                    )) {
                        stopSelf()
                        return
                    }
''',
)

replace_once(
    SERVICE,
    '''        } finally {
            releaseAudioPipeline(pipeline)
            activeAudioRecord = null
            activeAudioTrack = null
        }
''',
    '''        } finally {
            publishFinalAudioTrackInputSignal()
            releaseAudioPipeline(pipeline)
            activeAudioRecord = null
            activeAudioTrack = null
        }
''',
)

replace_once(
    SERVICE,
    '''    private fun applyRecoveryGain(encoding: AudioEncoding, buffers: AudioBuffers, gain: LinearRamp) {
        if (encoding == AudioEncoding.PcmShort) gain.applyInPlace(buffers.shortMixed)
        else gain.applyInPlace(buffers.floatMixed)
    }

    private fun zeroUnreadTail(
''',
    '''    private fun applyRecoveryGain(encoding: AudioEncoding, buffers: AudioBuffers, gain: LinearRamp) {
        if (encoding == AudioEncoding.PcmShort) gain.applyInPlace(buffers.shortMixed)
        else gain.applyInPlace(buffers.floatMixed)
    }

    private fun recordAudioTrackInputSignal(encoding: AudioEncoding, buffers: AudioBuffers) {
        if (encoding == AudioEncoding.PcmShort) {
            audioTrackInputTelemetry.recordShort(
                buffers.shortDry,
                buffers.shortMixed,
                buffers.sampleCount,
            )
        } else {
            audioTrackInputTelemetry.recordFloat(
                buffers.floatDry,
                buffers.floatMixed,
                buffers.sampleCount,
            )
        }
        val now = System.nanoTime()
        if (now - lastTrackInputSignalPublishNanos >= TRACK_INPUT_SIGNAL_PUBLISH_INTERVAL_NANOS) {
            RootlessZachDiagnostics.publishTrackInputSignal(audioTrackInputTelemetry.snapshot())
            lastTrackInputSignalPublishNanos = now
        }
    }

    private fun publishFinalAudioTrackInputSignal() {
        audioTrackInputTelemetry.snapshot().takeIf { it.sampleCount > 0L }?.let {
            RootlessZachDiagnostics.publishTrackInputSignal(it)
        }
    }

    private fun zeroUnreadTail(
''',
)

replace_once(
    SERVICE,
    '''        private const val TELEMETRY_INTERVAL_NANOS = 1_000_000_000L
        private const val IDLE_POLL_MS = 50L
''',
    '''        private const val TELEMETRY_INTERVAL_NANOS = 1_000_000_000L
        private const val TRACK_INPUT_SIGNAL_PUBLISH_INTERVAL_NANOS = 1_000_000_000L
        private const val TRACK_INPUT_SIGNAL_HASH_STRIDE = 16
        private const val IDLE_POLL_MS = 50L
''',
)

write(
    "app/src/test/java/me/timschneeberger/rootlessjamesdsp/audio/transport/AudioTransportTelemetryScopeTest.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.audio.transport

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
''',
)

write(
    "app/src/test/java/me/timschneeberger/rootlessjamesdsp/diagnostics/AudioDiagnosticAccuracyTest.kt",
    r'''package me.timschneeberger.rootlessjamesdsp.diagnostics

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
''',
)

HANDOFF = "docs/AGENT_HANDOFF.md"
handoff = read(HANDOFF)
note = r'''
## Diagnostics accuracy follow-up

- Expected policy/settings-driven pipeline rebuilds are classified as RECONFIGURATION rather than
  RECOVERY.
- Transport snapshots retain the legacy underrun field while adding service-epoch underrun delta,
  active-AudioTrack underruns, and track generation.
- A second allocation-free signal accumulator measures captured input against the final
  post-crossfade/post-recovery-gain buffer submitted to AudioTrack. It still does not claim to
  observe Samsung's downstream system mix.
- Compatibility reports probe public Android 14+ USB mixer capabilities, including whether a
  connected USB route advertises BIT_PERFECT behavior. Rootless DSP itself remains explicitly
  non-bit-perfect.
- Active Samsung/Dolby/third-party effect state cannot be reliably enumerated through public APIs,
  so reports warn about the unobservable pre/post system effect chain instead of guessing.
'''
if note not in handoff:
    write(HANDOFF, handoff.rstrip() + "\n" + note + "\n")

print("Diagnostics accuracy patch applied")
