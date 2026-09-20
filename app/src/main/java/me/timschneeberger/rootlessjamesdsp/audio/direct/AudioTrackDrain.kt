package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioTrack
import java.util.concurrent.locks.LockSupport

object AudioTrackDrain {
    fun awaitPlaybackHead(
        track: AudioTrack,
        writtenFrames: Long,
        stopRequested: () -> Boolean,
    ): Boolean {
        if (writtenFrames <= 0) return true
        val bufferNanos = if (track.sampleRate > 0) {
            (track.bufferSizeInFrames.toLong() * NANOS_PER_SECOND) / track.sampleRate
        } else {
            0L
        }
        val deadline = System.nanoTime() + maxOf(MINIMUM_TIMEOUT_NANOS, bufferNanos * 4)
        while (!stopRequested()) {
            val head = track.playbackHeadPosition.toLong() and UINT32_MASK
            if (hasReachedTarget(head, writtenFrames and UINT32_MASK)) return true
            if (System.nanoTime() >= deadline) return false
            LockSupport.parkNanos(POLL_NANOS)
            if (Thread.interrupted()) return false
        }
        return false
    }

    /** Correct across AudioTrack's unsigned 32-bit playback-head wrap. */
    fun hasReachedTarget(headModulo: Long, targetModulo: Long): Boolean {
        val remaining = (targetModulo - headModulo) and UINT32_MASK
        return remaining == 0L || remaining > INT32_MAX
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MINIMUM_TIMEOUT_NANOS = 2_000_000_000L
    private const val POLL_NANOS = 5_000_000L
    private const val UINT32_MASK = 0xffff_ffffL
    private const val INT32_MAX = 0x7fff_ffffL
}
