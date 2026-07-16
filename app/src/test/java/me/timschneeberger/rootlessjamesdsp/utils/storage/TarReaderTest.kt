package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TarReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `extract keeps safe payload and reads upstream metadata`() {
        val archive = createArchive(
            Entry("shared_prefs/dsp_equalizer.xml", "<map />".toByteArray()),
            Entry("metadata", "min_version_code=51\nis_backup=true".toByteArray())
        )
        val target = temporaryFolder.newFolder("safe")

        val metadata = Tar.Reader(
            ByteArrayInputStream(archive),
            { it == "shared_prefs/dsp_equalizer.xml" }
        ).extract(target)

        assertEquals("51", metadata?.get("min_version_code"))
        assertEquals("true", metadata?.get("is_backup"))
        assertEquals(
            "<map />",
            target.resolve("shared_prefs/dsp_equalizer.xml").readText()
        )
    }

    @Test
    fun `unsafe archive paths are rejected before writing`() {
        val target = temporaryFolder.newFolder("unsafe")
        val archive = createArchive(
            Entry("../shared_prefs/dsp_equalizer.xml", "escape".toByteArray())
        )

        val result = Tar.Reader(ByteArrayInputStream(archive)).extract(target)

        assertNull(result)
        assertFalse(target.exists())
        assertFalse(temporaryFolder.root.resolve("shared_prefs/dsp_equalizer.xml").exists())
    }

    @Test
    fun `unknown entries count toward expanded size limits`() {
        val target = temporaryFolder.newFolder("limited")
        val archive = createArchive(Entry("unknown.bin", ByteArray(9)))

        val result = Tar.Reader(
            ByteArrayInputStream(archive),
            { false },
            Tar.Limits(maxEntryCount = 4, maxEntryBytes = 16, maxTotalBytes = 8)
        ).extract(target)

        assertNull(result)
        assertFalse(target.exists())
    }

    @Test
    fun `ignored entry is drained before a later known entry is extracted`() {
        val target = temporaryFolder.newFolder("ignored")
        val archive = createArchive(
            Entry("ignored.bin", "ignored".toByteArray()),
            Entry("dsp_equalizer.xml", "known".toByteArray())
        )

        val result = Tar.Reader(
            ByteArrayInputStream(archive),
            { it == "dsp_equalizer.xml" }
        ).extract(target)

        assertTrue(result != null)
        assertFalse(target.resolve("ignored.bin").exists())
        assertEquals("known", target.resolve("dsp_equalizer.xml").readText())
    }

    @Test
    fun `single entry expanded size limit is enforced`() {
        val target = temporaryFolder.newFolder("entry-limit")
        val archive = createArchive(Entry("dsp_equalizer.xml", ByteArray(9)))

        val result = Tar.Reader(
            ByteArrayInputStream(archive),
            { true },
            Tar.Limits(maxEntryCount = 4, maxEntryBytes = 8, maxTotalBytes = 16)
        ).extract(target)

        assertNull(result)
        assertFalse(target.exists())
    }

    @Test
    fun `duplicate archive entries are rejected`() {
        val target = temporaryFolder.newFolder("duplicate")
        val archive = createArchive(
            Entry("dsp_equalizer.xml", "first".toByteArray()),
            Entry("dsp_equalizer.xml", "second".toByteArray())
        )

        val result = Tar.Reader(ByteArrayInputStream(archive)).extract(target)

        assertNull(result)
        assertFalse(target.exists())
    }

    @Test
    fun `stale staging content is removed before extraction`() {
        val target = temporaryFolder.newFolder("stale")
        target.resolve("stale.txt").writeText("old")
        val archive = createArchive(Entry("dsp_equalizer.xml", "new".toByteArray()))

        val result = Tar.Reader(ByteArrayInputStream(archive)).extract(target)

        assertTrue(result != null)
        assertFalse(target.resolve("stale.txt").exists())
        assertEquals("new", target.resolve("dsp_equalizer.xml").readText())
    }

    @Test
    fun `metadata-only archive is not a valid preset`() {
        val archive = createArchive(Entry("metadata", "version=3".toByteArray()))

        assertFalse(Tar.Reader(ByteArrayInputStream(archive)).validate())
    }

    @Test
    fun `oversized metadata is rejected before buffering it in memory`() {
        val target = temporaryFolder.newFolder("metadata-limit")
        val archive = createArchive(Entry("metadata", ByteArray(64 * 1024 + 1)))

        val result = Tar.Reader(ByteArrayInputStream(archive)).extract(target)

        assertNull(result)
        assertFalse(target.exists())
    }

    @Test
    fun `normalization rejects traversal absolute and ambiguous separators`() {
        assertNull(Tar.normalizeArchiveEntryName("../dsp.xml"))
        assertNull(Tar.normalizeArchiveEntryName("profiles/../dsp.xml"))
        assertNull(Tar.normalizeArchiveEntryName("/absolute/dsp.xml"))
        assertNull(Tar.normalizeArchiveEntryName("C:/absolute/dsp.xml"))
        assertNull(Tar.normalizeArchiveEntryName("profiles\\..\\dsp.xml"))
        assertNull(Tar.normalizeArchiveEntryName("profiles//dsp.xml"))
        assertEquals("profiles/device/profile.json", Tar.normalizeArchiveEntryName("profiles/device/profile.json"))
    }

    private fun createArchive(vararg entries: Entry): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarOutputStream(bytes).use { output ->
            entries.forEachIndexed { index, entry ->
                val source = temporaryFolder.newFile("archive-entry-$index")
                source.writeBytes(entry.contents)
                output.putNextEntry(TarEntry(source, entry.name))
                output.write(entry.contents)
                output.flush()
            }
        }
        return bytes.toByteArray()
    }

    private data class Entry(val name: String, val contents: ByteArray)
}
