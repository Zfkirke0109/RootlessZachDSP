package me.timschneeberger.rootlessjamesdsp.player.documents

import org.junit.Assert.*
import org.junit.Test

class AudioDocumentSelectionTest {
    @Test fun `extension finds wavpack even with unknown MIME`() {
        assertTrue(AudioDocumentSelection.accepts("Track.WV", "application/octet-stream", false))
        assertTrue(AudioDocumentSelection.accepts("Track.WVC", null, true))
        assertFalse(AudioDocumentSelection.accepts("Track.wvc", "audio/wavpack", false))
        assertFalse(AudioDocumentSelection.accepts("Track.wv", "audio/wavpack", true))
    }
    @Test fun `only supported sources or audio MIME appear`() {
        assertTrue(AudioDocumentSelection.accepts("Track.flac", null, false))
        assertTrue(AudioDocumentSelection.accepts("Track", "audio/mpeg", false))
        assertFalse(AudioDocumentSelection.accepts("Track.exe", "application/octet-stream", false))
    }
}
