
package me.timschneeberger.rootlessjamesdsp.audio.transport

/** Conservative adaptive interleaved-PCM block-size controller. */
class AdaptiveBufferController(
    minimumSamples: Int,
    initialSamples: Int,
    maximumSamples: Int = 16_384,
    val alignmentSamples: Int = 128,
    private val pressureIntervalsBeforeGrow: Int = 2,
    private val stableIntervalsBeforeShrink: Int = 45,
    private val allowShrink: Boolean = true,
) {
    data class Observation(
        val underrunDelta: Int = 0,
        val deadlineMissDelta: Int = 0,
        val processingLoadRatio: Double = 0.0,
        val ioError: Boolean = false,
    )

    enum class Reason { NONE, UNDERRUN, DEADLINE_PRESSURE, IO_ERROR, STABLE_SHRINK }

    data class Decision(val previousSamples: Int, val newSamples: Int, val reason: Reason) {
        val changed: Boolean get() = previousSamples != newSamples
    }

    data class BurstPolicy(
        val minimumSamples: Int,
        val initialSamples: Int,
        val maximumSamples: Int,
        val alignmentSamples: Int,
    )

    init {
        require(alignmentSamples > 0) { "alignmentSamples must be positive" }
        require(pressureIntervalsBeforeGrow > 0) { "pressureIntervalsBeforeGrow must be positive" }
        require(stableIntervalsBeforeShrink > 0) { "stableIntervalsBeforeShrink must be positive" }
    }

    private val minimumSamples =
        alignUp(minimumSamples.coerceAtLeast(alignmentSamples), alignmentSamples)
    private val maximumSamples =
        alignDown(maximumSamples.coerceAtLeast(this.minimumSamples), alignmentSamples)
            .coerceAtLeast(this.minimumSamples)

    var currentSamples =
        alignUp(initialSamples.coerceAtLeast(this.minimumSamples), alignmentSamples)
            .coerceAtMost(this.maximumSamples)
        private set

    private var pressureIntervals = 0
    private var stableIntervals = 0

    fun observe(observation: Observation): Decision {
        val previous = currentSamples
        val pressureReason = when {
            observation.ioError -> Reason.IO_ERROR
            observation.underrunDelta > 0 -> Reason.UNDERRUN
            observation.deadlineMissDelta > 0 || observation.processingLoadRatio >= 0.90 ->
                Reason.DEADLINE_PRESSURE
            else -> Reason.NONE
        }

        if (pressureReason != Reason.NONE) {
            pressureIntervals++
            stableIntervals = 0
            if (pressureIntervals >= pressureIntervalsBeforeGrow && currentSamples < maximumSamples) {
                currentSamples =
                    alignUp(
                        (currentSamples.toLong() * 2L)
                            .coerceAtMost(maximumSamples.toLong())
                            .toInt(),
                        alignmentSamples,
                    ).coerceAtMost(maximumSamples)
                pressureIntervals = 0
                return Decision(previous, currentSamples, pressureReason)
            }
            return Decision(previous, currentSamples, Reason.NONE)
        }

        pressureIntervals = 0
        stableIntervals = if (observation.processingLoadRatio in 0.0..0.55) {
            stableIntervals + 1
        } else {
            0
        }

        if (allowShrink && stableIntervals >= stableIntervalsBeforeShrink && currentSamples > minimumSamples) {
            currentSamples =
                alignDown((currentSamples / 2).coerceAtLeast(minimumSamples), alignmentSamples)
                    .coerceAtLeast(minimumSamples)
            stableIntervals = 0
            return Decision(previous, currentSamples, Reason.STABLE_SHRINK)
        }

        return Decision(previous, currentSamples, Reason.NONE)
    }

    companion object {
        fun burstAlignedPolicy(
            framesPerBurst: Int,
            channelCount: Int,
            configuredSamples: Int,
            maximumSamples: Int = 16_384,
            minimumBursts: Int = 8,
            initialBursts: Int = 16,
        ): BurstPolicy {
            require(framesPerBurst > 0) { "framesPerBurst must be positive" }
            require(channelCount > 0) { "channelCount must be positive" }
            require(configuredSamples > 0) { "configuredSamples must be positive" }
            require(maximumSamples > 0) { "maximumSamples must be positive" }
            require(minimumBursts > 0) { "minimumBursts must be positive" }
            require(initialBursts >= minimumBursts) {
                "initialBursts must be greater than or equal to minimumBursts"
            }

            val alignment = Math.multiplyExact(framesPerBurst, channelCount)
            val maximum =
                alignDown(maximumSamples, alignment).coerceAtLeast(alignment)
            val minimum =
                alignUp(Math.multiplyExact(alignment, minimumBursts), alignment)
                    .coerceAtMost(maximum)
            val requestedInitial = maxOf(
                configuredSamples,
                Math.multiplyExact(alignment, initialBursts),
            )
            val initial =
                alignUp(requestedInitial.coerceAtMost(maximum), alignment)
                    .coerceIn(minimum, maximum)

            return BurstPolicy(
                minimumSamples = minimum,
                initialSamples = initial,
                maximumSamples = maximum,
                alignmentSamples = alignment,
            )
        }

        fun bufferDurationNanos(interleavedSamples: Int, sampleRate: Int, channelCount: Int): Long {
            require(interleavedSamples >= 0 && sampleRate > 0 && channelCount > 0)
            val frames = interleavedSamples.toDouble() / channelCount
            return ((frames / sampleRate) * 1_000_000_000.0).toLong()
        }

        private fun alignUp(samples: Int, alignment: Int): Int {
            val value = samples.toLong()
            val aligned = ((value + alignment - 1L) / alignment) * alignment
            require(aligned <= Int.MAX_VALUE) { "aligned sample count exceeds Int range" }
            return aligned.toInt()
        }

        private fun alignDown(samples: Int, alignment: Int): Int =
            (samples / alignment) * alignment
    }
}
