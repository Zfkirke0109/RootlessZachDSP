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
 * Streams already-decoded PCM from a local document into the exact AudioTrack verified by
 * [AndroidUsbBitPerfectController]. No DSP, fades, resampling, gain ramps, or software volume are
 * inserted.
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
        data object Paused : Event
        data object Resumed : Event
        data object Completed : Event
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
        worker?.let {
            LockSupport.unpark(it)
            it.interrupt()
            if (Thread.currentThread() !== it) {
                runCatching { it.join(CLOSE_JOIN_TIMEOUT_MILLIS) }
            }
        }
        closeResources()
    }

    private fun runPlayback() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = findExactPcmTrack(extractor)
            if (trackIndex < 0) {
                eventListener(Event.Failed("The selected document no longer exposes the verified PCM format"))
                return
            }
            extractor.selectTrack(trackIndex)

            val transferBuffer = ByteBuffer.allocateDirect(TRANSFER_BUFFER_BYTES)
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            session.audioTrack.setVolume(1.0f)
            session.audioTrack.play()
            eventListener(Event.Started)

            var reportedPause = false
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
                    eventListener(Event.Completed)
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
                        eventListener(Event.Failed("AudioTrack write failed with code $written"))
                        return
                    }
                    remaining -= written
                }
                if (!extractor.advance() && remaining == 0) {
                    eventListener(Event.Completed)
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
                ?: inferEncoding(format.positiveInt(MediaFormat.METADATA_KEY_BITS_PER_SAMPLE))
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
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 1_500L
    }
}
