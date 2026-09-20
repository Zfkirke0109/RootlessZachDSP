package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PcmContainer(val bitDepth: Int, val bytesPerSample: Int) {
    UNSIGNED_8(8, 1),
    SIGNED_16(16, 2),
    SIGNED_24_PACKED(24, 3),
    SIGNED_32(32, 4),
    ;

    val androidEncoding: Int
        get() = when (this) {
            UNSIGNED_8 -> AudioFormat.ENCODING_PCM_8BIT
            SIGNED_16 -> AudioFormat.ENCODING_PCM_16BIT
            SIGNED_24_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            SIGNED_32 -> AudioFormat.ENCODING_PCM_32BIT
        }

    companion object {
        fun forNativeBitDepth(bitDepth: Int): PcmContainer = when (bitDepth) {
            in 4..8 -> UNSIGNED_8
            in 9..16 -> SIGNED_16
            in 17..24 -> SIGNED_24_PACKED
            in 25..32 -> SIGNED_32
            else -> throw IllegalArgumentException("Unsupported PCM bit depth: $bitDepth")
        }
    }
}

object LeftAlignedPcmPacker {
    fun pack(
        samples: IntArray,
        sampleCount: Int,
        container: PcmContainer,
        output: ByteBuffer,
    ): Int {
        require(sampleCount in 0..samples.size)
        val requiredBytes = sampleCount * container.bytesPerSample
        require(output.capacity() >= requiredBytes)
        output.clear()
        output.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            val sample = samples[index]
            when (container) {
                PcmContainer.UNSIGNED_8 -> output.put(((sample ushr 24) + 128).toByte())
                PcmContainer.SIGNED_16 -> output.putShort((sample shr 16).toShort())
                PcmContainer.SIGNED_24_PACKED -> {
                    val value = sample shr 8
                    output.put(value.toByte())
                    output.put((value shr 8).toByte())
                    output.put((value shr 16).toByte())
                }
                PcmContainer.SIGNED_32 -> output.putInt(sample)
            }
        }
        output.flip()
        return requiredBytes
    }
}
