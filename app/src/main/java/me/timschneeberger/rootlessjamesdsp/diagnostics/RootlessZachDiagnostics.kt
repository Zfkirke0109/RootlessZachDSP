package me.timschneeberger.rootlessjamesdsp.diagnostics

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
 * [publish] and [publishSignal] may be called from the urgent audio thread. They perform atomic
 * updates and simple counter comparisons only. JSON serialization and file I/O are performed by a
 * dedicated writer thread. Periodic snapshots are written approximately every five seconds;
 * anomaly counter increases also request an immediate off-thread flush.
 */
object RootlessZachDiagnostics {
    private val transportSnapshot = AtomicReference<AudioTransportTelemetry.Snapshot?>(null)
    private val signalSnapshot = AtomicReference<AudioSignalTelemetry.Snapshot?>(null)
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

    private val buildIdentity = DiagnosticBuildIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        commit = BuildConfig.COMMIT_SHA,
    )

    fun publish(value: AudioTransportTelemetry.Snapshot) {
        val previous = transportSnapshot.getAndSet(value)
        ensureWriterStarted()
        if (previous != null && hasAnomalyIncrease(previous, value)) {
            writer.execute { flushLatestSafely() }
        }
    }

    fun publishSignal(value: AudioSignalTelemetry.Snapshot) {
        signalSnapshot.set(value)
        ensureWriterStarted()
    }

    fun latestTransportSnapshot(): AudioTransportTelemetry.Snapshot? = transportSnapshot.get()

    fun latestSignalSnapshot(): AudioSignalTelemetry.Snapshot? = signalSnapshot.get()

    /** Starts a new non-identifying correlation epoch for a service/engine lifetime. */
    fun beginEngineEpoch() {
        engineEpoch = newEpoch()
        signalSnapshot.set(null)
        writer.execute { lastPersistedTransportSnapshot = null }
        ensureWriterStarted()
    }

    /** Clears only the in-memory latest snapshots, preserving persisted history. */
    fun clear() {
        transportSnapshot.set(null)
        signalSnapshot.set(null)
    }

    fun clearHistory() {
        clear()
        writer.execute {
            runCatching { diagnosticsStore()?.clear() }
                .onFailure { Timber.w(it, "Unable to clear RootlessZach diagnostics history") }
            lastPersistedTransportSnapshot = null
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
        val currentSignal = signalSnapshot.get()
        val previous = lastPersistedTransportSnapshot

        // The writer remains alive for the application process. Once audio processing stops, the
        // latest atomic snapshots intentionally remain available to the report UI, but they must
        // not be appended again every five seconds with only a newer wall-clock timestamp.
        if (previous?.capturedAtNanos == current.capturedAtNanos) return

        val droppedBeforeWrite = droppedEventCount.get()
        val nowMs = System.currentTimeMillis()
        val epoch = engineEpoch
        val lines = ArrayList<String>(7)

        if (previous != null) {
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
        currentSignal?.let { signal ->
            lines += AudioDiagnosticJson.signalSnapshot(
                snapshot = signal,
                build = buildIdentity,
                engineEpoch = epoch,
                wallClockEpochMs = nowMs,
            )
        }

        try {
            diagnosticsStore()?.appendLines(lines) ?: return
            lastPersistedTransportSnapshot = current
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

    private fun hasAnomalyIncrease(
        previous: AudioTransportTelemetry.Snapshot,
        current: AudioTransportTelemetry.Snapshot,
    ): Boolean =
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
