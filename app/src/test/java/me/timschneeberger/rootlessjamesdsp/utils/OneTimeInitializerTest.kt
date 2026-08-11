package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OneTimeInitializerTest {
    @Test
    fun `action runs on first call only`() {
        val initializer = OneTimeInitializer()
        val runs = AtomicInteger()

        assertFalse(initializer.hasRun)
        repeat(5) { initializer.runOnce { runs.incrementAndGet() } }

        assertEquals(1, runs.get())
        assertTrue(initializer.hasRun)
    }

    @Test
    fun `concurrent callers run the action exactly once`() {
        val initializer = OneTimeInitializer()
        val runs = AtomicInteger()
        val threadCount = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                start.await()
                initializer.runOnce {
                    // Widen the window a losing thread could slip through.
                    Thread.sleep(5)
                    runs.incrementAndGet()
                }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failed action is retried by the next caller`() {
        val initializer = OneTimeInitializer()
        val attempts = AtomicInteger()

        repeat(2) {
            runCatching {
                initializer.runOnce {
                    attempts.incrementAndGet()
                    throw IllegalStateException("system service not ready")
                }
            }
        }
        assertEquals(2, attempts.get())
        assertFalse(initializer.hasRun)

        // Once it succeeds it latches, and stops running.
        initializer.runOnce { attempts.incrementAndGet() }
        initializer.runOnce { attempts.incrementAndGet() }

        assertEquals(3, attempts.get())
        assertTrue(initializer.hasRun)
    }
}
