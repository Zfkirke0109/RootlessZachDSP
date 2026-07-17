
package me.timschneeberger.rootlessjamesdsp.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoalescingSnapshotWorkerTest {
    @Test
    fun `event storm is coalesced and worker releases on close`() {
        val providerCalls = AtomicInteger()
        val published = AtomicInteger()
        val firstPublication = CountDownLatch(1)
        val worker = CoalescingSnapshotWorker(
            name = "coalescing-test",
            snapshotProvider = { providerCalls.incrementAndGet() },
            publisher = {
                published.incrementAndGet()
                firstPublication.countDown()
                Thread.sleep(5)
            },
        )

        repeat(10_000) { worker.requestPublish() }

        assertTrue(firstPublication.await(2, TimeUnit.SECONDS))
        assertTrue(worker.flushAndClose(2_000))
        assertTrue(published.get() >= 1)
        assertTrue(published.get() < 10_000)
        assertEquals(providerCalls.get(), published.get())
    }

    @Test
    fun `close flushes a final snapshot even without an earlier request`() {
        val published = AtomicInteger()
        val worker = CoalescingSnapshotWorker(
            name = "coalescing-final-flush-test",
            snapshotProvider = { 1 },
            publisher = { published.incrementAndGet() },
        )

        assertTrue(worker.flushAndClose(2_000))
        assertEquals(1, published.get())

        worker.requestPublish()
        Thread.sleep(25)
        assertEquals(1, published.get())
    }
}
