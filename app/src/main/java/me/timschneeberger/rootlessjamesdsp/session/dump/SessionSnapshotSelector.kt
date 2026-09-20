package me.timschneeberger.rootlessjamesdsp.session.dump

import kotlinx.coroutines.CancellationException
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionPolicyInfoDump
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump

/** No Android calls; keeps the last observable empty snapshot distinct from query failure. */
object SessionSnapshotSelector {
    data class Result(
        val dump: ISessionInfoDump?,
        val providersTried: Int,
        val failedProviders: Int,
        val usableSessions: Int,
        val policies: ISessionPolicyInfoDump?,
    )

    fun select(
        readers: List<() -> ISessionInfoDump?>,
        ownUid: Int,
        requireMedia: Boolean,
    ): Result {
        var observed: ISessionInfoDump? = null
        var failed = 0
        var policies: ISessionPolicyInfoDump? = null
        readers.forEachIndexed { index, read ->
            if (Thread.currentThread().isInterrupted) throw CancellationException("Session query cancelled")
            val dump = try { read() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (dump == null) {
                failed++
            } else {
                observed = dump
                if (dump is ISessionPolicyInfoDump) policies = dump
                val usable = dump.sessions.count { (sid, session) ->
                    sid > 0 && session.uid != ownUid &&
                        (!requireMedia || session.isUsageRecordable())
                }
                if (usable > 0) return Result(dump, index + 1, failed, usable, policies)
            }
        }
        return Result(observed, readers.size, failed, 0, policies)
    }
}
