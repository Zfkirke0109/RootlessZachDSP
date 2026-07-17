package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.*
import org.junit.Test

class AudioTransfersTest {
    @Test fun `partial transfers retry until complete`() {
        val chunks = ArrayDeque(listOf(3, 2, 5))
        val result = AudioTransfers.transferFully(10) { _, _ -> chunks.removeFirst() }
        assertTrue(result.completed)
        assertEquals(10, result.transferredSamples)
        assertEquals(3, result.operationCount)
        assertEquals(2, result.partialOperationCount)
        assertNull(result.errorCode)
    }

    @Test fun `error preserves prior progress`() {
        val chunks = ArrayDeque(listOf(4, -6))
        val result = AudioTransfers.transferFully(10) { _, _ -> chunks.removeFirst() }
        assertFalse(result.completed)
        assertEquals(4, result.transferredSamples)
        assertEquals(-6, result.errorCode)
    }

    @Test fun `zero progress is bounded`() {
        val result = AudioTransfers.transferFully(10, maxZeroProgress = 2) { _, _ -> 0 }
        assertFalse(result.completed)
        assertEquals(2, result.zeroProgressCount)
        assertEquals(2, result.operationCount)
    }
}
