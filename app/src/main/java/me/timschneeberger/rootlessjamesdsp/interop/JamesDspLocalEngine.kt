package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry
import me.timschneeberger.rootlessjamesdsp.diagnostics.RootlessZachDiagnostics
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    private val signalTelemetry = if (BuildConfig.ROOTLESS) {
        AudioSignalTelemetry(hashSeed = System.nanoTime() xor handle)
    } else {
        null
    }
    private var lastSignalPublishNanos = 0L

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            JamesDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
        if (BuildConfig.ROOTLESS) RootlessZachDiagnostics.beginEngineEpoch()
    }

    override fun close() {
        signalTelemetry?.snapshot()?.takeIf { it.sampleCount > 0L }?.let {
            RootlessZachDiagnostics.publishSignal(it)
        }

        val oldHandle = handle
        handle = 0

        // Make sure ongoing async calls to native have enough time to finish
        Timer().schedule(100) {
            JamesDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        }
        recordInt16Signal(input, output, offset, length)
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
        }
        recordFloatSignal(input, output, offset, length)
    }

    private fun recordInt16Signal(
        input: ShortArray,
        output: ShortArray,
        offset: Int,
        length: Int,
    ) {
        val telemetry = signalTelemetry ?: return
        val range = resolveSignalRange(input.size, output.size, offset, length) ?: return
        telemetry.recordShort(input, range.inputOffset, output, range.outputOffset, range.samples)
        publishSignalIfDue(telemetry)
    }

    private fun recordFloatSignal(
        input: FloatArray,
        output: FloatArray,
        offset: Int,
        length: Int,
    ) {
        val telemetry = signalTelemetry ?: return
        val range = resolveSignalRange(input.size, output.size, offset, length) ?: return
        telemetry.recordFloat(input, range.inputOffset, output, range.outputOffset, range.samples)
        publishSignalIfDue(telemetry)
    }

    private fun publishSignalIfDue(telemetry: AudioSignalTelemetry) {
        val now = System.nanoTime()
        if (now - lastSignalPublishNanos < SIGNAL_PUBLISH_INTERVAL_NANOS) return
        RootlessZachDiagnostics.publishSignal(telemetry.snapshot())
        lastSignalPublishNanos = now
    }

    private fun resolveSignalRange(
        inputSize: Int,
        outputSize: Int,
        offset: Int,
        length: Int,
    ): SignalRange? {
        if (offset < 0 && length < 0) {
            val samples = minOf(inputSize, outputSize)
            return samples.takeIf { it > 0 }?.let { SignalRange(0, 0, it) }
        }
        if (offset < 0 || length <= 0 || offset >= inputSize) return null
        val samples = minOf(length, inputSize - offset, outputSize)
        return samples.takeIf { it > 0 }?.let { SignalRange(offset, 0, it) }
    }

    private data class SignalRange(
        val inputOffset: Int,
        val outputOffset: Int,
        val samples: Int,
    )

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return JamesDspWrapper.setLimiter(handle, threshold, release) and JamesDspWrapper.setPostGain(handle, postGain)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return JamesDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return JamesDspWrapper.setBassBoost(handle, enable, maxGain)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setVacuumTube(handle, enable, level)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return JamesDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return JamesDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return JamesDspWrapper.setGraphicEq(handle, enable, bands)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprog(handle, enable, name, script)
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return JamesDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return JamesDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        JamesDspWrapper.freezeLiveprogExecution(handle, freeze)
    }

    companion object {
        private const val SIGNAL_PUBLISH_INTERVAL_NANOS = 1_000_000_000L
    }
}
