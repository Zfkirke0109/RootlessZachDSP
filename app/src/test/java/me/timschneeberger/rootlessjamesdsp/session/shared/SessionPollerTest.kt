package me.timschneeberger.rootlessjamesdsp.session.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPollerTest {
    @Test fun `burst requests coalesce into at most one followup snapshot`() = runTest {
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val applied = mutableListOf<Int>()
        val poller = SessionPoller(this, StandardTestDispatcher(testScheduler), {
            reads++; if (reads == 1) gate.await(); reads
        }, { applied.add(it) })
        poller.request(); runCurrent()
        repeat(50) { poller.request() }
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(1, 2), applied)
        assertEquals(2, reads)
        poller.close()
    }

    @Test fun `close cancels pending results and prevents new work`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val applied = mutableListOf<Int>()
        val poller = SessionPoller(this, StandardTestDispatcher(testScheduler), {
            reads++; gate.await(); 1
        }, { applied.add(it) })
        poller.request(); runCurrent(); poller.close()
        gate.complete(Unit); poller.request(); advanceUntilIdle()
        assertTrue(applied.isEmpty()); assertEquals(1, reads)
    }

    @Test fun `method changes discard old snapshot and request fresh data`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val applied = mutableListOf<Int>()
        val poller = SessionPoller(this, StandardTestDispatcher(testScheduler), {
            reads++; if (reads == 1) gate.await(); reads
        }, { applied.add(it) })
        poller.request(); runCurrent(); poller.invalidate()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(2), applied)
        poller.close()
    }
}
