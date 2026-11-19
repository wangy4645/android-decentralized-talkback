package com.talkback.appprod.endpointtext

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.talkback.core.endpointattachment.AttachmentChunker
import java.io.InputStream

object StreamingFileHasher {
    fun querySize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) {
                val size = cursor.getLong(idx)
                if (size > 0L) return size
            }
        }
        return null
    }

    fun sha256Hex(context: Context, uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            sha256Hex(stream)
        }

    fun sha256Hex(stream: InputStream): String {
        val buf = ByteArray(64 * 1024)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    fun copyToTempFile(
        context: Context,
        uri: Uri,
        dest: java.io.File,
    ): Long {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    total += read
                }
            }
        } ?: error("OPEN_FAILED")
        return total
    }
}