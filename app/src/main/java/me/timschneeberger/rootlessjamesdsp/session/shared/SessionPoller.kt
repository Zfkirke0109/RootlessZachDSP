package me.timschneeberger.rootlessjamesdsp.session.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the main dispatcher. Blocking provider work runs only on [worker]. */
internal class SessionPoller<T>(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val collect: suspend () -> T,
    private val apply: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var job: Job? = null
    private var requested = false
    private var closed = false
    private var generation = 0L

    fun request() {
        if (closed) return
        requested = true
        if (job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) {
            while (requested && !closed) {
                requested = false
                val expected = generation
                try {
                    val result = withContext(worker) { collect() }
                    if (!closed && expected == generation) apply(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!closed && expected == generation) onFailure(error)
                }
            }
        }.also { it.start() }
    }

    fun invalidate() {
        generation++
        request()
    }

    fun close() {
        closed = true
        requested = false
        generation++
        job?.cancel()
        job = null
    }
}
