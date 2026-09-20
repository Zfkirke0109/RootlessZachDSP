package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.*
import me.timschneeberger.rootlessjamesdsp.session.dump.DumpManager
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionPolicyInfoDump
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionManager


class RootlessSessionManager(context: Context) : BaseSessionManager(context)
{
    // Session database
    val sessionDatabase: RootlessSessionDatabase = RootlessSessionDatabase(context)
    // Session policy database
    val sessionPolicyDatabase: SessionRecordingPolicyManager = SessionRecordingPolicyManager(context)

    override fun destroy()
    {
        super.destroy()
        sessionDatabase.destroy()
        sessionPolicyDatabase.destroy()
    }

    override fun collectSnapshot(): PollSnapshot {
        val sessions = dumpManager.dumpSessions()
        val policies = sessions as? ISessionPolicyInfoDump ?: dumpManager.dumpCaptureAllowlistLog()
        return PollSnapshot(sessions, policies)
    }

    override fun applySnapshot(snapshot: PollSnapshot) {
        snapshot.policies?.let { sessionPolicyDatabase.update(it) }
        handleSessionDump(snapshot.sessions)
    }

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        // An unavailable query cannot prove that an existing session disappeared.
        sessionDump?.let { sessionDatabase.update(it) }
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        sessionDatabase.clearSessions()
        sessionPolicyDatabase.clearSessions()
        super.onDumpMethodChange(method)
    }
}