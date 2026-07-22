package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioTrack
import android.os.Build
import android.os.Process
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmSource
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Streams native-decoder PCM into an Android 14+ bit-perfect mixer-contract session.
 *
 * This path never applies ReplayGain, app volume, fades, resampling, or JamesDSP. A routed-device
 * observation is emitted separately and is not described as physical bit-perfect verification.
 */
class DecodedLosslessPlaybackEngine(
    private val source: LosslessPcmSource,
    private val expectedFormat: DirectPcmFormat,
    private val session: AndroidUsbBitPerfectController.Session,
    private val eventListener: (Event) -> Unit,
) : Closeable {
    sealed interface Event {
        data object Started : Event
        data object Paused : Event
        data object Resumed : Event
        data object Completed : Event
        data object Stopped : Event
        data class RouteObserved(val selectedUsbDevice: Boolean, val description: String) : Event
        data class Failed(val reason: String) : Event
    }

    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(worker == null) { "Decoded lossless engine can only be started once" }
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
        runCatching { session.audioTrack.stop() }
        LockSupport.unpark(thread)
        thread.interrupt()
    }

    private fun runPlayback() {
        try {
            val metadata = source.metadata
            val resolved = DecodedPcmFormatResolver.resolve(metadata)
                ?: error("Decoded ${metadata.channelCount}-channel ${metadata.nativeBitDepth}-bit PCM is unsupported")
            check(resolved.exactlyMatches(expectedFormat)) {
                "Decoder output no longer matches the negotiated USB PCM format"
            }
            val container = PcmContainer.forNativeBitDepth(metadata.nativeBitDepth)
            val framesPerChunk = maxOf(1, TRANSFER_BUFFER_BYTES / (metadata.channelCount * container.bytesPerSample))
            val samples = IntArray(framesPerChunk * metadata.channelCount)
            val packed = ByteBuffer.allocateDirect(samples.size * container.bytesPerSample)

            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            session.audioTrack.setVolume(1.0f)
            session.audioTrack.play()
            eventListener(Event.Started)

            var reportedPause = false
            var routedDeviceConfirmed = false
            var writtenBytes = 0L
            val frameSizeBytes = metadata.channelCount * container.bytesPerSample
            val frameProgress = LosslessFrameProgress(metadata.totalFrames)
            while (!stopRequested.get()) {
                if (pauseRequested.get()) {
                    if (!reportedPause) {
                        runCatching { session.audioTrack.pause() }
                        eventListener(Event.Paused)
                        reportedPause = true
                    }
                    LockSupport.parkNanos(PAUSE_POLL_NANOS)
                    continue
                }
                if (reportedPause) {
                    session.audioTrack.play()
                    eventListener(Event.Resumed)
                    reportedPause = false
                }

                val frames = source.readFrames(samples, framesPerChunk)
                if (frames == 0) {
                    frameProgress.verifyEndOfStream()
                    val drained = AudioTrackDrain.awaitPlaybackHead(
                        session.audioTrack,
                        writtenBytes / frameSizeBytes,
                        stopRequested::get,
                    )
                    if (drained) {
                        eventListener(Event.Completed)
                    } else if (stopRequested.get()) {
                        eventListener(Event.Stopped)
                    } else {
                        eventListener(Event.Failed("Timed out while draining the USB AudioTrack"))
                    }
                    return
                }
                check(frames > 0) { "Lossless decoder returned an invalid frame count" }
                frameProgress.record(frames)
                val sampleCount = frames * metadata.channelCount
                var remaining = LeftAlignedPcmPacker.pack(samples, sampleCount, container, packed)
                while (remaining > 0 && !stopRequested.get()) {
                    val written = session.audioTrack.write(packed, remaining, AudioTrack.WRITE_BLOCKING)
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

                if (stopRequested.get()) {
                    eventListener(Event.Stopped)
                    return
                }

                val routed = runCatching { session.audioTrack.routedDevice }
                    .getOrElse { error("AudioTrack routed-device query failed") }
                when (DirectRouteEvidence.classify(session.device.id, routed?.id)) {
                    DirectRouteObservation.PENDING -> check(!routedDeviceConfirmed) {
                        "AudioTrack no longer reports the selected USB route"
                    }
                    DirectRouteObservation.SELECTED_USB_CONFIRMED -> {
                        if (!routedDeviceConfirmed) {
                            routedDeviceConfirmed = true
                            eventListener(
                                Event.RouteObserved(
                                    selectedUsbDevice = true,
                                    description = "AudioTrack reports the selected USB device; external sample verification is still pending",
                                ),
                            )
                        }
                    }
                    DirectRouteObservation.DIFFERENT_DEVICE ->
                        error("AudioTrack routed away from the selected USB output")
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
        // Session is only created on API 34+, but keep the runtime boundary explicit because the
        // app still supports Android 10 through 13 for non-bit-perfect playback paths.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { session.close() }
        }
    }

    companion object {
        private const val THREAD_NAME = "RootlessZachLossless"
        private const val TRANSFER_BUFFER_BYTES = 256 * 1024
        private const val PAUSE_POLL_NANOS = 20_000_000L
    }
}
