package me.timschneeberger.rootlessjamesdsp.audio.transport

import java.util.Locale

private const val EVENT_REASON_LOG_WINDOW_MS = 10_000L
private const val STARTUP_PRIMING_BUFFERS = 2L

/**
 * Single-writer, multi-reader transport counters.
 *
 * The urgent audio thread is the only writer. Snapshot publication happens on a lower-priority
 * worker and reads volatile fields without taking a monitor that could block the audio path.
 */
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
        val startupPrimingUnderrunCount: Int = 0,
        val runtimeStarvationUnderrunCount: Int = 0,
        val activeTrackWrittenSamples: Long = 0L,
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
            append(" startupPrimingUnderruns=").append(startupPrimingUnderrunCount)
            append(" runtimeStarvationUnderruns=").append(runtimeStarvationUnderrunCount)
            append(" trackGeneration=").append(trackGeneration)
            append(" activeTrackWrittenSamples=").append(activeTrackWrittenSamples)
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

    @Volatile
    private var sampleRate = 0
    @Volatile
    private var channelCount = 0
    @Volatile
    private var bufferSamples = 0
    @Volatile
    private var totalReadSamples = 0L
    @Volatile
    private var totalWrittenSamples = 0L
    @Volatile
    private var partialReadOperations = 0L
    @Volatile
    private var partialWriteOperations = 0L
    @Volatile
    private var zeroProgressOperations = 0L
    @Volatile
    private var ioErrorCount = 0L
    @Volatile
    private var recoveryCount = 0L
    @Volatile
    private var reconfigurationCount = 0L
    @Volatile
    private var underrunCount = 0
    @Volatile
    private var activeTrackUnderrunCount = 0
    @Volatile
    private var startupPrimingUnderrunCount = 0
    @Volatile
    private var runtimeStarvationUnderrunCount = 0
    @Volatile
    private var activeTrackWrittenSamples = 0L
    @Volatile
    private var trackGeneration = 0
    @Volatile
    private var deadlineMissCount = 0L
    @Volatile
    private var bypassBufferCount = 0L
    @Volatile
    private var lastProcessingNanos = 0L
    @Volatile
    private var maxProcessingNanos = 0L
    @Volatile
    private var processingLoadEwma = 0.0
    @Volatile
    private var lastRecoveryReason: String? = null
    @Volatile
    private var lastRecoveryAtNanos: Long? = null
    @Volatile
    private var lastReconfigurationReason: String? = null
    @Volatile
    private var lastReconfigurationAtNanos: Long? = null
    @Volatile
    private var lastErrorCode: Int? = null

    fun configure(rate: Int, channels: Int, samples: Int) {
        sampleRate = rate
        channelCount = channels
        bufferSamples = samples
        trackGeneration++
        activeTrackUnderrunCount = 0
        activeTrackWrittenSamples = 0L
    }

    fun recordRead(result: AudioTransferResult) {
        totalReadSamples += result.transferredSamples
        partialReadOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let {
            ioErrorCount++
            lastErrorCode = it
        }
    }

    fun recordWrite(result: AudioTransferResult) {
        totalWrittenSamples += result.transferredSamples
        activeTrackWrittenSamples += result.transferredSamples
        partialWriteOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let {
            ioErrorCount++
            lastErrorCode = it
        }
    }

    fun recordProcessing(durationNanos: Long, deadlineNanos: Long, bypassed: Boolean) {
        lastProcessingNanos = durationNanos.coerceAtLeast(0)
        maxProcessingNanos = maxOf(maxProcessingNanos, lastProcessingNanos)
        if (deadlineNanos > 0 && durationNanos > deadlineNanos) deadlineMissCount++
        if (bypassed) bypassBufferCount++
        val ratio = if (deadlineNanos > 0) durationNanos.toDouble() / deadlineNanos else 0.0
        processingLoadEwma = if (processingLoadEwma == 0.0) ratio else processingLoadEwma * 0.9 + ratio * 0.1
    }

    /**
     * Records the monotonic underrun counter belonging to the currently active AudioTrack.
     *
     * Android frequently reports one or more underruns while a newly created track is being primed.
     * Keep those separate from starvation that occurs after at least two complete application
     * buffers have been written to the current track generation.
     */
    fun recordActiveTrackUnderrunCount(currentCount: Int) {
        val safeCurrent = currentCount.coerceAtLeast(0)
        val delta = (safeCurrent - activeTrackUnderrunCount).coerceAtLeast(0)
        if (delta > 0) {
            underrunCount += delta
            if (isTrackStillPriming()) startupPrimingUnderrunCount += delta
            else runtimeStarvationUnderrunCount += delta
        }
        activeTrackUnderrunCount = safeCurrent
    }

    /** Retained for deterministic tests and non-AudioTrack transports. */
    fun recordUnderrunDelta(delta: Int) {
        val safeDelta = delta.coerceAtLeast(0)
        underrunCount += safeDelta
        activeTrackUnderrunCount += safeDelta
        if (isTrackStillPriming()) startupPrimingUnderrunCount += safeDelta
        else runtimeStarvationUnderrunCount += safeDelta
    }

    fun recordRecovery(reason: String) {
        recoveryCount++
        lastRecoveryReason = reason
        lastRecoveryAtNanos = clockNanos()
    }

    fun recordReconfiguration(reason: String) {
        reconfigurationCount++
        lastReconfigurationReason = reason
        lastReconfigurationAtNanos = clockNanos()
    }

    fun currentUnderrunCount(): Int = underrunCount

    fun currentDeadlineMissCount(): Long = deadlineMissCount

    fun currentProcessingLoadEwma(): Double = processingLoadEwma

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
            startupPrimingUnderrunCount = startupPrimingUnderrunCount,
            runtimeStarvationUnderrunCount = runtimeStarvationUnderrunCount,
            activeTrackWrittenSamples = activeTrackWrittenSamples,
            lastReconfigurationReason = lastReconfigurationReason,
            lastReconfigurationAtNanos = lastReconfigurationAtNanos,
        )
    }

    private fun isTrackStillPriming(): Boolean {
        val threshold = bufferSamples.toLong().coerceAtLeast(1L) * STARTUP_PRIMING_BUFFERS
        return activeTrackWrittenSamples < threshold
    }
}
