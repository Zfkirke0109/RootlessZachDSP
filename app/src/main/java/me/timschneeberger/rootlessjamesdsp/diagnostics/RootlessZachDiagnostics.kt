package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private val immediateFlushScheduled = AtomicBoolean(false)
    private val immediateFlushRequested = AtomicBoolean(false)
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
            requestImmediateFlush()
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

    private fun requestImmediateFlush() {
        immediateFlushRequested.set(true)
        if (!immediateFlushScheduled.compareAndSet(false, true)) return

        try {
            writer.execute {
                try {
                    while (immediateFlushRequested.getAndSet(false)) {
                        flushLatestSafely()
                    }
                } finally {
                    immediateFlushScheduled.set(false)
                    if (immediateFlushRequested.get()) requestImmediateFlush()
                }
            }
        } catch (error: RejectedExecutionException) {
            immediateFlushScheduled.set(false)
            droppedEventCount.incrementAndGet()
            Timber.w(error, "RootlessZach diagnostics writer rejected an immediate flush")
        }
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
