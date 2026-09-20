package me.timschneeberger.rootlessjamesdsp.player.documents

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R

/** Reads only immediate children of the user-granted SAF tree, independent of picker MIME filters. */
internal class AudioFolderBrowser(
    private val activity: AppCompatActivity,
    private val readChildren: (Uri, String, Boolean) -> AudioFolderListing = { tree, parent, correction ->
        AudioFolderDocuments.read(activity.contentResolver, tree, parent, correction)
    },
) {
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var generation = 0

    fun close() {
        generation++
        job?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun open(tree: Uri, correction: Boolean, selected: (Uri) -> Unit) {
        close()
        show(tree, listOf(DocumentsContract.getTreeDocumentId(tree)), correction, selected)
    }

    private fun show(tree: Uri, path: List<String>, correction: Boolean, selected: (Uri) -> Unit) {
        val expected = ++generation
        job = activity.lifecycleScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { readChildren(tree, path.last(), correction) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (expected != generation || activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { listing ->
                val entries = listing.entries.sortedWith(compareBy<AudioFolderEntry> { !it.directory }.thenBy { it.name.lowercase() })
                val labels = entries.map { if (it.directory) "${it.name}/" else it.name }.toTypedArray()
                val builder = AlertDialog.Builder(activity)
                    .setTitle(if (correction) R.string.direct_player_folder_correction else R.string.direct_player_folder_audio)
                    .setNegativeButton(android.R.string.cancel, null)
                if (labels.isNotEmpty()) builder.setItems(labels) { _, index ->
                    val entry = entries[index]
                    if (entry.directory) show(tree, path + entry.id, correction, selected)
                    else selected(DocumentsContract.buildDocumentUriUsingTree(tree, entry.id))
                } else builder.setMessage(R.string.direct_player_folder_empty)
                if (path.size > 1) builder.setNeutralButton(R.string.direct_player_folder_up) { _, _ ->
                    show(tree, path.dropLast(1), correction, selected)
                }
                dialog = builder.show()
                if (listing.truncated) android.widget.Toast.makeText(activity,
                    R.string.direct_player_folder_limit, android.widget.Toast.LENGTH_LONG).show()
            }.onFailure {
                dialog = AlertDialog.Builder(activity)
                    .setMessage(R.string.direct_player_folder_failed)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

}

internal data class AudioFolderEntry(val id: String, val name: String, val directory: Boolean)
internal data class AudioFolderListing(val entries: List<AudioFolderEntry>, val truncated: Boolean)

internal object AudioFolderDocuments {
    fun read(resolver: ContentResolver, tree: Uri, parent: String, correction: Boolean): AudioFolderListing {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: error("Document provider returned no cursor")
        return cursor.use { collect(it, correction) }
    }

    fun collect(cursor: Cursor, correction: Boolean): AudioFolderListing {
        val entries = mutableListOf<AudioFolderEntry>()
        var scanned = 0
        var truncated = false
        while (cursor.moveToNext()) {
            if (++scanned > MAX_CHILDREN) { truncated = true; break }
            val id = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            val mime = cursor.getString(2)
            val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
            if (directory || AudioDocumentSelection.accepts(name, mime, correction)) {
                entries.add(AudioFolderEntry(id, name, directory))
            }
        }
        return AudioFolderListing(entries, truncated)
    }
    private const val MAX_CHILDREN = 1000
}
