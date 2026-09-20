package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import me.timschneeberger.rootlessjamesdsp.player.codec.SeekableDocumentStager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seekable PCM WavPack decoder backed by the official WavPack 5.9.0 library.
 *
 * [readFrames] returns the unmodified interleaved 32-bit words produced by
 * `WavpackUnpackSamples()`. Integer streams use signed integer magnitudes in the original bit
 * depth; floating-point streams use IEEE-754 bit patterns. ReplayGain is metadata only and is
 * never applied by this class.
 *
 * A hybrid `.wv` is truthfully reported as [WavPackEncodingMode.HYBRID_LOSSY] unless the
 * library actually opened and used the supplied `.wvc` correction stream.
 */
class NativeWavPackDecoder private constructor(
    private var nativeHandle: Long,
    private val stagedFiles: List<File>,
    val metadata: WavPackMetadata,
) : Closeable {
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun readFrames(output: IntArray, requestedFrames: Int): Int {
        checkOpen()
        require(requestedFrames > 0) { "requestedFrames must be positive" }
        require(requestedFrames <= Int.MAX_VALUE / metadata.channelCount) {
            "requestedFrames is too large for the channel count"
        }
        require(output.size >= requestedFrames * metadata.channelCount) {
            "Output buffer cannot hold $requestedFrames interleaved WavPack frames"
        }
        val decoded = NativeWavPackBridge.nativeReadFrames(nativeHandle, output, requestedFrames)
        if (decoded < 0) throw IOException(nativeError("WavPack decode failed"))
        return decoded
    }

    @Synchronized
    fun seekToFrame(frame: Long) {
        checkOpen()
        require(frame >= 0) { "frame must not be negative" }
        if (!NativeWavPackBridge.nativeSeekToFrame(nativeHandle, frame)) {
            throw IOException(nativeError("WavPack seek failed"))
        }
    }

    /** Reads an APEv2 binary value without interpreting or modifying it. */
    @Synchronized
    fun readBinaryTag(name: String, maximumBytes: Int = MAX_BINARY_TAG_BYTES): ByteArray {
        checkOpen()
        require(maximumBytes in 1..MAX_BINARY_TAG_BYTES) {
            "maximumBytes must be between 1 and $MAX_BINARY_TAG_BYTES"
        }
        val index = metadata.binaryTagNames.indexOf(name)
        require(index >= 0) { "No WavPack binary tag named $name" }
        val size = NativeWavPackBridge.nativeBinaryTagSize(nativeHandle, index)
        if (size < 0) throw IOException("WavPack binary tag metadata could not be read")
        if (size > maximumBytes) {
            throw IOException("WavPack binary tag is $size bytes, above the $maximumBytes-byte limit")
        }
        return NativeWavPackBridge.nativeReadBinaryTag(nativeHandle, index, maximumBytes)
            ?: throw IOException(nativeError("WavPack binary tag could not be read"))
    }

    @get:Synchronized
    val decodeErrorCount: Int
        get() {
            checkOpen()
            return NativeWavPackBridge.nativeDecodeErrorCount(nativeHandle)
                .also { check(it >= 0) { "Native WavPack decoder did not report an error count" } }
        }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) NativeWavPackBridge.nativeClose(handle)
        stagedFiles.forEach(File::delete)
    }

    private fun checkOpen() {
        check(!closed.get() && nativeHandle != 0L) { "WavPack decoder is closed" }
    }

    private fun nativeError(fallback: String): String =
        NativeWavPackBridge.nativeLastError().ifBlank { fallback }

    companion object {
        const val MAX_BINARY_TAG_BYTES = 32 * 1024 * 1024
        private const val MAX_AUTOMATIC_ARTWORK_ITEMS = 1
        private const val MAX_AUTOMATIC_ARTWORK_BYTES = 4 * 1024 * 1024
        private const val TECHNICAL_INFO_FIELDS = 11

        /**
         * Opens Storage Access Framework documents. Non-seekable provider streams are copied to
         * app cache because WavPack tags, correction files, and seeking require random access.
         */
        fun open(
            context: Context,
            sourceUri: Uri,
            correctionUri: Uri? = null,
        ): Result<NativeWavPackDecoder> = runCatching {
            val appContext = context.applicationContext
            SeekableDocumentStager.removeStaleFiles(appContext.cacheDir)
            tryOpenDirect(appContext, sourceUri, correctionUri)?.let { handle ->
                return@runCatching create(
                    handle = handle,
                    stagedFiles = emptyList(),
                    correctionFileProvided = correctionUri != null,
                )
            }

            val staged = mutableListOf<File>()
            try {
                val sourceFile = stageDocument(appContext, sourceUri, ".wv").also(staged::add)
                val correctionFile = correctionUri
                    ?.let { stageDocument(appContext, it, ".wvc") }
                    ?.also(staged::add)
                val handle = openFiles(sourceFile, correctionFile)
                create(handle, staged.toList(), correctionUri != null)
            } catch (error: Throwable) {
                staged.forEach(File::delete)
                throw error
            }
        }

        /** The descriptors remain owned by the caller; native code duplicates them immediately. */
        fun open(
            source: ParcelFileDescriptor,
            correction: ParcelFileDescriptor? = null,
        ): Result<NativeWavPackDecoder> = runCatching {
            require(NativeWavPackBridge.nativeIsSeekable(source.fd)) {
                "WavPack source descriptor is not seekable"
            }
            require(correction == null || NativeWavPackBridge.nativeIsSeekable(correction.fd)) {
                "WavPack correction descriptor is not seekable"
            }
            create(
                handle = openNative(source.fd, correction?.fd ?: -1),
                stagedFiles = emptyList(),
                correctionFileProvided = correction != null,
            )
        }

        private fun tryOpenDirect(
            context: Context,
            sourceUri: Uri,
            correctionUri: Uri?,
        ): Long? {
            val source = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return null
            source.use { sourceDescriptor ->
                if (!NativeWavPackBridge.nativeIsSeekable(sourceDescriptor.fd)) return null
                if (correctionUri == null) return openNative(sourceDescriptor.fd, -1)

                val correction = context.contentResolver.openFileDescriptor(correctionUri, "r")
                    ?: return null
                correction.use { correctionDescriptor ->
                    if (!NativeWavPackBridge.nativeIsSeekable(correctionDescriptor.fd)) return null
                    return openNative(sourceDescriptor.fd, correctionDescriptor.fd)
                }
            }
        }

        private fun stageDocument(context: Context, uri: Uri, suffix: String): File {
            return SeekableDocumentStager.stage(context, uri, suffix)
        }

        private fun openFiles(source: File, correction: File?): Long {
            val sourceDescriptor = ParcelFileDescriptor.open(
                source,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            sourceDescriptor.use { sourcePfd ->
                if (correction == null) return openNative(sourcePfd.fd, -1)
                val correctionDescriptor = ParcelFileDescriptor.open(
                    correction,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                correctionDescriptor.use { correctionPfd ->
                    return openNative(sourcePfd.fd, correctionPfd.fd)
                }
            }
        }

        private fun openNative(sourceFd: Int, correctionFd: Int): Long {
            val handle = NativeWavPackBridge.nativeOpen(sourceFd, correctionFd)
            if (handle == 0L) {
                val detail = NativeWavPackBridge.nativeLastError()
                throw IOException(detail.ifBlank { "The selected document is not a valid WavPack stream" })
            }
            return handle
        }

        private fun create(
            handle: Long,
            stagedFiles: List<File>,
            correctionFileProvided: Boolean,
        ): NativeWavPackDecoder {
            try {
                val technical = NativeWavPackBridge.nativeTechnicalInfo(handle)
                    ?: error("Native WavPack metadata is unavailable")
                check(technical.size == TECHNICAL_INFO_FIELDS) {
                    "Native WavPack metadata has an unexpected shape"
                }
                val sampleRate = technical[0].checkedInt("sample rate")
                val nativeSampleRate = technical[1].checkedInt("native sample rate")
                val bitsPerSample = technical[2].checkedInt("bit depth")
                val bytesPerSample = technical[3].checkedInt("byte depth")
                val channelCount = technical[4].checkedInt("channel count")
                check(sampleRate > 0 && nativeSampleRate > 0 && bitsPerSample in 1..32 &&
                    bytesPerSample in 1..4 && channelCount in 1..256) {
                    "WavPack stream reports an unsupported PCM format"
                }

                val modeFlags = technical[7].checkedInt("mode flags")
                val binaryTagNames = NativeWavPackBridge.nativeBinaryTagNames(handle).toList()
                val artwork = readArtwork(handle, binaryTagNames)
                val tags = WavPackMetadataParser.parseTextTagPairs(
                    NativeWavPackBridge.nativeTextTagPairs(handle),
                )
                val metadata = WavPackMetadata(
                    sampleRate = sampleRate,
                    nativeSampleRate = nativeSampleRate,
                    bitsPerSample = bitsPerSample,
                    bytesPerSample = bytesPerSample,
                    channelCount = channelCount,
                    channelMask = technical[5],
                    channelIdentities = NativeWavPackBridge.nativeChannelIdentities(handle)
                        ?.map { it.toInt() and 0xff }
                        .orEmpty(),
                    channelLayoutTag = technical[9],
                    channelReordering = NativeWavPackBridge.nativeChannelReordering(handle)
                        ?.map { it.toInt() and 0xff }
                        .orEmpty(),
                    totalFrames = technical[6],
                    sampleRepresentation = if (modeFlags and WavPackModeFlags.FLOAT != 0) {
                        WavPackSampleRepresentation.IEEE_FLOAT32_BITS_IN_INT32
                    } else {
                        WavPackSampleRepresentation.SIGNED_INTEGER_IN_INT32
                    },
                    encoding = WavPackTruthModel.assess(modeFlags, correctionFileProvided),
                    sourceContainer = WavPackMetadataParser.sourceContainer(
                        technical[10].checkedInt("source container"),
                    ),
                    storedMd5 = WavPackMetadataParser.bytesToHex(
                        NativeWavPackBridge.nativeStoredMd5(handle),
                    ),
                    tags = tags,
                    replayGain = WavPackMetadataParser.replayGain(tags),
                    binaryTagNames = binaryTagNames,
                    artwork = artwork,
                )
                return NativeWavPackDecoder(handle, stagedFiles, metadata)
            } catch (error: Throwable) {
                NativeWavPackBridge.nativeClose(handle)
                stagedFiles.forEach(File::delete)
                throw error
            }
        }

        private fun readArtwork(handle: Long, names: List<String>): List<WavPackArtwork> {
            val artwork = mutableListOf<WavPackArtwork>()
            var remainingBytes = MAX_AUTOMATIC_ARTWORK_BYTES
            names.forEachIndexed { index, name ->
                if (artwork.size >= MAX_AUTOMATIC_ARTWORK_ITEMS || remainingBytes <= 0 ||
                    !WavPackMetadataParser.isArtworkTag(name)) {
                    return@forEachIndexed
                }
                val size = NativeWavPackBridge.nativeBinaryTagSize(handle, index)
                if (size <= 0 || size > remainingBytes || size > MAX_BINARY_TAG_BYTES) {
                    return@forEachIndexed
                }
                val raw = NativeWavPackBridge.nativeReadBinaryTag(handle, index, size)
                    ?: return@forEachIndexed
                WavPackMetadataParser.parseArtwork(name, raw)?.let(artwork::add)
                remainingBytes -= size
            }
            return artwork
        }

        private fun Long.checkedInt(label: String): Int {
            check(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "WavPack $label is outside the supported integer range"
            }
            return toInt()
        }
    }
}
