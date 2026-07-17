package me.timschneeberger.rootlessjamesdsp.audio.transport

import kotlin.math.ceil

/** Conservative adaptive interleaved-PCM block-size controller. */
class AdaptiveBufferController(
    minimumSamples: Int,
    initialSamples: Int,
    maximumSamples: Int = 16_384,
    private val alignmentSamples: Int = 128,
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

    private val minimumSamples = align(minimumSamples.coerceAtLeast(alignmentSamples))
    private val maximumSamples = align(maximumSamples.coerceAtLeast(this.minimumSamples))
    var currentSamples = align(initialSamples.coerceIn(this.minimumSamples, this.maximumSamples))
        private set
    private var pressureIntervals = 0
    private var stableIntervals = 0

    fun observe(observation: Observation): Decision {
        val previous = currentSamples
        val pressureReason = when {
            observation.ioError -> Reason.IO_ERROR
            observation.underrunDelta > 0 -> Reason.UNDERRUN
            observation.deadlineMissDelta > 0 || observation.processingLoadRatio >= 0.90 -> Reason.DEADLINE_PRESSURE
            else -> Reason.NONE
        }
        if (pressureReason != Reason.NONE) {
            pressureIntervals++
            stableIntervals = 0
            if (pressureIntervals >= pressureIntervalsBeforeGrow && currentSamples < maximumSamples) {
                currentSamples = align((currentSamples * 2).coerceAtMost(maximumSamples))
                pressureIntervals = 0
                return Decision(previous, currentSamples, pressureReason)
            }
            return Decision(previous, currentSamples, Reason.NONE)
        }

        pressureIntervals = 0
        stableIntervals = if (observation.processingLoadRatio in 0.0..0.55) stableIntervals + 1 else 0
        if (allowShrink && stableIntervals >= stableIntervalsBeforeShrink && currentSamples > minimumSamples) {
            currentSamples = align((currentSamples / 2).coerceAtLeast(minimumSamples))
            stableIntervals = 0
            return Decision(previous, currentSamples, Reason.STABLE_SHRINK)
        }
        return Decision(previous, currentSamples, Reason.NONE)
    }

    private fun align(samples: Int) =
        (ceil(samples.toDouble() / alignmentSamples) * alignmentSamples).toInt()

    companion object {
        fun bufferDurationNanos(interleavedSamples: Int, sampleRate: Int, channelCount: Int): Long {
            require(interleavedSamples >= 0 && sampleRate > 0 && channelCount > 0)
            val frames = interleavedSamples.toDouble() / channelCount
            return ((frames / sampleRate) * 1_000_000_000.0).toLong()
        }
    }
}
