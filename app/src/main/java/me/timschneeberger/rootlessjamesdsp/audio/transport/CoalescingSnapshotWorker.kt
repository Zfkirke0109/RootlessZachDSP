
package me.timschneeberger.rootlessjamesdsp.audio.transport

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * A single bounded handoff from a real-time producer to a lower-priority publisher.
 *
 * The producer only sets an atomic flag and unparks an already-created worker thread. Repeated
 * requests collapse into one pending publication, so an event storm cannot create an unbounded
 * executor queue.
 */
class CoalescingSnapshotWorker<T>(
    name: String,
    private val snapshotProvider: () -> T,
    private val publisher: (T) -> Unit,
    private val errorHandler: (Throwable) -> Unit = {},
) : Closeable {
    private val publishRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val worker = Thread(::runLoop, name).apply {
        isDaemon = true
        priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
        start()
    }

    fun requestPublish() {
        if (closed.get()) return
        publishRequested.set(true)
        LockSupport.unpark(worker)
    }

    /**
     * Requests one final snapshot and waits briefly for the worker to release references.
     *
     * @return true when the worker terminated within [timeoutMillis].
     */
    fun flushAndClose(timeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis >= 0L) { "timeoutMillis must not be negative" }
        if (closed.compareAndSet(false, true)) {
            publishRequested.set(true)
            LockSupport.unpark(worker)
        }
        worker.join(timeoutMillis)
        return !worker.isAlive
    }

    override fun close() {
        flushAndClose()
    }

    private fun runLoop() {
        while (!closed.get() || publishRequested.get()) {
            if (publishRequested.getAndSet(false)) {
                runCatching { publisher(snapshotProvider()) }
                    .onFailure { error ->
                        runCatching { errorHandler(error) }
                    }
            }
            if (!closed.get() && !publishRequested.get()) {
                LockSupport.parkNanos(IDLE_PARK_NANOS)
            }
        }
    }

    companion object {
        private const val IDLE_PARK_NANOS = 50_000_000L
        private const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
