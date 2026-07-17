package app.simmo.store

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * A random ID minted once per install, stored *outside* the backed-up
 * `datastore/` directory so backups and device transfers never carry it.
 * After a restore the marker is absent, a fresh one is minted, and the
 * mismatch with [SimmoState.installId] triggers [withInstallValidated]'s
 * subscription-ID invalidation. Blocking file I/O — call off the main thread.
 */
object InstallMarker {
    private const val FILE_NAME = "install_marker"

    fun get(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        file.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val fresh = UUID.randomUUID().toString()
        // Write-then-rename so a crash mid-write can't leave a truncated
        // marker that would re-trigger invalidation on every launch.
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(fresh)
        if (!tmp.renameTo(file)) {
            // Lost a race with a concurrent first launch; use whatever won.
            tmp.delete()
            return file.readText().trim().ifEmpty { fresh }
        }
        return fresh
    }
}
