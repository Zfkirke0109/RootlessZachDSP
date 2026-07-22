package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.runBlocking
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmSource
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

data class EnhancedPlaybackStatus(
    val sourceSampleRate: Int,
    val sourceBitDepth: Int,
    val sourceChannels: Int,
    val decodedEncoding: String,
    val audioTrackSampleRate: Int,
    val audioTrackEncoding: Int,
    val audioTrackChannels: Int,
    val activeDspStages: List<String>,
    val resamplingStatus: String,
)

/** Source-owning stereo playback through the same JamesDSP engine used by rootless capture. */
class EnhancedLosslessPlaybackEngine(
    context: Context,
    private val source: LosslessPcmSource,
    private val eventListener: (Event) -> Unit,
) : Closeable {
    sealed interface Event {
        data class Started(val status: EnhancedPlaybackStatus) : Event
        data object Paused : Event
        data object Resumed : Event
        data object Completed : Event
        data object Stopped : Event
        data class Failed(val reason: String) : Event
    }

    private val appContext = context.applicationContext
    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var dspEngine: JamesDspLocalEngine? = null

    @Synchronized
    fun start() {
        check(worker == null) { "Enhanced lossless engine can only be started once" }
        worker = Thread(::runPlayback, THREAD_NAME).apply { start() }
    }

    fun setPaused(paused: Boolean) {
        if (stopRequested.get()) return
        pauseRequested.set(paused)
        worker?.let(LockSupport::unpark)
    }

    fun isPaused(): Boolean = pauseRequested.get()

    override fun close() {
        stopRequested.set(true)
        val thread = worker
        if (thread == null) {
            closeResources()
            return
        }
        runCatching { audioTrack?.stop() }
        LockSupport.unpark(thread)
        thread.interrupt()
    }

    private fun runPlayback() {
        try {
            val metadata = source.metadata
            check(metadata.channelCount == 2) {
                "Enhanced playback currently supports stereo sources only; channel layout was preserved and not downmixed"
            }
            check(metadata.canonicalInterleavedChannelOrder) {
                "Enhanced playback rejected a noncanonical channel order instead of swapping speakers"
            }
            val pcmContainer = PcmContainer.forNativeBitDepth(metadata.nativeBitDepth)
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val minimumBytes = AudioTrack.getMinBufferSize(
                metadata.sampleRate,
                channelMask,
                pcmContainer.androidEncoding,
            )
            check(minimumBytes > 0) { "Android rejected the decoded PCM output format" }
            val format = AudioFormat.Builder()
                .setSampleRate(metadata.sampleRate)
                .setEncoding(pcmContainer.androidEncoding)
                .setChannelMask(channelMask)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimumBytes, TRANSFER_BUFFER_BYTES))
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Enhanced AudioTrack did not initialize" }
            audioTrack = track
            track.setVolume(1.0f)

            // Direct Player owns a separate engine. It must not reset or publish into the
            // capture service's process-wide diagnostics epoch.
            val engine = JamesDspLocalEngine(appContext, publishRootlessDiagnostics = false)
            dspEngine = engine
            engine.sampleRate = metadata.sampleRate.toFloat()
            val appliedNamespaces = runBlocking { engine.syncWithPreferencesAndWait() }
            val stageSnapshot = DspStageSnapshot.read(appContext, appliedNamespaces)

            val framesPerChunk = maxOf(1, TRANSFER_BUFFER_BYTES / (metadata.channelCount * pcmContainer.bytesPerSample))
            val input = IntArray(framesPerChunk * metadata.channelCount)
            val output = IntArray(input.size)
            val packed = ByteBuffer.allocateDirect(output.size * pcmContainer.bytesPerSample)

            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            track.play()
            eventListener(
                Event.Started(
                    EnhancedPlaybackStatus(
                        sourceSampleRate = metadata.sampleRate,
                        sourceBitDepth = metadata.nativeBitDepth,
                        sourceChannels = metadata.channelCount,
                        decodedEncoding = buildString {
                            append("signed left-aligned PCM32")
                            if (metadata.decodedTransformations.isNotEmpty()) {
                                append("; ")
                                append(metadata.decodedTransformations.joinToString())
                            }
                        },
                        audioTrackSampleRate = track.format.sampleRate,
                        audioTrackEncoding = track.format.encoding,
                        audioTrackChannels = track.format.channelCount,
                        activeDspStages = stageSnapshot.activeStages,
                        resamplingStatus = "No app resampler; Android mixer/hardware conversion is unknown",
                    ),
                ),
            )

            var reportedPause = false
            var writtenBytes = 0L
            val frameSizeBytes = metadata.channelCount * pcmContainer.bytesPerSample
            val frameProgress = LosslessFrameProgress(metadata.totalFrames)
            while (!stopRequested.get()) {
                if (pauseRequested.get()) {
                    if (!reportedPause) {
                        runCatching { track.pause() }
                        eventListener(Event.Paused)
                        reportedPause = true
                    }
                    LockSupport.parkNanos(PAUSE_POLL_NANOS)
                    continue
                }
                if (reportedPause) {
                    track.play()
                    eventListener(Event.Resumed)
                    reportedPause = false
                }

                val frames = source.readFrames(input, framesPerChunk)
                if (frames == 0) {
                    frameProgress.verifyEndOfStream()
                    val drained = AudioTrackDrain.awaitPlaybackHead(
                        track,
                        writtenBytes / frameSizeBytes,
                        stopRequested::get,
                    )
                    if (drained) {
                        eventListener(Event.Completed)
                    } else if (stopRequested.get()) {
                        eventListener(Event.Stopped)
                    } else {
                        eventListener(Event.Failed("Timed out while draining the enhanced AudioTrack"))
                    }
                    return
                }
                check(frames > 0) { "Lossless decoder returned an invalid frame count" }
                frameProgress.record(frames)
                val sampleCount = frames * metadata.channelCount
                engine.processInt32(input, output, 0, sampleCount)
                var remaining = LeftAlignedPcmPacker.pack(output, sampleCount, pcmContainer, packed)
                while (remaining > 0 && !stopRequested.get()) {
                    val written = track.write(packed, remaining, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        if (stopRequested.get()) {
                            eventListener(Event.Stopped)
                            return
                        }
                        error("AudioTrack write failed with code $written")
                    }
                    remaining -= written
                    writtenBytes += written
                }
            }
            eventListener(Event.Stopped)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            eventListener(Event.Stopped)
        } catch (error: Throwable) {
            eventListener(Event.Failed(error.message ?: error.javaClass.simpleName))
        } finally {
            closeResources()
        }
    }

    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        runCatching { source.close() }
        dspEngine?.let { runCatching { it.close() } }
        dspEngine = null
        audioTrack?.let { track ->
            runCatching { if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }

    companion object {
        private const val THREAD_NAME = "RootlessZachEnhanced"
        private const val TRANSFER_BUFFER_BYTES = 256 * 1024
        private const val PAUSE_POLL_NANOS = 20_000_000L
    }
}
