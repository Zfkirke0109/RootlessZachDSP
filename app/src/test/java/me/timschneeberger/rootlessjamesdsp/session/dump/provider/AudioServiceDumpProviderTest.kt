package me.timschneeberger.rootlessjamesdsp.session.dump.provider

import me.timschneeberger.rootlessjamesdsp.session.dump.utils.AudioFlingerServiceDumpUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioServiceDumpProviderTest {
    @Test
    fun `pid lookup preserves every unique session id`() {
        val lookup = buildSidPidLookup(
            listOf(
                AudioFlingerServiceDumpUtils.Dataset(100, 200, 300),
                AudioFlingerServiceDumpUtils.Dataset(101, 200, 300),
                AudioFlingerServiceDumpUtils.Dataset(101, 200, 300),
                AudioFlingerServiceDumpUtils.Dataset(102, 201, 301),
            ),
        )

        assertEquals(setOf(100, 101), lookup[200])
        assertEquals(setOf(102), lookup[201])
    }

    @Test
    fun `fallback resolves only an unambiguous pid`() {
        val lookup = mapOf(
            200 to setOf(100, 101),
            201 to setOf(102),
        )

        assertNull(resolveFallbackSessionId(200, lookup))
        assertEquals(102, resolveFallbackSessionId(201, lookup))
        assertNull(resolveFallbackSessionId(999, lookup))
    }
}
