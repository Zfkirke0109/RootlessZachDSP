package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Streams already-decoded PCM from a local document into the exact AudioTrack configured by
 * [AndroidUsbBitPerfectController]. No DSP, fades, resampling, gain ramps, or software volume are
 * inserted. The engine separately reports when the playing AudioTrack identifies the selected USB
 * device as its routed device; that app-observable evidence is not physical-output verification.
 */
class DirectPcmPlaybackEngine(
    context: Context,
    private val uri: Uri,
    private val expectedFormat: DirectPcmFormat,
    private val session: AndroidUsbBitPerfectController.Session,
    private val eventListener: (Event) -> Unit,
) : Closeable {
    sealed interface Event {
        data object Started : Event
        data object RoutedDeviceConfirmed : Event
        data object Paused : Event
        data object Resumed : Event
        data class Completed(val routedDeviceConfirmed: Boolean) : Event
        data class Failed(val reason: String) : Event
        data object Stopped : Event
    }

    private val appContext = context.applicationContext
    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(worker == null) { "Direct PCM engine can only be started once" }
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
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = findExactPcmTrack(extractor)
            if (trackIndex < 0) {
                eventListener(Event.Failed("The selected document no longer exposes the expected PCM format"))
                return
            }
            extractor.selectTrack(trackIndex)

            val transferBuffer = ByteBuffer.allocateDirect(TRANSFER_BUFFER_BYTES)
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            session.audioTrack.play()
            eventListener(Event.Started)

            var reportedPause = false
            var routedDeviceConfirmed = false
            var writtenBytes = 0L
            val frameSizeBytes = expectedFormat.channelCount * expectedFormat.bytesPerSample()
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

                transferBuffer.clear()
                val sampleBytes = extractor.readSampleData(transferBuffer, 0)
                if (sampleBytes < 0) {
                    val drained = AudioTrackDrain.awaitPlaybackHead(
                        session.audioTrack,
                        writtenBytes / frameSizeBytes,
                        stopRequested::get,
                    )
                    if (drained) {
                        eventListener(Event.Completed(routedDeviceConfirmed))
                    } else if (stopRequested.get()) {
                        eventListener(Event.Stopped)
                    } else {
                        eventListener(Event.Failed("Timed out while draining the USB AudioTrack"))
                    }
                    return
                }
                transferBuffer.position(0)
                transferBuffer.limit(sampleBytes)

                var remaining = sampleBytes
                while (remaining > 0 && !stopRequested.get()) {
                    val written = session.audioTrack.write(
                        transferBuffer,
                        remaining,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) {
                        eventListener(
                            if (stopRequested.get()) {
                                Event.Stopped
                            } else {
                                Event.Failed("AudioTrack write failed with code $written")
                            },
                        )
                        return
                    }
                    remaining -= written
                    writtenBytes += written

                    val routedDeviceResult = runCatching { session.audioTrack.routedDevice }
                    if (routedDeviceResult.isFailure) {
                        eventListener(
                            Event.Failed(
                                "AudioTrack routed-device query failed: " +
                                    routedDeviceResult.exceptionOrNull()?.javaClass?.simpleName,
                            ),
                        )
                        return
                    }
                    val routedDevice = routedDeviceResult.getOrNull()
                    when (DirectRouteEvidence.classify(session.device.id, routedDevice?.id)) {
                        DirectRouteObservation.PENDING -> {
                            if (routedDeviceConfirmed) {
                                eventListener(
                                    Event.Failed(
                                        "AudioTrack no longer reports the selected USB route",
                                    ),
                                )
                                return
                            }
                        }
                        DirectRouteObservation.SELECTED_USB_CONFIRMED -> {
                            if (!routedDeviceConfirmed) {
                                routedDeviceConfirmed = true
                                eventListener(Event.RoutedDeviceConfirmed)
                            }
                        }
                        DirectRouteObservation.DIFFERENT_DEVICE -> {
                            eventListener(
                                Event.Failed(
                                    "AudioTrack routed away from the selected USB output",
                                ),
                            )
                            return
                        }
                    }
                }
                if (!extractor.advance() && remaining == 0) {
                    val drained = AudioTrackDrain.awaitPlaybackHead(
                        session.audioTrack,
                        writtenBytes / frameSizeBytes,
                        stopRequested::get,
                    )
                    if (drained) {
                        eventListener(Event.Completed(routedDeviceConfirmed))
                    } else if (stopRequested.get()) {
                        eventListener(Event.Stopped)
                    } else {
                        eventListener(Event.Failed("Timed out while draining the USB AudioTrack"))
                    }
                    return
                }
            }
            eventListener(Event.Stopped)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            eventListener(Event.Stopped)
        } catch (error: Exception) {
            eventListener(Event.Failed(error.message ?: error.javaClass.simpleName))
        } finally {
            extractor.release()
            closeResources()
        }
    }

    private fun findExactPcmTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME) != "audio/raw") continue
            val sampleRate = format.positiveInt(MediaFormat.KEY_SAMPLE_RATE) ?: continue
            val channelCount = format.positiveInt(MediaFormat.KEY_CHANNEL_COUNT) ?: continue
            val encoding = format.positiveInt(MediaFormat.KEY_PCM_ENCODING)
                ?: inferEncoding(format.positiveInt("bits-per-sample"))
            val channelMask = when (channelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> continue
            }
            val actual = DirectPcmFormat(sampleRate, channelCount, encoding, channelMask)
            if (actual.exactlyMatches(expectedFormat)) return index
        }
        return -1
    }

    private fun inferEncoding(bitDepth: Int?): Int = when (bitDepth) {
        8 -> AudioFormat.ENCODING_PCM_8BIT
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_32BIT
        else -> AudioFormat.ENCODING_INVALID
    }

    private fun DirectPcmFormat.bytesPerSample(): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> 4
        else -> error("Unsupported exact PCM encoding: $encoding")
    }

    private fun MediaFormat.positiveInt(key: String): Int? =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        runCatching {
            if (session.audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
                session.audioTrack.stop()
            }
        }
        // Session is only returned by AndroidUsbBitPerfectController on API 34+, but keep the
        // platform boundary explicit so cleanup remains lint- and runtime-safe on the app's API 29
        // minimum.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            session.close()
        }
    }

    companion object {
        private const val THREAD_NAME = "RootlessZachDirectPcm"
        private const val TRANSFER_BUFFER_BYTES = 256 * 1024
        private const val PAUSE_POLL_NANOS = 20_000_000L
    }
}
