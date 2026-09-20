package me.timschneeberger.rootlessjamesdsp.audio.transport

/** Result of a bounded partial audio transfer. */
data class AudioTransferResult(
    val requestedSamples: Int,
    val transferredSamples: Int,
    val operationCount: Int,
    val partialOperationCount: Int,
    val zeroProgressCount: Int,
    val errorCode: Int? = null,
) {
    val completed: Boolean
        get() = errorCode == null && transferredSamples == requestedSamples
    val madeProgress: Boolean
        get() = transferredSamples > 0
}

/** Deterministic helper for partial AudioRecord reads and AudioTrack writes. */
object AudioTransfers {
    const val DEFAULT_MAX_ZERO_PROGRESS = 3

    fun transferFully(
        requestedSamples: Int,
        maxZeroProgress: Int = DEFAULT_MAX_ZERO_PROGRESS,
        shouldStop: () -> Boolean = { false },
        transfer: (offsetSamples: Int, remainingSamples: Int) -> Int,
    ): AudioTransferResult {
        require(requestedSamples >= 0)
        require(maxZeroProgress > 0)
        if (requestedSamples == 0) return AudioTransferResult(0, 0, 0, 0, 0)

        var transferred = 0
        var operations = 0
        var partialOperations = 0
        var zeroProgress = 0
        while (transferred < requestedSamples && !shouldStop()) {
            val remaining = requestedSamples - transferred
            val result = transfer(transferred, remaining)
            operations++
            when {
                result < 0 -> return AudioTransferResult(
                    requestedSamples,
                    transferred,
                    operations,
                    partialOperations,
                    zeroProgress,
                    result,
                )
                result == 0 -> {
                    zeroProgress++
                    if (zeroProgress >= maxZeroProgress) break
                    Thread.yield()
                }
                else -> {
                    val accepted = result.coerceAtMost(remaining)
                    if (accepted < remaining) partialOperations++
                    transferred += accepted
                    zeroProgress = 0
                }
            }
        }
        return AudioTransferResult(
            requestedSamples,
            transferred,
            operations,
            partialOperations,
            zeroProgress,
        )
    }
}
