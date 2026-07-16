package me.timschneeberger.rootlessjamesdsp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RotatingJsonlStoreTest {
    @Test
    fun `store rotates before exceeding the active file budget`() {
        val directory = Files.createTempDirectory("rzdsp-jsonl").toFile()
        try {
            val store = RotatingJsonlStore(
                directory = directory,
                maximumActiveBytes = 16,
                maximumLineBytes = 64,
            )

            store.appendLine("{\"a\":1}")
            store.appendLine("{\"b\":2}")
            store.appendLine("{\"c\":3}")

            assertTrue(store.rotatedFile().exists())
            assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), store.rotatedFile().readLines())
            assertEquals(listOf("{\"c\":3}"), store.activeFile().readLines())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `store normalizes embedded newlines and returns recent records`() {
        val directory = Files.createTempDirectory("rzdsp-jsonl").toFile()
        try {
            val store = RotatingJsonlStore(directory, maximumActiveBytes = 1_024)
            store.appendLines(listOf("one", "two\ncontinued", "three"))

            assertEquals(listOf("two continued", "three"), store.readRecentLines(2))
            assertEquals(3, store.activeFile().readLines().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `clear removes active and rotated generations`() {
        val directory = Files.createTempDirectory("rzdsp-jsonl").toFile()
        try {
            val store = RotatingJsonlStore(
                directory = directory,
                maximumActiveBytes = 8,
                maximumLineBytes = 64,
            )
            store.appendLine("1234567")
            store.appendLine("abcdefg")
            assertTrue(store.activeFile().exists())
            assertTrue(store.rotatedFile().exists())

            store.clear()

            assertFalse(store.activeFile().exists())
            assertFalse(store.rotatedFile().exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
