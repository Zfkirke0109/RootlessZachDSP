package me.timschneeberger.rootlessjamesdsp.player.codec.flac

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import me.timschneeberger.rootlessjamesdsp.player.codec.SeekableDocumentStager
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seekable FLAC decoder backed by the bundled dr_flac implementation.
 *
 * Samples are returned as signed, interleaved, left-aligned 32-bit PCM. ReplayGain is reported as
 * metadata only; applying it would make direct playback non-bit-perfect.
 */
class NativeFlacDecoder private constructor(
    private var nativeHandle: Long,
    private val stagedFile: File?,
    val metadata: FlacMetadata,
) : Closeable {
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun readFrames(output: IntArray, requestedFrames: Int): Int {
        check(!closed.get()) { "FLAC decoder is closed" }
        require(requestedFrames > 0) { "requestedFrames must be positive" }
        require(output.size / metadata.channelCount >= requestedFrames) {
            "Output buffer cannot hold $requestedFrames interleaved frames"
        }
        return NativeFlacBridge.nativeReadFrames(nativeHandle, output, requestedFrames)
            .also { check(it >= 0) { "Native FLAC read failed" } }
    }

    @Synchronized
    fun seekToFrame(frame: Long): Boolean {
        check(!closed.get()) { "FLAC decoder is closed" }
        require(frame >= 0) { "frame must not be negative" }
        return NativeFlacBridge.nativeSeekToFrame(nativeHandle, frame)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) NativeFlacBridge.nativeClose(handle)
        stagedFile?.delete()
    }

    companion object {
        fun open(context: Context, uri: Uri): Result<NativeFlacDecoder> = runCatching {
            val appContext = context.applicationContext
            SeekableDocumentStager.removeStaleFiles(appContext.cacheDir)
            val direct = appContext.contentResolver.openFileDescriptor(uri, "r")
                ?: error("The document provider did not expose a readable file descriptor")
            val directHandle = direct.use { descriptor ->
                if (!NativeFlacBridge.nativeIsSeekable(descriptor.fd)) {
                    null
                } else {
                    openDescriptor(descriptor).also { handle ->
                        check(handle != 0L) { "The selected seekable file is not a valid FLAC stream" }
                    }
                }
            }
            if (directHandle != null) return@runCatching create(directHandle, null)

            // Some document providers expose a non-seekable pipe. dr_flac requires seeking, so
            // stage only that case in the app cache and delete it when the decoder closes.
            val staged = SeekableDocumentStager.stage(appContext, uri, ".flac")
            try {
                val stagedHandle = ParcelFileDescriptor.open(
                    staged,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use(::openDescriptor)
                if (stagedHandle == 0L) error("The selected file is not a valid seekable FLAC stream")
                create(stagedHandle, staged)
            } catch (error: Throwable) {
                staged.delete()
                throw error
            }
        }

        private fun openDescriptor(descriptor: ParcelFileDescriptor): Long =
            NativeFlacBridge.nativeOpen(descriptor.fd)

        private fun create(handle: Long, stagedFile: File?): NativeFlacDecoder {
            try {
                val sampleRate = NativeFlacBridge.nativeSampleRate(handle)
                val channels = NativeFlacBridge.nativeChannels(handle)
                val bitDepth = NativeFlacBridge.nativeBitsPerSample(handle)
                check(sampleRate > 0 && channels in 1..8 && bitDepth in 4..24) {
                    "FLAC stream reports an unsupported PCM format"
                }
                val tags = FlacMetadataParser.parseTags(NativeFlacBridge.nativeTags(handle))
                val artworkBytes = NativeFlacBridge.nativeArtwork(handle)
                val artwork = artworkBytes?.let {
                    FlacArtwork(
                        mimeType = NativeFlacBridge.nativeArtworkMime(handle).ifBlank { null },
                        bytes = it,
                    )
                }
                val metadata = FlacMetadata(
                    sampleRate = sampleRate,
                    bitsPerSample = bitDepth,
                    channelCount = channels,
                    channelLayout = FlacMetadataParser.channelLayout(channels),
                    totalFrames = NativeFlacBridge.nativeTotalFrames(handle),
                    streamMd5 = NativeFlacBridge.nativeStreamMd5(handle).ifBlank { null },
                    tags = tags,
                    replayGain = FlacMetadataParser.replayGain(tags),
                    artwork = artwork,
                )
                return NativeFlacDecoder(handle, stagedFile, metadata)
            } catch (error: Throwable) {
                NativeFlacBridge.nativeClose(handle)
                stagedFile?.delete()
                throw error
            }
        }
    }
}
