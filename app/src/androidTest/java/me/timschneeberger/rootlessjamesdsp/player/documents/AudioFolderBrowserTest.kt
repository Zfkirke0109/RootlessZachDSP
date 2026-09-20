package me.timschneeberger.rootlessjamesdsp.player.documents

import android.database.MatrixCursor
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.DirectPlayerActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AudioFolderBrowserTest {
    private val tree = DocumentsContract.buildTreeDocumentUri("example.documents", "root")

    @Test fun cursorScanIsBoundedAndCorrectionIgnoresMime() {
        MatrixCursor(arrayOf("document_id", "_display_name", "mime_type")).use { cursor ->
            repeat(1002) { cursor.addRow(arrayOf("id$it", "Track$it.WVC", "application/octet-stream")) }
            val listing = AudioFolderDocuments.collect(cursor, true)
            assertTrue(listing.truncated)
            assertEquals(1000, listing.entries.size)
            assertEquals("id999", listing.entries.last().id)
        }
    }

    @Test fun browseEnterParentAndDeliverCorrectionUri() {
        ActivityScenario.launch(DirectPlayerActivity::class.java).use { scenario ->
            val selected = AtomicReference<Uri>()
            lateinit var browser: AudioFolderBrowser
            scenario.onActivity { activity ->
                browser = AudioFolderBrowser(activity) { _, parent, correction ->
                    check(correction)
                    AudioFolderListing(if (parent == "root") listOf(AudioFolderEntry("child", "Album", true))
                        else listOf(AudioFolderEntry("correction", "Song.wvc", false)), false)
                }
                browser.open(tree, true) { selected.set(it) }
            }
            awaitText("Album/")
            onView(withText("Album/")).perform(click())
            awaitText("Song.wvc")
            onView(withText(R.string.direct_player_folder_up)).perform(click())
            awaitText("Album/")
            onView(withText("Album/")).perform(click())
            awaitText("Song.wvc")
            onView(withText("Song.wvc")).perform(click())
            assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "correction"), selected.get())
            scenario.onActivity { browser.close() }
        }
    }

    @Test fun closeDiscardsBlockedProviderResult() {
        ActivityScenario.launch(DirectPlayerActivity::class.java).use { scenario ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            lateinit var browser: AudioFolderBrowser
            scenario.onActivity { activity ->
                browser = AudioFolderBrowser(activity) { _, _, _ ->
                    entered.countDown()
                    try {
                        check(release.await(5, TimeUnit.SECONDS))
                        AudioFolderListing(listOf(AudioFolderEntry("late", "Late.wv", false)), false)
                    } finally { exited.countDown() }
                }
                browser.open(tree, false) { error("Must not select stale result") }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity { browser.close() }
            } finally { release.countDown() }
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText("Late.wv")).check(doesNotExist())
        }
    }

    private fun awaitText(text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (System.nanoTime() < deadline) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = WindowInspector.getGlobalWindowViews().any { containsText(it, text) }
            }
            if (visible) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for folder entry: $text")
    }

    private fun containsText(view: View, text: String): Boolean {
        if (!view.isShown) return false
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
    }
}
