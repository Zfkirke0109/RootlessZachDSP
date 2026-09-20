package me.timschneeberger.rootlessjamesdsp.player.documents

import java.util.Locale

/** Discovery only: the decoder still validates the selected document's content. */
internal object AudioDocumentSelection {
    fun accepts(name: String, mime: String?, correction: Boolean): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (correction) return extension == "wvc"
        if (extension == "wvc") return false
        return extension in setOf("wv", "flac", "wav", "mp3", "m4a", "aac", "ogg", "opus") ||
            mime?.startsWith("audio/", ignoreCase = true) == true
    }
}
