package me.timschneeberger.rootlessjamesdsp.session.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUidPolicyTest {
    @Test
    fun exclusionMode_acceptsEveryUidExceptExcluded() {
        val policy = SessionUidPolicy(excludedUids = setOf(1001, 1002))

        assertTrue(policy.accepts(1000))
        assertFalse(policy.accepts(1001))
    }

    @Test
    fun allowMode_acceptsOnlySelectedAndNonExcludedUids() {
        val policy = SessionUidPolicy(
            excludedUids = setOf(1002),
            allowedUids = setOf(1001, 1002),
        )

        assertTrue(policy.accepts(1001))
        assertFalse(policy.accepts(1002))
        assertFalse(policy.accepts(1003))
    }

    @Test
    fun emptyAllowlist_admitsNoSessions() {
        val policy = SessionUidPolicy(allowedUids = emptySet())

        assertFalse(policy.accepts(1000))
        assertFalse(policy.accepts(1001))
    }
}
