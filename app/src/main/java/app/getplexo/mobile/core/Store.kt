package app.getplexo.mobile.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** Hands a finished temp file to the public Downloads collection. */
object Store {

    fun save(context: Context, source: File, displayName: String, mime: String?): String {
        val resolver = context.contentResolver
        val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Plexo")
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create the file in Downloads")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                // One big buffer: this is a straight copy of the finished file, and the
                // 8 KB default made multi-gigabyte saves needlessly slow.
                source.inputStream().use { input -> input.copyTo(out, 1024 * 1024) }
            } ?: throw IOException("Could not open the destination file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        // MediaStore renames on a name collision ("file (1).iso"); report what it kept.
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: displayName
        return "Download/Plexo/$actual"
    }

    fun sanitize(raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringBefore('?')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trimStart('.')
        return cleaned.ifBlank { "download" }.take(180)
    }
}
