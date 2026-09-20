package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.*
import org.junit.Test

class AudioRampsTest {
    @Test fun `wet dry ramp reaches dry`() {
        val output = FloatArray(4)
        val mixer = WetDryCrossfader(1f)
        mixer.rampTo(0f, 4)
        mixer.mix(FloatArray(4), floatArrayOf(1f, 1f, 1f, 1f), output)
        assertEquals(0f, mixer.wet, 0.0001f)
        assertTrue(output[0] > output[3])
        assertEquals(0f, output[3], 0.0001f)
    }

    @Test fun `short gain ramp avoids overflow`() {
        val values = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE)
        val ramp = LinearRamp(0f)
        ramp.rampTo(1f, 2)
        ramp.applyInPlace(values)
        assertArrayEquals(shortArrayOf(16384.toShort(), Short.MAX_VALUE), values)
    }
}
