package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory bridge plus bounded app-private persistence for rootless transport diagnostics.
 *
 * [publish] is called by the urgent audio thread. It performs atomic updates and simple counter
 * comparisons only. JSON serialization and file I/O are performed by a dedicated writer thread.
 * Periodic snapshots are written approximately every five seconds; anomaly counter increases also
 * request an immediate off-thread flush.
 */
object RootlessZachDiagnostics {
    private val snapshot = AtomicReference<AudioTransportTelemetry.Snapshot?>(null)
    private val writerStarted = AtomicBoolean(false)
    private val store = AtomicReference<RotatingJsonlStore?>(null)
    private val droppedEventCount = AtomicLong(0L)

    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RootlessZachDiagnostics").apply { isDaemon = true }
    }

    @Volatile
    private var engineEpoch = newEpoch()

    // Accessed only by the diagnostics writer thread.
    private var lastPersistedSnapshot: AudioTransportTelemetry.Snapshot? = null

    private val buildIdentity = DiagnosticBuildIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        commit = BuildConfig.COMMIT_SHA,
    )

    fun publish(value: AudioTransportTelemetry.Snapshot) {
        val previous = snapshot.getAndSet(value)
        ensureWriterStarted()
        if (previous != null && hasAnomalyIncrease(previous, value)) {
            writer.execute { flushLatestSafely() }
        }
    }

    fun latestTransportSnapshot(): AudioTransportTelemetry.Snapshot? = snapshot.get()

    /** Begins a new correlation epoch; call from a service-start hook in the next diagnostics stage. */
    fun beginEngineEpoch() {
        engineEpoch = newEpoch()
        writer.execute { lastPersistedSnapshot = null }
        ensureWriterStarted()
    }

    /** Clears only the in-memory latest snapshot, preserving persisted history. */
    fun clear() = snapshot.set(null)

    fun clearHistory() {
        snapshot.set(null)
        writer.execute {
            runCatching { diagnosticsStore()?.clear() }
                .onFailure { Timber.w(it, "Unable to clear RootlessZach diagnostics history") }
            lastPersistedSnapshot = null
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
        val current = snapshot.get() ?: return
        val previous = lastPersistedSnapshot
        val droppedBeforeWrite = droppedEventCount.get()
        val nowMs = System.currentTimeMillis()
        val epoch = engineEpoch
        val lines = ArrayList<String>(6)

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

        try {
            diagnosticsStore()?.appendLines(lines) ?: return
            lastPersistedSnapshot = current
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
