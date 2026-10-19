package com.mikaso.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/** Where a saved file ended up. */
data class SavedFile(val uri: String, val size: Long)

/**
 * Writes finished downloads into the phone's media store (or a folder the user picked),
 * and answers the small questions the Downloads screen asks about existing files.
 *
 * Uses MediaStore on Android 10+ so no storage permission is needed for the default locations.
 */
class FileStore(private val context: Context) {

    fun locationLabel(s: SettingsState): String = when (s.saveMode) {
        SaveMode.Default -> "Movies / Mikaso · Music / Mikaso"
        SaveMode.Downloads -> "Download / Mikaso"
        SaveMode.Custom ->
            if (s.saveTree.isNotBlank()) treeLabel(Uri.parse(s.saveTree)) else "Choose a folder…"
    }

    /** A friendly name for a folder the user picked with the system picker. */
    fun treeLabel(uri: Uri): String {
        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: "Selected folder"
    }

    fun exists(uri: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun delete(uri: String): Boolean = try {
        context.contentResolver.delete(Uri.parse(uri), null, null) > 0
    } catch (e: Exception) {
        false
    }

    /** Save [stream] as [filename]. Returns null if nothing could be written. */
    fun save(stream: InputStream, filename: String, mime: String, kind: MediaKind, settings: SettingsState): SavedFile? =
        when (settings.saveMode) {
            SaveMode.Custom -> saveToTree(stream, filename, mime, settings.saveTree)
            else -> saveToMediaStore(stream, filename, mime, kind, settings.saveMode)
        }

    private fun saveToMediaStore(
        stream: InputStream,
        filename: String,
        mime: String,
        kind: MediaKind,
        mode: SaveMode,
    ): SavedFile? {
        val collection = if (kind == MediaKind.Audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(kind, mode))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return null

        val size = try {
            context.contentResolver.openOutputStream(uri)?.use { out -> stream.copyTo(out) } ?: return null
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return null
        } finally {
            runCatching { stream.close() }
        }

        runCatching {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return SavedFile(uri.toString(), size)
    }

    private fun saveToTree(stream: InputStream, filename: String, mime: String, treeUri: String): SavedFile? {
        if (treeUri.isBlank()) return null
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull() ?: return null
        val file = try {
            tree.createFile(mime, filename)
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val size = context.contentResolver.openOutputStream(file.uri)?.use { out -> stream.copyTo(out) } ?: return null
            SavedFile(file.uri.toString(), size)
        } catch (e: Exception) {
            runCatching { file.delete() }
            null
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun relativePath(kind: MediaKind, mode: SaveMode): String = when {
        mode == SaveMode.Downloads -> "${Environment.DIRECTORY_DOWNLOADS}/Mikaso"
        kind == MediaKind.Audio -> "${Environment.DIRECTORY_MUSIC}/Mikaso"
        else -> "${Environment.DIRECTORY_MOVIES}/Mikaso"
    }
}
