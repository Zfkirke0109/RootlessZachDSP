package me.timschneeberger.rootlessjamesdsp.player.codec.flac

internal object NativeFlacBridge {
    external fun nativeIsSeekable(fd: Int): Boolean
    external fun nativeOpen(fd: Int): Long
    external fun nativeSampleRate(handle: Long): Int
    external fun nativeChannels(handle: Long): Int
    external fun nativeBitsPerSample(handle: Long): Int
    external fun nativeTotalFrames(handle: Long): Long
    external fun nativeTags(handle: Long): Array<String>
    external fun nativeArtwork(handle: Long): ByteArray?
    external fun nativeArtworkMime(handle: Long): String
    external fun nativeStreamMd5(handle: Long): String
    external fun nativeReadFrames(handle: Long, output: IntArray, requestedFrames: Int): Int
    external fun nativeSeekToFrame(handle: Long, frame: Long): Boolean
    external fun nativeClose(handle: Long)

    init {
        System.loadLibrary("flac-decoder")
    }
}
