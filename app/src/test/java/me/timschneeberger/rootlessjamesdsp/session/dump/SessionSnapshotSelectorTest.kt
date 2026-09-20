package me.timschneeberger.rootlessjamesdsp.session.dump

import me.timschneeberger.rootlessjamesdsp.session.dump.data.AudioPolicyServiceDump
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.session.dump.data.AudioServiceDump
import org.junit.Assert.*
import org.junit.Test

class SessionSnapshotSelectorTest {
    private fun entry(uid: Int, usage: String = "USAGE_MEDIA") =
        AudioSessionDumpEntry(uid, "redacted", usage, "CONTENT_TYPE_MUSIC")

    @Test fun `self zero and notification sessions do not suppress fallback`() {
        val first = AudioServiceDump(hashMapOf(0 to entry(20), 4 to entry(10),
            5 to entry(30, "USAGE_NOTIFICATION_EVENT")))
        val second = AudioServiceDump(hashMapOf(8 to entry(40)))
        val result = SessionSnapshotSelector.select(listOf({ first }, { second }), 10, true)
        assertSame(second, result.dump)
        assertEquals(2, result.providersTried)
        assertEquals(1, result.usableSessions)
    }

    @Test fun `provider failure does not discard a later valid snapshot`() {
        val valid = AudioServiceDump(hashMapOf(9 to entry(40)))
        val result = SessionSnapshotSelector.select(listOf({ error("denied") }, { valid }), 10, true)
        assertSame(valid, result.dump)
        assertEquals(1, result.failedProviders)
    }

    @Test fun `successful empty snapshot differs from unavailable query`() {
        val empty = SessionSnapshotSelector.select(listOf({ AudioServiceDump(hashMapOf()) }), 10, true)
        assertNotNull(empty.dump)
        assertEquals(0, empty.usableSessions)
        assertNull(SessionSnapshotSelector.select(listOf({ null }), 10, true).dump)
    }

    @Test fun `first usable provider is sufficient and root keeps nonmedia sessions`() {
        val notification = AudioServiceDump(hashMapOf(9 to entry(40, "USAGE_NOTIFICATION_EVENT")))
        val result = SessionSnapshotSelector.select(listOf({ notification }, { error("must not read") }), 10, false)
        assertSame(notification, result.dump)
        assertEquals(1, result.providersTried)
    }
    @Test fun `fallback preserves policy evidence from an earlier provider`() {
        val policies = AudioPolicyServiceDump(hashMapOf(0 to entry(10)), hashMapOf("example.player" to false))
        val sessions = AudioServiceDump(hashMapOf(9 to entry(40)))
        val result = SessionSnapshotSelector.select(listOf({ policies }, { sessions }), 10, true)
        assertSame(sessions, result.dump)
        assertSame(policies, result.policies)
    }
}
