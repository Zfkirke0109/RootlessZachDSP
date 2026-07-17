package me.timschneeberger.rootlessjamesdsp.audio.transport

import java.util.Locale

private const val EVENT_REASON_LOG_WINDOW_MS = 10_000L

/**
 * Single-writer, multi-reader transport counters.
 *
 * The urgent audio thread is the only writer. Snapshot publication happens on a lower-priority
 * worker and reads volatile fields without taking a monitor that could block the audio path.
 *
 * Processing percentiles use a preallocated ring written by the urgent thread. Sorting and
 * percentile calculation happen only when the lower-priority snapshot worker requests a snapshot.
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
        val lastReconfigurationReason: String? = null,
        val lastReconfigurationAtNanos: Long? = null,
        val processingWindowSamples: Int = 0,
        val processingP50Nanos: Long = 0L,
        val processingP95Nanos: Long = 0L,
        val processingP99Nanos: Long = 0L,
        val currentConsecutiveDeadlineMisses: Int = 0,
        val maxConsecutiveDeadlineMisses: Int = 0,
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
            append(" deadlineMissStreak=").append(currentConsecutiveDeadlineMisses)
            append(" maxDeadlineMissStreak=").append(maxConsecutiveDeadlineMisses)
            append(" bypassBuffers=").append(bypassBufferCount)
            append(" processNs=").append(lastProcessingNanos)
            append(" maxProcessNs=").append(maxProcessingNanos)
            append(" processWindowSamples=").append(processingWindowSamples)
            append(" processP50Ns=").append(processingP50Nanos)
            append(" processP95Ns=").append(processingP95Nanos)
            append(" processP99Ns=").append(processingP99Nanos)
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
    private var currentConsecutiveDeadlineMisses = 0
    @Volatile
    private var maxConsecutiveDeadlineMisses = 0
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

    private val processingWindowMicros = IntArray(PROCESSING_WINDOW_CAPACITY)
    @Volatile
    private var processingWindowWriteIndex = 0
    @Volatile
    private var processingWindowSampleCount = 0

    fun configure(rate: Int, channels: Int, samples: Int) {
        sampleRate = rate
        channelCount = channels
        bufferSamples = samples
        trackGeneration++
        activeTrackUnderrunCount = 0
        resetProcessingWindow()
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
        partialWriteOperations += result.partialOperationCount
        zeroProgressOperations += result.zeroProgressCount
        result.errorCode?.let {
            ioErrorCount++
            lastErrorCode = it
        }
    }

    fun recordProcessing(durationNanos: Long, deadlineNanos: Long, bypassed: Boolean) {
        val safeDurationNanos = durationNanos.coerceAtLeast(0L)
        lastProcessingNanos = safeDurationNanos
        maxProcessingNanos = maxOf(maxProcessingNanos, safeDurationNanos)
        recordProcessingDuration(safeDurationNanos)

        val missedDeadline = deadlineNanos > 0L && safeDurationNanos > deadlineNanos
        if (missedDeadline) {
            deadlineMissCount++
            val nextStreak = currentConsecutiveDeadlineMisses + 1
            currentConsecutiveDeadlineMisses = nextStreak
            maxConsecutiveDeadlineMisses = maxOf(maxConsecutiveDeadlineMisses, nextStreak)
        } else {
            currentConsecutiveDeadlineMisses = 0
        }

        if (bypassed) bypassBufferCount++
        val ratio =
            if (deadlineNanos > 0L) safeDurationNanos.toDouble() / deadlineNanos else 0.0
        processingLoadEwma =
            if (processingLoadEwma == 0.0) ratio else processingLoadEwma * 0.9 + ratio * 0.1
    }

    /** Records the monotonic underrun counter belonging to the currently active AudioTrack. */
    fun recordActiveTrackUnderrunCount(currentCount: Int) {
        val safeCurrent = currentCount.coerceAtLeast(0)
        underrunCount += (safeCurrent - activeTrackUnderrunCount).coerceAtLeast(0)
        activeTrackUnderrunCount = safeCurrent
    }

    /** Retained for deterministic tests and non-AudioTrack transports. */
    fun recordUnderrunDelta(delta: Int) {
        val safeDelta = delta.coerceAtLeast(0)
        underrunCount += safeDelta
        activeTrackUnderrunCount += safeDelta
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
        val percentiles = snapshotProcessingPercentiles()
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
            processingWindowSamples = percentiles.sampleCount,
            processingP50Nanos = percentiles.p50Nanos,
            processingP95Nanos = percentiles.p95Nanos,
            processingP99Nanos = percentiles.p99Nanos,
            currentConsecutiveDeadlineMisses = currentConsecutiveDeadlineMisses,
            maxConsecutiveDeadlineMisses = maxConsecutiveDeadlineMisses,
        )
    }

    private fun recordProcessingDuration(durationNanos: Long) {
        val micros = (durationNanos / NANOS_PER_MICROSECOND)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val index = processingWindowWriteIndex
        processingWindowMicros[index] = micros
        processingWindowWriteIndex = (index + 1) % PROCESSING_WINDOW_CAPACITY
        if (processingWindowSampleCount < PROCESSING_WINDOW_CAPACITY) {
            processingWindowSampleCount++
        }
    }

    private fun resetProcessingWindow() {
        processingWindowWriteIndex = 0
        processingWindowSampleCount = 0
        currentConsecutiveDeadlineMisses = 0
        maxConsecutiveDeadlineMisses = 0
    }

    private fun snapshotProcessingPercentiles(): ProcessingPercentiles {
        val count = processingWindowSampleCount.coerceIn(0, PROCESSING_WINDOW_CAPACITY)
        if (count == 0) return ProcessingPercentiles.EMPTY

        val endExclusive = processingWindowWriteIndex
        val start = (endExclusive - count + PROCESSING_WINDOW_CAPACITY) %
            PROCESSING_WINDOW_CAPACITY
        val sortedMicros = IntArray(count)
        for (index in 0 until count) {
            sortedMicros[index] =
                processingWindowMicros[(start + index) % PROCESSING_WINDOW_CAPACITY]
        }
        sortedMicros.sort()
        return ProcessingPercentiles(
            sampleCount = count,
            p50Nanos = nearestRankMicros(sortedMicros, 50) * NANOS_PER_MICROSECOND,
            p95Nanos = nearestRankMicros(sortedMicros, 95) * NANOS_PER_MICROSECOND,
            p99Nanos = nearestRankMicros(sortedMicros, 99) * NANOS_PER_MICROSECOND,
        )
    }

    private fun nearestRankMicros(sortedMicros: IntArray, percentile: Int): Long {
        val rank = (
            (sortedMicros.size.toLong() * percentile + PERCENTILE_DENOMINATOR - 1L) /
                PERCENTILE_DENOMINATOR
            )
            .toInt()
            .coerceIn(1, sortedMicros.size)
        return sortedMicros[rank - 1].toLong()
    }

    private data class ProcessingPercentiles(
        val sampleCount: Int,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
    ) {
        companion object {
            val EMPTY = ProcessingPercentiles(0, 0L, 0L, 0L)
        }
    }

    companion object {
        private const val PROCESSING_WINDOW_CAPACITY = 512
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val PERCENTILE_DENOMINATOR = 100L
    }
}
