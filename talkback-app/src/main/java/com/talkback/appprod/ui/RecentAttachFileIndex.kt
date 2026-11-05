package com.talkback.appprod.ui

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.talkback.core.endpointattachment.AttachmentTransferPolicy

data class AttachFileCandidate(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?
)

object RecentAttachFileIndex {
    private const val QUERY_LIMIT = 40

    fun load(context: Context, maxBytes: Int = AttachmentTransferPolicy.DEFAULT_MAX_ATTACHMENT_BYTES): List<AttachFileCandidate> {
        if (!canQueryRecentIndex(context)) return emptyList()
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) AND ${MediaStore.Files.FileColumns.SIZE}<=? AND ${MediaStore.Files.FileColumns.SIZE}>0"
            args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString(),
                maxBytes.toString(),
            )
        } else {
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? AND ${MediaStore.Files.FileColumns.SIZE}<=? AND ${MediaStore.Files.FileColumns.SIZE}>0"
            args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
                maxBytes.toString(),
            )
        }
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        val out = ArrayList<AttachFileCandidate>()
        resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext() && out.size < QUERY_LIMIT) {
                val mime = cursor.getString(mimeCol)?.lowercase().orEmpty()
                if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) {
                    continue
                }
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val size = cursor.getLong(sizeCol)
                val uri = ContentUris.withAppendedId(collection, id)
                out.add(AttachFileCandidate(uri, name, size, mime.ifBlank { null }))
            }
        }
        return out
    }

    fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun canQueryRecentIndex(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Scoped storage: index may be partial without legacy READ; still try the query.
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
