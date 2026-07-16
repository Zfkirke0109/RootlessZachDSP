package me.timschneeberger.rootlessjamesdsp.audio.transport

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Allocation-free pre/post signal accumulator for the rootless audio loop.
 *
 * The accumulator stores only aggregate counters and session-salted rolling hashes. It never keeps
 * PCM samples. Recording takes one monitor acquisition per audio buffer, not per sample. Call
 * [snapshot] from a non-real-time thread or at an existing telemetry boundary.
 */
class AudioSignalTelemetry(
    private val hashSeed: Long,
    private val silenceThreshold: Double = DEFAULT_SILENCE_THRESHOLD,
    private val changeThreshold: Double = DEFAULT_CHANGE_THRESHOLD,
    private val clippingThreshold: Double = DEFAULT_CLIPPING_THRESHOLD,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    data class Snapshot(
        val capturedAtNanos: Long,
        val sampleCount: Long,
        val inputRms: Double,
        val outputRms: Double,
        val inputPeak: Double,
        val outputPeak: Double,
        val inputDcOffset: Double,
        val outputDcOffset: Double,
        val inputSilenceRatio: Double,
        val outputSilenceRatio: Double,
        val inputClippedSamples: Long,
        val outputClippedSamples: Long,
        val changedSamples: Long,
        val changedSampleRatio: Double,
        val inputHash: Long,
        val outputHash: Long,
        val outputChanged: Boolean,
    )

    private var sampleCount = 0L
    private var inputSquareSum = 0.0
    private var outputSquareSum = 0.0
    private var inputSum = 0.0
    private var outputSum = 0.0
    private var inputPeak = 0.0
    private var outputPeak = 0.0
    private var inputSilentSamples = 0L
    private var outputSilentSamples = 0L
    private var inputClippedSamples = 0L
    private var outputClippedSamples = 0L
    private var changedSamples = 0L
    private var inputHash = FNV_OFFSET_BASIS xor hashSeed
    private var outputHash = FNV_OFFSET_BASIS xor hashSeed

    init {
        require(silenceThreshold >= 0.0) { "silenceThreshold must not be negative" }
        require(changeThreshold >= 0.0) { "changeThreshold must not be negative" }
        require(clippingThreshold > 0.0) { "clippingThreshold must be positive" }
    }

    @Synchronized
    fun recordFloat(input: FloatArray, output: FloatArray, samples: Int) {
        require(samples >= 0 && samples <= input.size && samples <= output.size)
        for (index in 0 until samples) {
            recordNormalized(input[index].toDouble(), output[index].toDouble())
        }
    }

    @Synchronized
    fun recordShort(input: ShortArray, output: ShortArray, samples: Int) {
        require(samples >= 0 && samples <= input.size && samples <= output.size)
        for (index in 0 until samples) {
            recordNormalized(
                input[index].toDouble() / SHORT_NORMALIZER,
                output[index].toDouble() / SHORT_NORMALIZER,
            )
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val capturedAtNanos = clockNanos()
        val count = sampleCount
        if (count == 0L) {
            return Snapshot(
                capturedAtNanos = capturedAtNanos,
                sampleCount = 0,
                inputRms = 0.0,
                outputRms = 0.0,
                inputPeak = 0.0,
                outputPeak = 0.0,
                inputDcOffset = 0.0,
                outputDcOffset = 0.0,
                inputSilenceRatio = 0.0,
                outputSilenceRatio = 0.0,
                inputClippedSamples = 0,
                outputClippedSamples = 0,
                changedSamples = 0,
                changedSampleRatio = 0.0,
                inputHash = inputHash,
                outputHash = outputHash,
                outputChanged = false,
            )
        }
        val denominator = count.toDouble()
        val changedRatio = changedSamples / denominator
        return Snapshot(
            capturedAtNanos = capturedAtNanos,
            sampleCount = count,
            inputRms = sqrt(inputSquareSum / denominator),
            outputRms = sqrt(outputSquareSum / denominator),
            inputPeak = inputPeak,
            outputPeak = outputPeak,
            inputDcOffset = inputSum / denominator,
            outputDcOffset = outputSum / denominator,
            inputSilenceRatio = inputSilentSamples / denominator,
            outputSilenceRatio = outputSilentSamples / denominator,
            inputClippedSamples = inputClippedSamples,
            outputClippedSamples = outputClippedSamples,
            changedSamples = changedSamples,
            changedSampleRatio = changedRatio,
            inputHash = inputHash,
            outputHash = outputHash,
            outputChanged = changedSamples > 0L || inputHash != outputHash,
        )
    }

    @Synchronized
    fun reset(newHashSeed: Long = hashSeed) {
        sampleCount = 0L
        inputSquareSum = 0.0
        outputSquareSum = 0.0
        inputSum = 0.0
        outputSum = 0.0
        inputPeak = 0.0
        outputPeak = 0.0
        inputSilentSamples = 0L
        outputSilentSamples = 0L
        inputClippedSamples = 0L
        outputClippedSamples = 0L
        changedSamples = 0L
        inputHash = FNV_OFFSET_BASIS xor newHashSeed
        outputHash = FNV_OFFSET_BASIS xor newHashSeed
    }

    private fun recordNormalized(input: Double, output: Double) {
        val safeInput = input.takeIf { it.isFinite() } ?: 0.0
        val safeOutput = output.takeIf { it.isFinite() } ?: 0.0
        val inputAbs = abs(safeInput)
        val outputAbs = abs(safeOutput)

        sampleCount++
        inputSquareSum += safeInput * safeInput
        outputSquareSum += safeOutput * safeOutput
        inputSum += safeInput
        outputSum += safeOutput
        if (inputAbs > inputPeak) inputPeak = inputAbs
        if (outputAbs > outputPeak) outputPeak = outputAbs
        if (inputAbs <= silenceThreshold) inputSilentSamples++
        if (outputAbs <= silenceThreshold) outputSilentSamples++
        if (inputAbs >= clippingThreshold) inputClippedSamples++
        if (outputAbs >= clippingThreshold) outputClippedSamples++
        if (abs(safeInput - safeOutput) > changeThreshold) changedSamples++

        inputHash = updateHash(inputHash, quantizeForHash(safeInput))
        outputHash = updateHash(outputHash, quantizeForHash(safeOutput))
    }

    private fun quantizeForHash(value: Double): Long {
        val bounded = value.coerceIn(-HASH_RANGE, HASH_RANGE)
        return (bounded * HASH_SCALE).toLong()
    }

    private fun updateHash(current: Long, sample: Long): Long {
        var hash = current
        var value = sample
        repeat(Long.SIZE_BYTES) {
            hash = (hash xor (value and 0xffL)) * FNV_PRIME
            value = value ushr 8
        }
        return hash
    }

    companion object {
        const val DEFAULT_SILENCE_THRESHOLD = 1.0e-5
        const val DEFAULT_CHANGE_THRESHOLD = 1.0e-6
        const val DEFAULT_CLIPPING_THRESHOLD = 0.999

        private const val SHORT_NORMALIZER = 32_768.0
        private const val HASH_RANGE = 8.0
        private const val HASH_SCALE = 1_000_000.0
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
