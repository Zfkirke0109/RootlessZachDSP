package me.timschneeberger.rootlessjamesdsp.audio.transport

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
