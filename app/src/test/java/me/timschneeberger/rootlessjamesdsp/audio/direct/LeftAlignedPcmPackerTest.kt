package me.timschneeberger.rootlessjamesdsp.audio.direct

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LeftAlignedPcmPackerTest {
    @Test
    fun `chooses a lossless android pcm container`() {
        assertEquals(PcmContainer.UNSIGNED_8, PcmContainer.forNativeBitDepth(8))
        assertEquals(PcmContainer.SIGNED_16, PcmContainer.forNativeBitDepth(12))
        assertEquals(PcmContainer.SIGNED_24_PACKED, PcmContainer.forNativeBitDepth(20))
        assertEquals(PcmContainer.SIGNED_24_PACKED, PcmContainer.forNativeBitDepth(24))
    }

    @Test
    fun `packs left aligned samples to 24 bit little endian without losing source bits`() {
        val output = ByteBuffer.allocate(6)
        val bytes = LeftAlignedPcmPacker.pack(
            intArrayOf(0x12345600, -0x12345700),
            2,
            PcmContainer.SIGNED_24_PACKED,
            output,
        )

        assertEquals(6, bytes)
        val actual = ByteArray(bytes)
        output.get(actual)
        assertArrayEquals(
            byteArrayOf(0x56, 0x34, 0x12, 0xA9.toByte(), 0xCB.toByte(), 0xED.toByte()),
            actual,
        )
    }

    @Test
    fun `converts signed flac eight bit samples to unsigned pcm`() {
        val output = ByteBuffer.allocate(3)
        LeftAlignedPcmPacker.pack(
            intArrayOf(Int.MIN_VALUE, 0, 0x7f000000),
            3,
            PcmContainer.UNSIGNED_8,
            output,
        )
        val actual = ByteArray(3)
        output.get(actual)
        assertArrayEquals(byteArrayOf(0, 0x80.toByte(), 0xff.toByte()), actual)
    }
}
