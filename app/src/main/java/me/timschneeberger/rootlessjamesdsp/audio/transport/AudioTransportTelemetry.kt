package me.timschneeberger.rootlessjamesdsp.audio.transport

import java.util.Locale

private const val RECOVERY_REASON_LOG_WINDOW_MS = 10_000L

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
            append(" underruns=").append(underrunCount)
            append(" deadlineMisses=").append(deadlineMissCount)
            append(" bypassBuffers=").append(bypassBufferCount)
            append(" processNs=").append(lastProcessingNanos)
            append(" maxProcessNs=").append(maxProcessingNanos)
            append(" loadEwma=").append(String.format(Locale.US, "%.3f", processingLoadEwma))
            lastRecoveryAtNanos?.let { recoveryAt ->
                val recoveryAgeMs = ((capturedAtNanos - recoveryAt).coerceAtLeast(0L) / 1_000_000L)
                append(" recoveryAgeMs=").append(recoveryAgeMs)
                if (recoveryAgeMs <= RECOVERY_REASON_LOG_WINDOW_MS) {
                    lastRecoveryReason?.let { append(" recoveryReason=").append(it) }
                }
            }
            lastErrorCode?.let { append(" lastError=").append(it) }
        }
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
    private var underrunCount = 0
    private var deadlineMissCount = 0L
    private var bypassBufferCount = 0L
    private var lastProcessingNanos = 0L
    private var maxProcessingNanos = 0L
    private var processingLoadEwma = 0.0
    private var lastRecoveryReason: String? = null
    private var lastRecoveryAtNanos: Long? = null
    private var lastErrorCode: Int? = null

    @Synchronized fun configure(rate: Int, channels: Int, samples: Int) {
        sampleRate = rate
        channelCount = channels
        bufferSamples = samples
    }

    @Synchronized fun recordRead(result: AudioTransferResult) {
        totalReadSamples += result.transferredSamples
        partialReadOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let { ioErrorCount++; lastErrorCode = it }
    }

    @Synchronized fun recordWrite(result: AudioTransferResult) {
        totalWrittenSamples += result.transferredSamples
        partialWriteOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let { ioErrorCount++; lastErrorCode = it }
    }

    @Synchronized fun recordProcessing(durationNanos: Long, deadlineNanos: Long, bypassed: Boolean) {
        lastProcessingNanos = durationNanos.coerceAtLeast(0)
        maxProcessingNanos = maxOf(maxProcessingNanos, lastProcessingNanos)
        if (deadlineNanos > 0 && durationNanos > deadlineNanos) deadlineMissCount++
        if (bypassed) bypassBufferCount++
        val ratio = if (deadlineNanos > 0) durationNanos.toDouble() / deadlineNanos else 0.0
        processingLoadEwma = if (processingLoadEwma == 0.0) ratio else processingLoadEwma * 0.9 + ratio * 0.1
    }

    @Synchronized fun recordUnderrunDelta(delta: Int) {
        underrunCount += delta.coerceAtLeast(0)
    }

    @Synchronized fun recordRecovery(reason: String) {
        recoveryCount++
        lastRecoveryReason = reason
        lastRecoveryAtNanos = clockNanos()
    }

    @Synchronized fun snapshot(): Snapshot {
        val now = clockNanos()
        return Snapshot(
            now, sampleRate, channelCount, bufferSamples,
            totalReadSamples, totalWrittenSamples, partialReadOperations, partialWriteOperations,
            zeroProgressOperations, ioErrorCount, recoveryCount, underrunCount,
            deadlineMissCount, bypassBufferCount, lastProcessingNanos, maxProcessingNanos,
            processingLoadEwma, lastRecoveryReason, lastRecoveryAtNanos, lastErrorCode,
        )
    }
}
