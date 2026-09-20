package me.timschneeberger.rootlessjamesdsp

import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.utils.storage.Cache
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CacheLifecycleTest {
    @Test fun cleanupFinishesBeforeLoggingAndPreservesOtherOwners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(context.cacheDir, "rootlesszach-codec-active-test.wv").apply { writeText("active") }
        val temporary = Cache.getTemporaryFile(context).apply { writeText("old") }
        try {
            Cache.cleanupOwnedNow(context)
            assertFalse("cleanup must be finished on return", temporary.exists())
            assertTrue("codec owns its active inputs", staged.exists())
            val log = File(context.cacheDir, "cleanup-order-test.log").apply { writeText("ready") }
            try { assertEquals("ready", log.readText()) } finally { log.delete() }
        } finally { staged.delete(); temporary.delete() }
    }
}
