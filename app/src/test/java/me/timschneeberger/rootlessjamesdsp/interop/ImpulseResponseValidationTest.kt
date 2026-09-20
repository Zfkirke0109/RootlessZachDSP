package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.*
import org.junit.Test

class ImpulseResponseValidationTest {
    @Test fun `zero frames and truncated channel data are rejected`() {
        assertFalse(ImpulseResponseValidation.valid(floatArrayOf(1f), 1, 0))
        assertFalse(ImpulseResponseValidation.valid(floatArrayOf(1f), 2, 1))
        assertFalse(ImpulseResponseValidation.valid(floatArrayOf(1f), Int.MAX_VALUE, Int.MAX_VALUE))
    }
    @Test fun `nonfinite samples cannot reach native convolution`() {
        assertFalse(ImpulseResponseValidation.valid(floatArrayOf(Float.NaN), 1, 1))
        assertFalse(ImpulseResponseValidation.valid(floatArrayOf(Float.POSITIVE_INFINITY), 1, 1))
        assertTrue(ImpulseResponseValidation.valid(floatArrayOf(1f, 0f, 0.5f, 0f), 2, 2))
    }
}
