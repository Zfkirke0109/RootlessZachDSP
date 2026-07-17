package me.timschneeberger.rootlessjamesdsp.audio.transport

import kotlin.math.roundToInt

/** Allocation-free linear ramp used for wet/dry and recovery transitions. */
class LinearRamp(initialValue: Float = 1f) {
    var value = initialValue.coerceIn(0f, 1f)
        private set
    private var target = value
    private var step = 0f
    private var remainingSamples = 0
    val isSettled get() = remainingSamples == 0
    val targetValue get() = target

    fun setImmediate(newValue: Float) {
        value = newValue.coerceIn(0f, 1f)
        target = value
        step = 0f
        remainingSamples = 0
    }

    fun rampTo(newTarget: Float, sampleCount: Int) {
        target = newTarget.coerceIn(0f, 1f)
        if (sampleCount <= 0 || value == target) return setImmediate(target)
        remainingSamples = sampleCount
        step = (target - value) / sampleCount
    }

    fun next(): Float {
        if (remainingSamples > 0) {
            value += step
            remainingSamples--
            if (remainingSamples == 0) value = target
        }
        return value
    }

    fun applyInPlace(buffer: FloatArray) {
        for (index in buffer.indices) buffer[index] *= next()
    }

    fun applyInPlace(buffer: ShortArray) {
        for (index in buffer.indices) {
            buffer[index] = (buffer[index].toInt() * next()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

class WetDryCrossfader(initialWet: Float = 1f) {
    private val ramp = LinearRamp(initialWet)
    val wet get() = ramp.value
    val targetWet get() = ramp.targetValue
    val isSettled get() = ramp.isSettled
    fun setImmediate(wet: Float) = ramp.setImmediate(wet)
    fun rampTo(wet: Float, sampleCount: Int) = ramp.rampTo(wet, sampleCount)

    fun mix(dry: FloatArray, processed: FloatArray, output: FloatArray) {
        for (index in output.indices) {
            val wet = ramp.next()
            output[index] = dry[index] + ((processed[index] - dry[index]) * wet)
        }
    }

    fun mix(dry: ShortArray, processed: ShortArray, output: ShortArray) {
        for (index in output.indices) {
            val wet = ramp.next()
            val value = dry[index] + ((processed[index] - dry[index]) * wet)
            output[index] = value.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
