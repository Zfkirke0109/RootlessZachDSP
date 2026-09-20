package me.timschneeberger.rootlessjamesdsp.session.dump.utils

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasDumpPermission
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

object DumpUtils {
    private const val TIMEOUT_MS = 5_000L
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun dumpLines(context: Context, service: String, args: Array<String> = emptyArray()): List<String>? =
        dumpAll(context, service, args)?.lines()

    fun dumpAll(context: Context, service: String, args: Array<String> = emptyArray()): String? {
        if (!context.hasDumpPermission()) return null
        try {
            val binder = SystemServiceHelper.getSystemService(service) ?: return null
            val pipe = ParcelFileDescriptor.createPipe()
            pipe[0].use { read ->
                pipe[1].use { write -> binder.dumpAsync(write.fileDescriptor, args) }
                val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
                val poll = StructPollfd().apply {
                    fd = read.fileDescriptor
                    events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Dump cancelled")
                    if (System.nanoTime() >= deadline) throw IOException("Dump timed out")
                    if (Os.poll(arrayOf(poll), 100) == 0) continue
                    if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
                        throw IOException("Dump pipe unavailable")
                    }
                    val count = Os.read(read.fileDescriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("Dump exceeds size limit")
                    output.write(buffer, 0, count)
                }
                return output.toString("UTF-8")
            }
        } catch (cancelled: InterruptedIOException) {
            // Preserve interruption for runInterruptible; never publish a partial snapshot.
            Thread.currentThread().interrupt()
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Unable to collect %s snapshot", service)
            return null
        }
    }
}
