package me.timschneeberger.rootlessjamesdsp.player.codec.wavpack

internal object NativeWavPackBridge {
    external fun nativeIsSeekable(fd: Int): Boolean
    external fun nativeOpen(sourceFd: Int, correctionFd: Int): Long
    external fun nativeLastError(): String
    external fun nativeTechnicalInfo(handle: Long): LongArray?
    external fun nativeChannelIdentities(handle: Long): ByteArray?
    external fun nativeChannelReordering(handle: Long): ByteArray?
    external fun nativeStoredMd5(handle: Long): ByteArray?
    external fun nativeTextTagPairs(handle: Long): Array<String>
    external fun nativeBinaryTagNames(handle: Long): Array<String>
    external fun nativeBinaryTagSize(handle: Long, index: Int): Int
    external fun nativeReadBinaryTag(handle: Long, index: Int, maximumBytes: Int): ByteArray?
    external fun nativeReadFrames(handle: Long, output: IntArray, requestedFrames: Int): Int
    external fun nativeSeekToFrame(handle: Long, frame: Long): Boolean
    external fun nativeDecodeErrorCount(handle: Long): Int
    external fun nativeClose(handle: Long)

    init {
        System.loadLibrary("wavpack-decoder")
    }
}
